package io.stamethyst.godot;

import android.app.*;
import android.content.*;
import android.os.*;
import android.util.Log;
import io.stamethyst.R;
import java.io.File;
import java.util.*;
import org.json.JSONObject;

/** Startup gate shared by standalone play and the main-game loading overlay. */
final class LegacySlingBreakMigration implements AutoCloseable {
    private final Activity activity;
    private final LegacySlingBreakMigrationStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> saves = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private ServiceConnection connection;
    private Runnable readTimeout;
    private Runnable continuation;
    private AlertDialog dialog;
    private boolean closed;
    private int selected;

    LegacySlingBreakMigration(Activity activity, File userDirectory) {
        this.activity = activity;
        store = new LegacySlingBreakMigrationStore(userDirectory);
    }
    void start(Runnable continuation) {
        this.continuation = continuation;
        if (store.completed()) { proceed(); return; }
        readProfile(false);
    }
    private boolean active() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }

    private void readProfile(boolean game) {
        if (!active()) return;
        // Do not initialize WebView (or another process) for a profile that never existed.
        File profile = new File(activity.getApplicationInfo().dataDir, game ? "app_webview_game" : "app_webview");
        if (!profile.isDirectory()) { next(game); return; }
        Class<?> service = game ? LegacySlingBreakSaveService.GameProcess.class : LegacySlingBreakSaveService.class;
        ServiceConnection request = new ServiceConnection() {
            private boolean finished;
            private void complete(String raw, String error) {
                if (finished || connection != this) return;
                finished = true;
                unbind();
                if (!active()) return;
                if (error != null) Log.w("SlingBreakMigration", "Legacy profile read failed: " + error);
                if (raw != null && !saves.contains(raw)) {
                    saves.add(raw);
                    int level = 0;
                    try { level = new JSONObject(raw).optInt("level", 0); } catch (Exception ignored) { }
                    labels.add(activity.getString(game ? R.string.sling_migration_source_boot : R.string.sling_migration_source_standalone, level));
                }
                next(game);
            }
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (finished || connection != this || !active()) return;
                Messenger response = new Messenger(new Handler(Looper.getMainLooper(), message -> {
                    complete(message.getData().getString("save"), message.getData().getString("error")); return true;
                }));
                Message request = Message.obtain(null, 1); request.replyTo = response;
                try { new Messenger(binder).send(request); }
                catch (RemoteException error) { complete(null, "disconnected"); }
            }
            @Override public void onServiceDisconnected(ComponentName name) { complete(null, "disconnected"); }
            @Override public void onNullBinding(ComponentName name) { complete(null, "null_binding"); }
            @Override public void onBindingDied(ComponentName name) { complete(null, "binding_died"); }
        };
        connection = request;
        readTimeout = () -> {
            if (connection != request) return;
            unbind();
            Log.w("SlingBreakMigration", "Legacy profile read timed out; will retry next launch");
            next(game);
        };
        handler.postDelayed(readTimeout, 12000);
        try {
            if (!activity.bindService(new Intent(activity, service), request, Context.BIND_AUTO_CREATE)) {
                unbind(); next(game);
            }
        } catch (RuntimeException error) {
            Log.w("SlingBreakMigration", "Cannot bind legacy reader", error);
            unbind(); next(game);
        }
    }
    private void next(boolean game) { if (game) readGecko(); else readProfile(true); }
    private void readGecko() {
        if (!active()) return;
        File files = new File(activity.getApplicationInfo().dataDir, "files");
        new Thread(() -> {
            List<String> old = LegacyGeckoSlingBreakSave.read(files);
            handler.post(() -> {
                if (!active()) return;
                for (String raw : old) if (!saves.contains(raw)) {
                    saves.add(raw);
                    int level = 0;
                    try { level = new JSONObject(raw).optInt("level", 0); } catch (Exception ignored) { }
                    labels.add(activity.getString(R.string.sling_migration_source_gecko, level));
                }
                prompt();
            });
        }, "slingbreak-legacy-gecko-read").start();
    }
    private void unbind() {
        if (readTimeout != null) handler.removeCallbacks(readTimeout);
        if (connection != null) {
            try { activity.unbindService(connection); } catch (IllegalArgumentException ignored) { }
            connection = null;
        }
    }
    private void prompt() {
        if (!active()) return;
        if (saves.isEmpty()) { proceed(); return; }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle(R.string.sling_migration_title)
            .setPositiveButton(R.string.sling_migration_confirm, (d, which) -> migrate())
            .setNegativeButton(R.string.sling_migration_later, (d, which) -> proceed())
            .setOnCancelListener(d -> proceed());
        String message = activity.getString(store.progress().exists() ? R.string.sling_migration_replace : R.string.sling_migration_message);
        if (saves.size() > 1) {
            // AlertDialog does not display message and list together. Put the warning in the list header.
            android.widget.TextView header = new android.widget.TextView(activity);
            header.setText(activity.getString(R.string.sling_migration_title) + "\n\n" + message);
            int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
            header.setPadding(padding, padding, padding, padding / 2);
            builder.setCustomTitle(header);
            builder.setSingleChoiceItems(labels.toArray(new String[0]), selected, (d, which) -> selected = which);
        } else builder.setMessage(message + "\n\n" + labels.get(0));
        dialog = builder.show();
    }
    private void migrate() {
        if (!active()) return;
        dialog = new AlertDialog.Builder(activity).setMessage(R.string.sling_migration_working).setCancelable(false).show();
        String raw = saves.get(selected);
        new Thread(() -> {
            Exception failure = null;
            try { store.migrate(raw); } catch (Exception error) { failure = error; }
            Exception result = failure;
            handler.post(() -> {
                if (!active()) return;
                dialog.dismiss();
                if (result == null) {
                    android.widget.Toast.makeText(activity, R.string.sling_migration_success, android.widget.Toast.LENGTH_SHORT).show();
                    proceed();
                } else {
                    Log.w("SlingBreakMigration", "Migration failed; old save retained", result);
                    dialog = new AlertDialog.Builder(activity).setTitle(R.string.sling_migration_title)
                        .setMessage(R.string.sling_migration_failed)
                        .setPositiveButton(R.string.sling_migration_retry, (d, which) -> prompt())
                        .setNegativeButton(R.string.sling_migration_later, (d, which) -> proceed())
                        .setOnCancelListener(d -> proceed()).show();
                }
            });
        }, "slingbreak-save-migration").start();
    }
    private void proceed() {
        if (!active() || continuation == null) return;
        Runnable next = continuation; continuation = null; next.run();
    }
    @Override public void close() {
        closed = true; continuation = null; unbind(); handler.removeCallbacksAndMessages(null);
        if (dialog != null) { dialog.setOnCancelListener(null); dialog.dismiss(); }
    }
}
