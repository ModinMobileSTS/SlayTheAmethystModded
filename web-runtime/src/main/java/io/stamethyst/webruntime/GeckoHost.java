package io.stamethyst.webruntime;

import android.content.Context;
import android.view.View;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;

/** The small reflection ABI exposed to the launcher; Gecko types never cross this boundary. */
public final class GeckoHost {
    private static GeckoRuntime runtime;
    private final GeckoSession session;
    private final GeckoView view;

    public GeckoHost(Context context) {
        synchronized (GeckoHost.class) {
            if (runtime == null) runtime = GeckoRuntime.create(context.getApplicationContext(),
                new GeckoRuntimeSettings.Builder().consoleOutput(true).javaScriptEnabled(true)
                    .isolatedProcessEnabled(false).appZygoteProcessEnabled(false).build());
        }
        session = new GeckoSession(new GeckoSessionSettings.Builder()
            .displayMode(GeckoSessionSettings.DISPLAY_MODE_FULLSCREEN)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .suspendMediaWhenInactive(true).build());
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession session, String url) {
                android.util.Log.i("STS-Gecko", "Page started: " + url);
            }
            @Override public void onPageStop(GeckoSession session, boolean success) {
                android.util.Log.i("STS-Gecko", "Page stopped; success=" + success);
            }
        });
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onCrash(GeckoSession session) { android.util.Log.e("STS-Gecko", "Content process crashed"); }
        });
        session.open(runtime);
        view = new GeckoView(context);
        view.setSession(session);
    }
    public View getView() { return view; }
    public void loadUrl(String url) { session.loadUri(url); }
    public void setActive(boolean active) { session.setActive(active); }
    public void stop() { session.stop(); }
    public void close() { view.releaseSession(); session.close(); }
}
