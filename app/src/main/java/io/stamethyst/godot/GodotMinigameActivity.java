package io.stamethyst.godot;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.AssetManager;
import android.os.*;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;
import io.stamethyst.R;
import io.stamethyst.backend.launch.LauncherReturnCoordinator;
import java.util.function.Consumer;

public final class GodotMinigameActivity extends Activity {
    private static final java.util.concurrent.atomic.AtomicBoolean ACTIVE = new java.util.concurrent.atomic.AtomicBoolean();
    private boolean ownsEngineProcess;
    // Do not release onPause/onStop: Godot may lazily read assets until process death.
    private java.io.Closeable componentLease;
    private Object host;
    private LegacySlingBreakMigration legacyMigration;
    private Context engineContext;
    private Messenger boot;
    private Messenger incoming;
    private boolean started, resumed;
    private boolean engineFailed;
    private final GodotLauncherState launcherState = new GodotLauncherState();
    private final GodotExitState exitState = new GodotExitState();
    private android.app.AlertDialog recoveryDialog;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final IBinder.DeathRecipient death = () -> handler.post(() -> {
        launcherState.disconnect();
        publishState();
        if (!exitState.completed() && !isFinishing() && !isDestroyed())
            Toast.makeText(this, launcherText(R.string.godot_main_game_disconnected), Toast.LENGTH_LONG).show();
    });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyFullscreen();
        ownsEngineProcess = ACTIVE.compareAndSet(false, true);
        if (!ownsEngineProcess) { finish(); return; }
        boot = getIntent().getParcelableExtra("boot");
        incoming = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (message.what == GodotBootProtocol.STATE) {
                Bundle data = message.getData();
                launcherState.update(data.getInt("progress"), data.getString("message", ""), data.getBoolean("ready"));
                publishState();
                Log.i("GodotPrototype", "progress=" + launcherState.progress() + " ready=" + launcherState.ready());
            }
            if (message.what == GodotBootProtocol.REQUEST_LEAVE && !exitState.completed())
                requestLeave(GodotExitState.Destination.LAUNCHER);
            return true;
        }));
        // Godot owns loading/progress/entry UI; Android owns the system document picker,
        // transient double-BACK hint and failure/unsaved-progress recovery dialogs.
        FrameLayout game = new FrameLayout(this); game.setBackgroundColor(0xffd5d9cc);
        setContentView(game);
        // Register before native initialization so early JNI crashes are visible via Binder death.
        if (boot != null) {
            try { boot.getBinder().linkToDeath(death, 0); send(GodotBootProtocol.REGISTER, 0); }
            catch (RemoteException error) { failure(error); }
        }
        legacyMigration = new LegacySlingBreakMigration(this, new java.io.File(super.getFilesDir(), "godot-prototype-user"));
        legacyMigration.start(() -> startEngine(game));
    }

    private void startEngine(FrameLayout game) {
        if (exitState.saving() || exitState.completed() || engineFailed || isFinishing() || isDestroyed()) return;
        new Thread(() -> {
            try {
                componentLease = io.stamethyst.backend.minigame.SlingBreakStorage.tryAcquireComponents(this);
                if (componentLease == null) throw new java.io.IOException(launcherText(R.string.godot_components_busy));
                io.stamethyst.backend.update.UpdateReleaseInfo release = io.stamethyst.backend.minigame.SlingBreakInstallation.read(this);
                if (release == null) throw new java.io.IOException("小游戏依赖未安装，请返回设置下载");
                GodotFiles files = new GodotFiles(this, release);
                ClassLoader loader = files.loader();
                Class<?> type = loader.loadClass("io.stamethyst.godotruntime.PrototypeHost");
                handler.post(() -> {
                    if (exitState.saving() || exitState.completed() || engineFailed || isFinishing() || isDestroyed()) return;
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
                        // Binder may have delivered the final ready state while verification was
                        // still running. Replay it even if the main game sends no further updates.
                        publishState();
                        if (started) call("start"); if (resumed) call("resume");
                        if (boot != null) {
                            send(GodotBootProtocol.REGISTER, 0);
                            send(GodotBootProtocol.VISIBILITY, resumed ? 1 : 0);
                        }
                    } catch (Throwable error) { failure(error); }
                });
            } catch (Throwable error) { handler.post(() -> failure(error)); }
        }, "godot-component-verify").start();
    }
    private void event(String event) {
        Log.i("GodotPrototype", event);
        if ("enter_requested".equals(event) && boot != null && launcherState.canEnter() && !engineFailed
                && !exitState.returnsToLauncher()) requestLeave(GodotExitState.Destination.MAIN_GAME);
        else if ("save_retry_requested".equals(event) && !engineFailed && exitState.retrySave()) {
            dismissRecoveryDialog();
            armSaveTimeout();
        }
        else if ("launcher_ui_ready".equals(event) && boot != null) {
            launcherState.attachUi();
            // Keep the protocol gate even though there is no native launcher chrome.
            publishState();
        }
        else if ("saved".equals(event) && !engineFailed && exitState.acknowledgeSave()) {
            // Both standalone and boot sessions wait for the requested save to finish.
            // A stray/unsolicited event must not close a session or enter the main game.
            Log.i("GodotPrototype", "save_acknowledged destination=" + (exitState.returnsToLauncher() ? "launcher" : "main_game")
                + " ready=" + launcherState.ready());
            handler.removeCallbacks(saveTimeout);
            finishLeave();
        }
        else if ("save_failed".equals(event) && exitState.failSave()) {
            handler.removeCallbacks(saveTimeout);
            if (exitState.returnsToLauncher() || !launcherState.uiReady() || launcherState.disconnected()) confirmAbandon();
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
        if (engineFailed || exitState.completed() || isFinishing() || isDestroyed()) return;
        engineFailed = true;
        Log.e("GodotPrototype", "engine_failure", error);
        handler.removeCallbacks(saveTimeout);
        dismissRecoveryDialog();
        showEngineFailure();
    }
    private void showEngineFailure() {
        if (exitState.completed() || isFinishing() || isDestroyed()
                || (recoveryDialog != null && recoveryDialog.isShowing())) return;
        recoveryDialog = new android.app.AlertDialog.Builder(this)
            .setMessage(launcherText(R.string.godot_engine_failed_return))
            .setCancelable(false)
            .setPositiveButton(launcherText(R.string.godot_return_to_launcher), (dialog, which) -> {
                exitState.selectLauncher();
                send(GodotBootProtocol.CANCEL_MAIN_GAME, 0);
                if (exitState.complete()) finishLeave();
            }).show();
    }
    private void requestLeave(GodotExitState.Destination destination) {
        if (exitState.completed()) return;
        if (destination == GodotExitState.Destination.LAUNCHER) {
            exitState.selectLauncher();
            // Disarm late entry/Binder-death recovery BEFORE asking Godot to save.
            send(GodotBootProtocol.CANCEL_MAIN_GAME, 0);
        }
        if (engineFailed) {
            showEngineFailure();
            return;
        }
        if (exitState.saveFailed()) { confirmAbandon(); return; }
        if (!exitState.beginSave(destination)) return;
        Log.i("GodotPrototype", "save_requested mode=" + (boot == null ? "standalone" : "boot")
            + " destination=" + (exitState.returnsToLauncher() ? "launcher" : "main_game"));
        if (host == null) { exitState.complete(); finishLeave(); return; }
        call("leave");
        armSaveTimeout();
    }
    private void armSaveTimeout() {
        handler.removeCallbacks(saveTimeout);
        handler.postDelayed(saveTimeout, 10000);
    }
    private final Runnable saveTimeout = () -> {
        if (!exitState.completed() && !isFinishing() && !isDestroyed()) confirmAbandon();
    };
    private void confirmAbandon() {
        if (exitState.completed() || isFinishing() || isDestroyed() || (recoveryDialog != null && recoveryDialog.isShowing())) return;
        recoveryDialog = new android.app.AlertDialog.Builder(this).setMessage(launcherText(R.string.godot_save_unconfirmed))
            .setPositiveButton(launcherText(R.string.godot_keep_waiting), (dialog, which) -> {
                if (exitState.saving()) armSaveTimeout();
            })
            .setNegativeButton(launcherText(R.string.godot_discard_and_return), (dialog, which) -> {
                if (exitState.complete()) finishLeave();
            }).show();
    }
    private void dismissRecoveryDialog() {
        if (recoveryDialog != null) { recoveryDialog.dismiss(); recoveryDialog = null; }
    }
    private String launcherText(int resource) {
        // getResources() switches to the isolated engine APK after attach; launcher R IDs
        // must never be resolved against that unrelated resource table.
        return super.getResources().getString(resource);
    }
    private void finishLeave() {
        handler.removeCallbacks(saveTimeout);
        dismissRecoveryDialog();
        if (exitState.returnsToLauncher()) {
            if (send(GodotBootProtocol.RETURN_TO_LAUNCHER, 0)) {
                // Keep Godot covering the main game until the host brings up the launcher.
                handler.postDelayed(this::returnToLauncher, 1500);
            } else returnToLauncher();
        } else {
            send(GodotBootProtocol.ENTER_MAIN_GAME, 0);
            finish();
        }
    }
    private void returnToLauncher() {
        if (isFinishing() || isDestroyed()) return;
        LauncherReturnCoordinator.returnToLauncher(this);
    }
    private boolean send(int what, int value) {
        if (boot == null) return false;
        Message message = Message.obtain(null, what); message.arg1 = value; message.replyTo = incoming;
        try { boot.send(message); return true; }
        catch (RemoteException error) { Log.w("GodotPrototype", "main game disconnected", error); return false; }
    }
    private void call(String name) { call(name, new Class<?>[0]); }
    private void publishState() {
        call("state", new Class<?>[]{int.class, String.class, boolean.class},
            launcherState.progress(), launcherState.message(), launcherState.ready());
    }
    private void call(String name, Class<?>[] types, Object... args) {
        if (host == null) return;
        try { host.getClass().getMethod(name, types).invoke(host, args); }
        catch (Throwable error) { failure(error); }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (host == null || engineFailed || exitState.completed() || isFinishing() || isDestroyed()) return;
        try {
            // getGodot is already part of the downloaded host's GodotHost API. Use the
            // official dispatcher: it delivers SAF URIs AND cancellation on the render
            // thread. Keep the Intent intact, including its URI grants and ClipData.
            Object godot = host.getClass().getMethod("getGodot").invoke(host);
            godot.getClass().getMethod("onActivityResult", int.class, int.class, Intent.class)
                .invoke(godot, requestCode, resultCode, data);
        } catch (Throwable error) { failure(error); }
    }
    @Override protected void onStart() { super.onStart(); started = true; call("start"); }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        applyFullscreen();
        call("resume"); send(GodotBootProtocol.VISIBILITY, 1);
    }
    private void applyFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.view.WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attributes);
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(android.view.WindowInsets.Type.systemBars());
            }
        }
    }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) applyFullscreen(); }
    @Override protected void onPause() { exitState.resetBack(); send(GodotBootProtocol.VISIBILITY, 0); resumed = false; call("pause"); super.onPause(); }
    @Override protected void onStop() { started = false; call("stop"); super.onStop(); }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); applyFullscreen(); call("configuration", new Class<?>[]{Configuration.class}, config); }
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        // Godot's focused render view otherwise consumes BACK and quits its SceneTree,
        // bypassing Activity.onBackPressed and the save acknowledgement entirely.
        if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP && !event.isCanceled()) handleBack();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private void handleBack() {
        if (exitState.completed() || (exitState.saving() && exitState.returnsToLauncher())) return;
        if (exitState.pressBack(SystemClock.elapsedRealtime())) requestLeave(GodotExitState.Destination.LAUNCHER);
        else Toast.makeText(this, launcherText(R.string.godot_back_exit_confirmation), Toast.LENGTH_SHORT).show();
    }
    @Override public void onBackPressed() { handleBack(); }
    @Override protected void onDestroy() {
        if (!ownsEngineProcess) { super.onDestroy(); return; }
        if (legacyMigration != null) legacyMigration.close();
        dismissRecoveryDialog();
        handler.removeCallbacksAndMessages(null);
        if (boot != null) try { boot.getBinder().unlinkToDeath(death, 0); } catch (java.util.NoSuchElementException ignored) { }
        call("destroy"); super.onDestroy();
        // Only :minigame owns this engine. Never retain/reinitialize native singletons in :game.
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
