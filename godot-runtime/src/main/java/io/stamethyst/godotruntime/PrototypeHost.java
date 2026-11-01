package io.stamethyst.godotruntime;

import android.app.Activity;
import android.content.res.Configuration;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.function.Consumer;
import org.godotengine.godot.Godot;
import org.godotengine.godot.GodotHost;
import org.godotengine.godot.plugin.GodotPlugin;
import org.godotengine.godot.plugin.UsedByGodot;

/** Reflective boundary uses only Android/JDK types; the launcher has no Godot dependency. */
public final class PrototypeHost implements GodotHost {
    private final Activity activity;
    private final Godot godot;
    private final Bridge bridge;
    private final Consumer<String> events;
    private final FrameLayout view;
    private boolean destroyed;

    public PrototypeHost(Activity activity, String apk, String libraries, String gameAssets,
                         Consumer<String> events) throws Exception {
        this.activity = activity;
        this.events = events;
        godot = Godot.getInstance(new EngineContext(activity, apk, libraries, gameAssets));
        bridge = new Bridge(godot);
        if (!godot.initEngine(this, Arrays.asList("--main-pack", "res://game.pck", "--rendering-method", "gl_compatibility"),
                Collections.singleton(bridge))) throw new IllegalStateException("Godot initEngine failed");
        view = godot.onInitRenderView(this, new FrameLayout(activity));
    }
    public View getView() { return view; }
    public void start() { godot.onStart(this); }
    public void resume() { godot.onResume(this); }
    public void pause() { godot.onPause(this); }
    public void stop() { godot.onStop(this); }
    public void configuration(Configuration config) { godot.onConfigurationChanged(config); }
    public void destroy() { if (!destroyed) { destroyed = true; godot.onDestroy(this); } }
    public void state(int progress, String message, boolean ready) {
        bridge.progress = progress; bridge.message = message; bridge.ready = ready;
    }
    public void leave() { bridge.leaving = true; }
    @Override public Activity getActivity() { return activity; }
    @Override public Godot getGodot() { return godot; }
    @Override public Set<GodotPlugin> getHostPlugins(Godot engine) { return Collections.singleton(bridge); }
    @Override public void onGodotMainLoopStarted() { events.accept("main_loop_started"); }
    @Override public void onGodotForceQuit(Godot engine) { activity.runOnUiThread(activity::finish); }
    @Override public void onGodotRestartRequested(Godot engine) { events.accept("restart_not_supported"); }

    public final class Bridge extends GodotPlugin {
        volatile int progress;
        volatile String message = "";
        volatile boolean ready, leaving;
        Bridge(Godot godot) { super(godot); }
        @Override public String getPluginName() { return "SlingPrototype"; }
        @UsedByGodot public int get_progress() { return progress; }
        @UsedByGodot public String get_message() { return message; }
        @UsedByGodot public boolean is_ready() { return ready; }
        @UsedByGodot public boolean is_launcher_mode() { return activity.getIntent().hasExtra("boot"); }
        @UsedByGodot public boolean should_leave() { return leaving; }
        @UsedByGodot public void report(String event) {
            Log.i("GodotPrototype", "gdscript=" + event);
            events.accept(event);
        }
    }
}
