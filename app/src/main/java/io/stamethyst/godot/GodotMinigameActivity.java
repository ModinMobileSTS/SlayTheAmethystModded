package io.stamethyst.godot;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.AssetManager;
import android.os.*;
import android.util.Log;
import android.view.View;
import android.widget.*;
import java.util.function.Consumer;

public final class GodotMinigameActivity extends Activity {
    private static final java.util.concurrent.atomic.AtomicBoolean ACTIVE = new java.util.concurrent.atomic.AtomicBoolean();
    private boolean ownsEngineProcess;
    private Object host;
    private Context engineContext;
    private Messenger boot;
    private Messenger incoming;
    private boolean started, resumed, leaving, ready;
    private boolean engineFailed;
    private boolean saveFailed;
    private TextView status;
    private Button enter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final IBinder.DeathRecipient death = () -> handler.post(() -> {
        ready = false;
        call("state", new Class<?>[]{int.class, String.class, boolean.class}, 0, "主游戏进程已退出", false);
        if (status != null) { status.setVisibility(View.VISIBLE); status.setText("主游戏进程已退出；请保存并返回启动器"); }
        if (enter != null) { enter.setVisibility(View.VISIBLE); enter.setText("保存并返回"); }
    });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ownsEngineProcess = ACTIVE.compareAndSet(false, true);
        if (!ownsEngineProcess) { finish(); return; }
        boot = getIntent().getParcelableExtra("boot");
        incoming = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (message.what == 10) {
                Bundle data = message.getData(); ready = data.getBoolean("ready");
                status.setText(data.getInt("progress") + "% " + data.getString("message", ""));
                enter.setText(ready ? "启动游戏" : "返回主游戏");
                call("state", new Class<?>[]{int.class, String.class, boolean.class}, data.getInt("progress"), data.getString("message", ""), ready);
                Log.i("GodotPrototype", "progress=" + data.getInt("progress") + " ready=" + ready);
            }
            if (message.what == 11) requestLeave();
            return true;
        }));
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setBackgroundColor(0xffd5d9cc);
        layout.setFitsSystemWindows(true);
        status = new TextView(this); status.setText("校验并启动外部 Godot 引擎…"); layout.addView(status);
        FrameLayout game = new FrameLayout(this); layout.addView(game, new LinearLayout.LayoutParams(-1, 0, 1));
        enter = new Button(this); enter.setText(boot == null ? "保存并返回" : "返回主游戏");
        enter.setOnClickListener(v -> requestLeave()); layout.addView(enter); setContentView(layout);
        // Register before native initialization so early JNI crashes are visible via Binder death.
        if (boot != null) {
            try { boot.getBinder().linkToDeath(death, 0); send(1, 0); }
            catch (RemoteException error) { failure(error); }
        }
        new Thread(() -> {
            try {
                io.stamethyst.backend.update.UpdateReleaseInfo release = io.stamethyst.backend.minigame.SlingBreakInstallation.read(this);
                if (release == null) throw new java.io.IOException("小游戏依赖未安装，请返回设置下载");
                GodotFiles files = new GodotFiles(this, release);
                ClassLoader loader = files.loader();
                Class<?> type = loader.loadClass("io.stamethyst.godotruntime.PrototypeHost");
                handler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    try {
                        engineContext = (Context) loader.loadClass("io.stamethyst.godotruntime.EngineContext")
                            .getConstructor(Context.class, String.class, String.class, String.class)
                            .newInstance(this, new java.io.File(files.engine(), "engine.apk").getPath(),
                                new java.io.File(files.engine(), "lib").getPath(), files.assets().getPath());
                        host = type.getConstructor(Activity.class, String.class, String.class, String.class, Consumer.class)
                            .newInstance(this, new java.io.File(files.engine(), "engine.apk").getPath(),
                                new java.io.File(files.engine(), "lib").getPath(), files.assets().getPath(),
                                (Consumer<String>) event -> handler.post(() -> event(event)));
                        game.addView((View) type.getMethod("getView").invoke(host), new FrameLayout.LayoutParams(-1, -1));
                        if (started) call("start"); if (resumed) call("resume");
                        if (boot != null) { send(1, 0); send(3, resumed ? 1 : 0); }
                        else status.setText("Godot 已启动；等待 GDScript ready");
                    } catch (Throwable error) { failure(error); }
                });
            } catch (Throwable error) { handler.post(() -> failure(error)); }
        }, "godot-component-verify").start();
    }
    private void event(String event) {
        Log.i("GodotPrototype", event);
        if ("enter_requested".equals(event) && boot != null && ready) requestLeave();
        else if ("save_retry_requested".equals(event)) { saveFailed = false; leaving = false; requestLeave(); }
        else if ("launcher_ui_ready".equals(event) && boot != null) {
            status.setVisibility(View.GONE); enter.setVisibility(View.GONE);
        }
        else if ("saved".equals(event) && leaving) { if (boot != null) send(2, 0); finish(); }
        else if ("save_failed".equals(event)) {
            saveFailed = true;
            leaving = false; handler.removeCallbacks(saveTimeout);
            if (boot == null) {
                status.setVisibility(View.VISIBLE); status.setText("小游戏保存失败，已有存档未清理，可继续游玩或确认返回");
                enter.setVisibility(View.VISIBLE); enter.setText("返回（保存未确认）"); enter.setOnClickListener(v -> confirmAbandon());
            }
        }
        else if ("ready".equals(event) && boot == null) {
            status.setVisibility(View.GONE); enter.setVisibility(View.GONE);
        }
    }
    // Native UI helpers sometimes ask the Activity (not Godot.context) for Godot resource IDs.
    @Override public Resources getResources() { return engineContext == null ? super.getResources() : engineContext.getResources(); }
    @Override public AssetManager getAssets() { return engineContext == null ? super.getAssets() : engineContext.getAssets(); }
    // GodotIO prefers the host Activity, while its storage handlers use Godot.context.
    // Both must resolve exactly the same private directory or writes are classified as shared storage.
    @Override public java.io.File getFilesDir() {
        java.io.File dir = new java.io.File(super.getFilesDir(), "godot-prototype-user");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create isolated Godot save directory");
        return dir;
    }
    private void failure(Throwable error) {
        engineFailed = true;
        Log.e("GodotPrototype", "engine_failure", error);
        status.setVisibility(View.VISIBLE); enter.setVisibility(View.VISIBLE);
        status.setText(Log.getStackTraceString(error));
        enter.setText("返回（引擎启动失败）");
    }
    private void requestLeave() {
        if (saveFailed) { confirmAbandon(); return; }
        if (leaving) return;
        leaving = true;
        if (host == null || engineFailed) { if (boot != null) send(2, 0); finish(); return; }
        call("leave"); status.setText("等待 GDScript 保存确认…");
        handler.removeCallbacks(saveTimeout);
        handler.postDelayed(saveTimeout, 10000);
    }
    private final Runnable saveTimeout = () -> {
            if (!isFinishing()) { status.setText("未收到保存确认；可返回，不保证本次进度已保存");
                status.setVisibility(View.VISIBLE); enter.setVisibility(View.VISIBLE);
                enter.setText("返回（保存未确认）"); enter.setOnClickListener(v -> confirmAbandon());
                confirmAbandon(); }
    };
    private void confirmAbandon() {
        new android.app.AlertDialog.Builder(this).setMessage("保存尚未确认。可以继续等待（启动遮罩可在游戏内重试保存），或放弃本次未保存进度返回；已有存档不会清理。")
            .setPositiveButton("继续等待", (dialog, which) -> {})
            .setNegativeButton("放弃并返回", (dialog, which) -> { if (boot != null) send(2, 0); finish(); }).show();
    }
    private void send(int what, int value) {
        if (boot == null) return;
        Message message = Message.obtain(null, what); message.arg1 = value; message.replyTo = incoming;
        try { boot.send(message); } catch (RemoteException error) { Log.w("GodotPrototype", "main game disconnected", error); }
    }
    private void call(String name) { call(name, new Class<?>[0]); }
    private void call(String name, Class<?>[] types, Object... args) {
        if (host == null) return;
        try { host.getClass().getMethod(name, types).invoke(host, args); }
        catch (Throwable error) { failure(error); }
    }
    @Override protected void onStart() { super.onStart(); started = true; call("start"); }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        call("resume"); send(3, 1);
    }
    @Override protected void onPause() { send(3, 0); resumed = false; call("pause"); super.onPause(); }
    @Override protected void onStop() { started = false; call("stop"); super.onStop(); }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); call("configuration", new Class<?>[]{Configuration.class}, config); }
    @Override public void onBackPressed() { requestLeave(); }
    @Override protected void onDestroy() {
        if (!ownsEngineProcess) { super.onDestroy(); return; }
        handler.removeCallbacksAndMessages(null);
        if (boot != null) try { boot.getBinder().unlinkToDeath(death, 0); } catch (java.util.NoSuchElementException ignored) { }
        call("destroy"); super.onDestroy();
        // Only :minigame owns this engine. Never retain/reinitialize native singletons in :game.
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
