package io.stamethyst.godot;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;

/** adb-accessible debug-only entry; does not replace the production SlingBreak entry. */
public final class GodotPrototypeSetupActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT < 28 || !android.os.Process.is64Bit()) {
            TextView unsupported = new TextView(this);
            unsupported.setText("此原型要求 Android 9+、arm64 进程"); setContentView(unsupported); return;
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(24, 40, 24, 24);
        TextView status = new TextView(this);
        status.setText("Godot 双包原型\n先下载引擎与 PCK，再单独游玩或启用启动遮罩。仅 debug 可用。");
        layout.addView(status);
        Button download = new Button(this); download.setText("1. 下载 / 校验两个组件"); layout.addView(download);
        download.setOnClickListener(v -> {
            download.setEnabled(false);
            new Thread(() -> {
                try {
                    io.stamethyst.backend.update.UpdateCheckExecutionResult result = io.stamethyst.backend.minigame.SlingBreakService.INSTANCE.check(this);
                    if (!(result instanceof io.stamethyst.backend.update.UpdateCheckExecutionResult.Success)) throw new IllegalStateException(result.toString());
                    io.stamethyst.backend.minigame.SlingBreakService.INSTANCE.install(this,
                        ((io.stamethyst.backend.update.UpdateCheckExecutionResult.Success) result).getRelease(),
                        (percent, message) -> runOnUiThread(() -> status.setText(message)),
                        new io.stamethyst.backend.resources.ResourcePackDownloadMirrorSwitchController());
                }
                catch (Exception error) { runOnUiThread(() -> status.setText(android.util.Log.getStackTraceString(error))); }
                finally { runOnUiThread(() -> download.setEnabled(true)); }
            }, "godot-component-download").start();
        });
        Button play = new Button(this); play.setText("2. 单独运行 Godot PCK"); layout.addView(play);
        play.setOnClickListener(v -> startActivity(new Intent(this, GodotMinigameActivity.class)));
        Switch boot = new Switch(this); boot.setText("3. 下一次主游戏启动时使用 Godot 原型");
        boot.setChecked(getSharedPreferences("godot-prototype", 0).getBoolean("boot", false));
        boot.setOnCheckedChangeListener((button, checked) -> getSharedPreferences("godot-prototype", 0).edit().putBoolean("boot", checked).commit());
        layout.addView(boot);
        Button launcher = new Button(this); launcher.setText("返回启动器，正常启动主游戏"); layout.addView(launcher);
        launcher.setOnClickListener(v -> { startActivity(new Intent(this, io.stamethyst.LauncherActivity.class)); finish(); });
        setContentView(layout);
    }
}
