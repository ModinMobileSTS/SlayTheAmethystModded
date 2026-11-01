package io.stamethyst.godot;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.util.Log;
import io.stamethyst.GodotBootSession;

/** One session-scoped Binder capability; no exported receiver, socket or global progress file. */
public final class GodotMinigameBoot implements GodotBootSession {
    private final Activity activity;
    private final Runnable enter;
    private final Messenger incoming;
    private Messenger client;
    private Bundle latest = new Bundle();
    private boolean closed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final IBinder.DeathRecipient death;

    public GodotMinigameBoot(Activity activity, Runnable enter) throws Exception {
        this.activity = activity; this.enter = enter;
        death = () -> handler.post(() -> {
            if (!closed) { Log.w("GodotPrototype", "minigame_process_died"); this.enter.run(); }
        });
        // Full hashing happens on the minigame worker, not during main-game Activity creation.
        if (io.stamethyst.backend.minigame.SlingBreakInstallation.read(activity) == null)
            throw new java.io.IOException("Download both components before enabling the minigame");
        incoming = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (closed) return true;
            if (message.what == 1 && message.replyTo != null) {
                if (client != null) try { client.getBinder().unlinkToDeath(death, 0); } catch (java.util.NoSuchElementException ignored) { }
                client = message.replyTo;
                try { client.getBinder().linkToDeath(death, 0); } catch (RemoteException error) { enter.run(); }
                send();
            }
            if (message.what == 2) { Log.i("GodotPrototype", "enter_main_game"); enter.run(); }
            if (message.what == 3) {
                Log.i("GodotPrototype", "minigame_lifecycle=" + message.arg1);
                ((io.stamethyst.StsGameActivity) activity).setGodotMinigameVisible(message.arg1 == 1);
            }
            return true;
        }));
        activity.getWindow().getDecorView().post(() -> {
            if (closed || activity.isFinishing()) return;
            try {
                activity.startActivity(new Intent(activity, GodotMinigameActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION).putExtra("boot", incoming));
            } catch (RuntimeException error) {
                Log.e("GodotPrototype", "minigame_activity_start_failed", error); this.enter.run();
            }
        });
        handler.postDelayed(() -> {
            if (!closed && client == null) {
                Log.w("GodotPrototype", "minigame_registration_timeout; restoring main-game loading UI");
                this.enter.run();
            }
        }, 30000);
    }
    @Override public void publish(int progress, String message, boolean ready) {
        if (closed) return;
        latest = new Bundle(); latest.putInt("progress", progress); latest.putString("message", message); latest.putBoolean("ready", ready);
        send();
    }
    private void send() {
        if (client == null || closed) return;
        Message state = Message.obtain(null, 10); state.setData(new Bundle(latest));
        try { client.send(state); } catch (RemoteException error) { Log.w("GodotPrototype", "minigame disconnected", error); client = null; }
    }
    @Override public void close() {
        ((io.stamethyst.StsGameActivity) activity).setGodotMinigameVisible(false);
        if (client != null) try { client.send(Message.obtain(null, 11)); } catch (RemoteException ignored) { }
        if (client != null) try { client.getBinder().unlinkToDeath(death, 0); } catch (java.util.NoSuchElementException ignored) { }
        closed = true; client = null;
        handler.removeCallbacksAndMessages(null);
    }
}
