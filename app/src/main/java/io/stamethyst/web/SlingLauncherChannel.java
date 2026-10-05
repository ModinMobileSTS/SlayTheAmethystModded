package io.stamethyst.web;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

/** Per-view capability exposing only the three launcher callbacks. */
public final class SlingLauncherChannel {
    public final String token = UUID.randomUUID().toString();
    private final Object launcher;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayBlockingQueue<String> scripts = new ArrayBlockingQueue<>(128);
    private volatile boolean closed;

    public SlingLauncherChannel(Object launcher) { this.launcher = launcher; }
    public void evaluate(String script) {
        if (closed) return;
        if (!scripts.offer(script)) { scripts.poll(); scripts.offer(script); }
    }
    public String poll() {
        JSONArray commands = new JSONArray();
        String script;
        while ((script = scripts.poll()) != null) commands.put(script);
        return commands.toString();
    }
    public void dispatch(String method, String detail) {
        if (closed || !(method.equals("onPageReady") || method.equals("enterGame") || method.equals("scriptError"))) return;
        main.post(() -> {
            if (closed) return;
            try {
                java.lang.reflect.Method callback = method.equals("scriptError")
                    ? launcher.getClass().getDeclaredMethod(method, String.class)
                    : launcher.getClass().getDeclaredMethod(method);
                callback.setAccessible(true);
                if (method.equals("scriptError")) callback.invoke(launcher, detail);
                else callback.invoke(launcher);
            } catch (Exception e) { android.util.Log.e("SlingLauncherBridge", "Callback failed: " + method, e); }
        });
    }
    public void close() { closed = true; scripts.clear(); }
}
