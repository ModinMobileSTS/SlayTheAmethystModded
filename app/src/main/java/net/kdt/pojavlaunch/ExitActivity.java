package net.kdt.pojavlaunch;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import io.stamethyst.R;

public class ExitActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int code = getIntent().getIntExtra("code", -1);
        boolean isSignal = getIntent().getBooleanExtra("isSignal", false);
        int messageId = isSignal ? R.string.sts_signal_exit : R.string.sts_normal_exit;

        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(getString(messageId, code))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> finish())
                .setOnDismissListener(dialog -> finish())
                .show();
    }

    public static void showExitMessage(Context context, int code, boolean isSignal) {
        Intent intent = new Intent(context, ExitActivity.class);
        intent.putExtra("code", code);
        intent.putExtra("isSignal", isSignal);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }
}
