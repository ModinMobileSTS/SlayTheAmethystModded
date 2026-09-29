package io.stamethyst.backend.diag

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/** Read-only host observations; these do not reveal WebView's actual stream buffer or route. */
internal object WebViewAudioEnvironment {
    // Called on the WebView thread, only with the existing audio diagnostics opt-in enabled.
    fun record(context: Context) {
        runCatching {
            val report = JSONObject()
                .put("version", 1)
                .put("pid", Process.myPid())
                .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("note", "System capability reports and minimum PCM16 buffer queries, not measured " +
                    "speaker latency or WebView's actual buffer. Available outputs are not the active route.")

            fun capture(key: String, read: () -> Any?) {
                try {
                    report.put(key, read() ?: JSONObject.NULL)
                } catch (error: Exception) {
                    report.put(key, JSONObject.NULL)
                    report.put("${key}Error", error.javaClass.simpleName)
                }
            }

            capture("webViewProvider") {
                WebView.getCurrentWebViewPackage()?.let {
                    JSONObject().put("packageName", it.packageName).put("versionName", it.versionName)
                }
            }
            capture("lowLatencyFeature") {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)
            }
            capture("proAudioFeature") {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)
            }
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            capture("outputSampleRateProperty") { audio?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) }
            capture("outputFramesPerBufferProperty") { audio?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) }
            capture("audioMode") { audio?.mode }
            capture("availableOutputTypes") {
                audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.let { devices ->
                    JSONArray(devices.map { it.type }.distinct())
                }
            }
            capture("minimumOutputBuffers") {
                val nativeRate = report.optString("outputSampleRateProperty").toIntOrNull()
                val rates = listOfNotNull(nativeRate?.takeIf { it > 0 }, 48000).distinct()
                JSONArray().apply {
                    for (rate in rates) {
                        for (channels in 1..2) {
                            val config = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                            val bytes = AudioTrack.getMinBufferSize(rate, config, AudioFormat.ENCODING_PCM_16BIT)
                            put(JSONObject().apply {
                                put("sampleRate", rate)
                                put("channels", channels)
                                put("minBufferBytesOrError", bytes)
                                if (bytes > 0) {
                                    val frames = bytes.toDouble() / (2 * channels)
                                    put("frames", frames)
                                    put("bufferDurationMs", frames * 1000 / rate)
                                }
                            })
                        }
                    }
                }
            }
            WebViewDiagnosticsLogStore.append(context, "audio_environment", report.toString())
        }.onFailure {
            WebViewDiagnosticsLogStore.append(context, "audio_environment_error", it.javaClass.simpleName)
        }
    }
}
