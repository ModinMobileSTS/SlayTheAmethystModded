package io.stamethyst.godot;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.webkit.*;
import org.json.JSONTokener;

/** Reads localStorage in its owning process. Never opens/copies a live LevelDB database. */
public class LegacySlingBreakSaveService extends Service {
    public static final String KEY = "slingbreak-save-v1";
    static final int MAX_LENGTH = 256 * 1024;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView reader;
    private Messenger reply;
    private final Runnable timeout = () -> finishRead(null, "timeout");

    /** The boot overlay used app_webview_game, not the launcher's app_webview. */
    public static final class GameProcess extends LegacySlingBreakSaveService { }

    @Override public IBinder onBind(Intent intent) {
        return new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (message.replyTo != null && reply == null) {
                reply = message.replyTo;
                read();
            }
            return true;
        })).getBinder();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void read() {
        handler.postDelayed(timeout, 10000);
        try {
            reader = new WebView(this);
            reader.getSettings().setJavaScriptEnabled(true);
            reader.getSettings().setDomStorageEnabled(true);
            reader.getSettings().setAllowFileAccess(true);
            reader.getSettings().setAllowContentAccess(false);
            reader.getSettings().setBlockNetworkLoads(true);
            reader.setWebViewClient(new WebViewClient() {
                private boolean evaluated;
                @Override public void onPageFinished(WebView view, String url) {
                    if (evaluated) return;
                    evaluated = true;
                    // Synthetic, trusted HTML under the original file origin; no old game code runs.
                    view.evaluateJavascript("(function(){try{return localStorage.getItem('" + KEY + "')}catch(e){return '__read_error__'}})()", value -> {
                        try {
                            Object decoded = new JSONTokener(value).nextValue();
                            if (!(decoded instanceof String)) { finishRead(null, null); return; }
                            String save = (String) decoded;
                            if ("__read_error__".equals(save) || save.length() > MAX_LENGTH) {
                                finishRead(null, "unreadable_or_too_large");
                            } else finishRead(save, null);
                        } catch (Exception error) { finishRead(null, "invalid_result"); }
                    });
                }
            });
            reader.loadDataWithBaseURL("file:///android_asset/slingbreak/index.html",
                "<!doctype html><html><head><meta charset='utf-8'></head><body></body></html>",
                "text/html", "UTF-8", null);
        } catch (Throwable error) { finishRead(null, error.getClass().getSimpleName()); }
    }

    private void finishRead(String save, String error) {
        handler.removeCallbacks(timeout);
        Messenger target = reply;
        reply = null;
        if (reader != null) { reader.stopLoading(); reader.destroy(); reader = null; }
        if (target == null) return;
        Message result = Message.obtain(null, 1);
        Bundle data = new Bundle(); data.putString("save", save); data.putString("error", error);
        result.setData(data);
        try { target.send(result); } catch (RemoteException ignored) { }
    }

    @Override public void onDestroy() {
        finishRead(null, "service_destroyed");
        super.onDestroy();
    }
}
