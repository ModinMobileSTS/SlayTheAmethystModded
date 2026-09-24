package io.stamethyst.backend.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.Keep
import io.stamethyst.backend.diag.WebViewDiagnosticsLogStore
import io.stamethyst.ui.preferences.LauncherPreferences
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.WeakHashMap

/** A versioned, sample-only capability; no paths, arbitrary native calls or device-name rules. */
@Keep
internal class SlingNativeAudioBridge private constructor(context: Context) {
    private val application = context.applicationContext
    private var session = 0L
    private var closed = false
    private var active = true
    private var muted = true
    private var committed = false
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequested = false
    private var focusGranted = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change -> focusChanged(change) }, handler)
        .build()
    private val healthCheck = object : Runnable {
        override fun run() {
            synchronized(this@SlingNativeAudioBridge) {
                if (closed || session == 0L || !committed) return
                nativeMaintain(session)
                if (JSONObject(nativeInfo(session)).optBoolean("failed")) {
                    log("disconnected", nativeInfo(session))
                    // Close outside AAudio's error callback, including when the game is idle.
                    reset()
                } else handler.postDelayed(this, 1000)
            }
        }
    }

    private fun requestFocus() {
        if (focusRequested || !active || muted) return
        focusGranted = audioManager?.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequested = focusGranted
        log("focus_request", "granted=$focusGranted")
    }

    private fun abandonFocus() {
        if (focusRequested) audioManager?.abandonAudioFocusRequest(focusRequest)
        focusRequested = false; focusGranted = false
    }

    @Synchronized private fun focusChanged(change: Int) {
        if (closed || session == 0L || !focusRequested) return
        focusGranted = change == AudioManager.AUDIOFOCUS_GAIN
        if (change == AudioManager.AUDIOFOCUS_LOSS) focusRequested = false
        nativeMute(session, muted || !active || !focusGranted)
        log("focus_change", "change=$change granted=$focusGranted")
    }

    companion object {
        private const val INTERFACE = "SlingNativeAudio"
        private val instances = WeakHashMap<WebView, SlingNativeAudioBridge>()
        private val libraryAvailable by lazy {
            runCatching { System.loadLibrary("sling_audio"); true }.getOrDefault(false)
        }
        private val types = listOf("draw", "shoot", "tap", "break", "boom", "ricochet",
            "lightning", "frost", "prism", "gold", "core", "win", "upgrade")

        // Registry and WebView operations run on the UI thread. Bridge calls are synchronized
        // with activity lifecycle calls; JNI also serializes stream control operations.
        fun attach(view: WebView) {
            close(view)
            if (!libraryAvailable) return
            val bridge = SlingNativeAudioBridge(view.context)
            instances[view] = bridge
            view.addJavascriptInterface(bridge, INTERFACE)
        }

        fun pageStarted(view: WebView) { instances[view]?.reset() }
        fun setActive(view: WebView, active: Boolean) { instances[view]?.activate(active) }
        fun close(view: WebView) {
            instances.remove(view)?.shutdown()
            view.removeJavascriptInterface(INTERFACE)
        }
    }

    private fun log(event: String, detail: String) {
        if (LauncherPreferences.isSlingBreakAudioDebugModeEnabled(application)) {
            WebViewDiagnosticsLogStore.append(application, "native_audio_$event", detail)
        }
    }

    @Synchronized private fun reset() {
        handler.removeCallbacks(healthCheck)
        abandonFocus()
        if (session != 0L) nativeRelease(session)
        session = 0L; committed = false; muted = true
    }

    @Synchronized private fun shutdown() { closed = true; reset() }

    @Synchronized private fun activate(value: Boolean) {
        active = value
        if (!value) abandonFocus()
        if (session == 0L) return
        if (!nativeActive(session, value)) log("lifecycle_failure", nativeInfo(session))
        else if (value && committed) {
            requestFocus()
            nativeMute(session, muted || !focusGranted)
        }
    }

    @JavascriptInterface @Synchronized fun begin(): String {
        if (closed || !active) return "{\"error\":\"host-inactive\"}"
        // A document owns one immutable sample bank. Stale documents cannot replace it.
        if (session != 0L) return "{\"error\":\"session-exists\"}"
        session = nativeCreate()
        val result = if (session == 0L) "{\"error\":\"no-small-buffer-stream\"}" else nativeInfo(session)
        log("opened", result)
        return result
    }

    @JavascriptInterface @Synchronized fun upload(token: Long, id: Int, base64: String): Boolean {
        if (closed || token != session || token == 0L || committed || id !in 0..255 || base64.length > 2_100_000) return false
        return runCatching {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            if (bytes.isEmpty() || bytes.size % 4 != 0 || bytes.size > 1_536_000) return false
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val pcm = FloatArray(buffer.remaining()); buffer.get(pcm)
            nativeUpload(token, id, pcm)
        }.getOrDefault(false)
    }

    @JavascriptInterface @Synchronized fun commit(token: Long): Boolean {
        if (closed || token == 0L || token != session || committed || !active) return false
        committed = nativeCommit(token)
        if (committed) handler.postDelayed(healthCheck, 1000)
        log(if (committed) "ready" else "start_failure", nativeInfo(token))
        return committed
    }

    /** Positive means queued, zero means intentionally dropped, negative means fallback. */
    @JavascriptInterface @Synchronized fun play(token: Long, payload: String): Int {
        if (closed || token == 0L || token != session || !committed) return -1
        if (!active || muted || !focusGranted) return 0
        if (payload.length > 8192) return -2
        return runCatching {
            val command = JSONObject(payload)
            val type = types.indexOf(command.getString("type"))
            val voices = command.getJSONArray("voices")
            if (type < 0 || voices.length() !in 1..12) return -2
            val ids = IntArray(voices.length())
            val params = FloatArray(voices.length() * 3)
            for (i in ids.indices) {
                val voice = voices.getJSONObject(i)
                ids[i] = voice.getInt("id")
                params[i * 3] = voice.getDouble("delay").toFloat()
                params[i * 3 + 1] = voice.getDouble("pan").toFloat()
                params[i * 3 + 2] = if (voice.optBoolean("wet")) 1f else 0f
            }
            val result = nativePlay(token, type, command.optBoolean("priority"), ids, params)
            if (result < 0) log("play_failure", nativeInfo(token))
            result
        }.getOrDefault(-2)
    }

    @JavascriptInterface @Synchronized fun setMuted(token: Long, value: Boolean): Boolean {
        if (closed || token == 0L || token != session || !committed) return false
        muted = value
        if (value) abandonFocus() else requestFocus()
        return nativeMute(token, value || !active || !focusGranted)
    }

    @JavascriptInterface @Synchronized fun snapshot(token: Long): String {
        return if (!closed && token != 0L && token == session) JSONObject(nativeInfo(token))
            .put("hostActive", active).put("muted", muted).put("focusGranted", focusGranted).toString()
        else "{\"error\":\"no-session\"}"
    }

    @JavascriptInterface @Synchronized fun release(token: Long) {
        if (token != 0L && token == session) { log("closed", nativeInfo(token)); reset() }
    }

    private external fun nativeCreate(): Long
    private external fun nativeInfo(token: Long): String
    private external fun nativeUpload(token: Long, id: Int, pcm: FloatArray): Boolean
    private external fun nativeCommit(token: Long): Boolean
    private external fun nativePlay(token: Long, type: Int, priority: Boolean, ids: IntArray, params: FloatArray): Int
    private external fun nativeMute(token: Long, muted: Boolean): Boolean
    private external fun nativeActive(token: Long, active: Boolean): Boolean
    private external fun nativeMaintain(token: Long)
    private external fun nativeRelease(token: Long)
}
