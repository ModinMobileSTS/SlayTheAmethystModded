package io.stamethyst;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import net.kdt.pojavlaunch.MainActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class LauncherActivity extends AppCompatActivity {
    private static final String TAG = "LauncherActivity";
    public static final String EXTRA_DEBUG_LAUNCH_MODE = "io.stamethyst.debug_launch_mode";
    public static final String EXTRA_CRASH_OCCURRED = "io.stamethyst.crash_occurred";
    public static final String EXTRA_CRASH_CODE = "io.stamethyst.crash_code";
    public static final String EXTRA_CRASH_IS_SIGNAL = "io.stamethyst.crash_is_signal";
    public static final String EXTRA_CRASH_DETAIL = "io.stamethyst.crash_detail";
    private static final float DEFAULT_RENDER_SCALE = 0.75f;
    private static final float MIN_RENDER_SCALE = 0.50f;
    private static final float MAX_RENDER_SCALE = 1.00f;

    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();

    private TextView statusText;
    private TextView logPathText;
    private Button importButton;
    private Button importModsButton;
    private Button importSavesButton;
    private Button exportDebugButton;
    private LinearLayout modsContainer;
    private EditText renderScaleInput;
    private Button saveRenderScaleButton;
    private Spinner rendererBackendSpinner;
    private Button launchButton;

    private final ActivityResultLauncher<String[]> importJarLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onJarPicked);
    private final ActivityResultLauncher<String[]> importModsLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), this::onModJarsPicked);
    private final ActivityResultLauncher<String[]> importSavesLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onSavesArchivePicked);
    private final ActivityResultLauncher<String> exportDebugLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), this::onDebugExportPicked);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MainActivity.init(getApplicationContext());
        setContentView(R.layout.activity_launcher);

        statusText = findViewById(R.id.statusText);
        logPathText = findViewById(R.id.logPathText);
        importButton = findViewById(R.id.importButton);
        importModsButton = findViewById(R.id.importModsButton);
        importSavesButton = findViewById(R.id.importSavesButton);
        exportDebugButton = findViewById(R.id.exportDebugButton);
        modsContainer = findViewById(R.id.modsContainer);
        renderScaleInput = findViewById(R.id.renderScaleInput);
        saveRenderScaleButton = findViewById(R.id.saveRenderScaleButton);
        rendererBackendSpinner = findViewById(R.id.rendererBackendSpinner);
        launchButton = findViewById(R.id.launchButton);

        logPathText.setText(
                "Log: " + RuntimePaths.latestLog(this).getAbsolutePath()
                        + "\nVM: " + new File(RuntimePaths.stsRoot(this), "jvm_output.log").getAbsolutePath()
                        + "\nCrash: " + new File(RuntimePaths.stsRoot(this), "hs_err_pid*.log").getAbsolutePath()
        );

        importButton.setOnClickListener(v -> importJarLauncher.launch(new String[]{"application/java-archive", "application/octet-stream", "*/*"}));
        importModsButton.setOnClickListener(v -> importModsLauncher.launch(new String[]{"application/java-archive", "application/octet-stream", "*/*"}));
        importSavesButton.setOnClickListener(v -> importSavesLauncher.launch(new String[]{"application/zip", "application/x-zip-compressed", "*/*"}));
        exportDebugButton.setOnClickListener(v -> exportDebugLauncher.launch(buildDebugExportFileName()));
        saveRenderScaleButton.setOnClickListener(v -> saveRenderScaleFromInput(true));

        launchButton.setOnClickListener(v -> {
            if (!saveRenderScaleFromInput(false)) {
                return;
            }
            if (!saveRendererSelection()) {
                return;
            }
            prepareAndLaunch(StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD);
        });

        setupRendererSpinner();
        loadRenderScaleInput();
        refreshStatus();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(@Nullable Intent intent) {
        if (intent == null) {
            return;
        }
        boolean showedCrashDialog = maybeShowCrashDialog(intent);
        if (!showedCrashDialog) {
            maybeLaunchFromDebugExtra(intent);
        }
    }

    private boolean maybeShowCrashDialog(Intent intent) {
        if (!intent.getBooleanExtra(EXTRA_CRASH_OCCURRED, false)) {
            return false;
        }

        int code = intent.getIntExtra(EXTRA_CRASH_CODE, -1);
        boolean isSignal = intent.getBooleanExtra(EXTRA_CRASH_IS_SIGNAL, false);
        String detail = intent.getStringExtra(EXTRA_CRASH_DETAIL);
        String message;
        if (detail != null && !detail.trim().isEmpty()) {
            message = getString(R.string.sts_crash_detail_format, detail.trim());
        } else {
            int messageId = isSignal ? R.string.sts_signal_exit : R.string.sts_normal_exit;
            message = getString(messageId, code);
        }

        intent.removeExtra(EXTRA_CRASH_OCCURRED);
        intent.removeExtra(EXTRA_CRASH_CODE);
        intent.removeExtra(EXTRA_CRASH_IS_SIGNAL);
        intent.removeExtra(EXTRA_CRASH_DETAIL);

        new AlertDialog.Builder(this)
                .setTitle(R.string.sts_crash_dialog_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        return true;
    }

    private void maybeLaunchFromDebugExtra(Intent intent) {
        if (intent == null) {
            return;
        }
        String debugLaunchMode = intent.getStringExtra(EXTRA_DEBUG_LAUNCH_MODE);
        Log.i(TAG, "Debug launch extra: " + debugLaunchMode);
        if (StsLaunchSpec.LAUNCH_MODE_VANILLA.equals(debugLaunchMode)
                || StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD.equals(debugLaunchMode)) {
            if (!saveRenderScaleFromInput(false)) {
                return;
            }
            if (!saveRendererSelection()) {
                return;
            }
            Log.i(TAG, "Auto launching mode from debug extra: " + debugLaunchMode);
            prepareAndLaunch(debugLaunchMode);
        }
    }

    private void onJarPicked(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        setBusy(true, "Importing desktop-1.0.jar...");
        executor.execute(() -> {
            try {
                copyUriToFile(uri, RuntimePaths.importedStsJar(this));
                StsJarValidator.validate(RuntimePaths.importedStsJar(this));
                runOnUiThread(() -> {
                    Toast.makeText(this, "Imported desktop-1.0.jar", Toast.LENGTH_SHORT).show();
                    refreshStatus();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Import failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    refreshStatus();
                });
            }
        });
    }

    private void onModJarsPicked(@Nullable List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        setBusy(true, "Importing selected mod jars...");
        executor.execute(() -> {
            int imported = 0;
            List<String> errors = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    String modId = importModJar(uri);
                    if (!ModManager.isRequiredModId(modId)) {
                        ModManager.setOptionalModEnabled(this, modId, true);
                    }
                    imported++;
                } catch (Throwable error) {
                    String name = resolveDisplayName(uri);
                    errors.add(name + ": " + error.getMessage());
                }
            }
            int importedCount = imported;
            int failedCount = errors.size();
            String firstError = failedCount > 0 ? errors.get(0) : null;
            runOnUiThread(() -> {
                if (importedCount > 0 && failedCount == 0) {
                    Toast.makeText(this, "Imported " + importedCount + " mod jar(s)", Toast.LENGTH_SHORT).show();
                } else if (importedCount > 0) {
                    Toast.makeText(this, "Imported " + importedCount + ", failed " + failedCount + " (" + firstError + ")", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Mod import failed: " + firstError, Toast.LENGTH_LONG).show();
                }
                refreshStatus();
            });
        });
    }

    private String importModJar(Uri uri) throws IOException {
        File modsDir = RuntimePaths.modsDir(this);
        if (!modsDir.exists() && !modsDir.mkdirs()) {
            throw new IOException("Failed to create mods directory");
        }

        File tempFile = new File(modsDir, ".import-" + System.nanoTime() + ".tmp.jar");
        copyUriToFile(uri, tempFile);
        try {
            String modId = ModManager.normalizeModId(ModJarSupport.resolveModId(tempFile));
            if (modId.isEmpty()) {
                throw new IOException("modid is empty");
            }
            if (ModManager.MOD_ID_BASEMOD.equals(modId)) {
                ModJarSupport.validateBaseModJar(tempFile);
            } else if (ModManager.MOD_ID_STSLIB.equals(modId)) {
                ModJarSupport.validateStsLibJar(tempFile);
            }
            File targetFile = ModManager.resolveStorageFileForModId(this, modId);
            moveFileReplacing(tempFile, targetFile);
            return modId;
        } finally {
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private void moveFileReplacing(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Failed to replace existing file: " + target.getAbsolutePath());
        }
        if (source.renameTo(target)) {
            return;
        }
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
        if (!source.delete()) {
            throw new IOException("Failed to clean temp file: " + source.getAbsolutePath());
        }
    }

    private String resolveDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME},
                    null,
                    null,
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return "unknown.jar";
    }

    private void prepareAndLaunch(String launchMode) {
        RendererBackend renderer = selectedRendererFromUi();
        Intent intent = new Intent(this, LaunchLoadingActivity.class);
        intent.putExtra(LaunchLoadingActivity.EXTRA_LAUNCH_MODE, launchMode);
        intent.putExtra(LaunchLoadingActivity.EXTRA_RENDERER_BACKEND, renderer.rendererId());
        Log.i(TAG, "Forward launch to LaunchLoadingActivity, mode=" + launchMode + ", renderer=" + renderer.rendererId());
        startActivity(intent);
    }

    private void onSavesArchivePicked(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        setBusy(true, "Importing save archive...");
        executor.execute(() -> {
            try {
                int importedCount = importSaveArchive(uri);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Imported " + importedCount + " save files", Toast.LENGTH_SHORT).show();
                    refreshStatus();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Save import failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    refreshStatus();
                });
            }
        });
    }

    private void onDebugExportPicked(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        setBusy(true, "Exporting debug bundle...");
        executor.execute(() -> {
            try {
                int exportedCount = exportDebugBundle(uri);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Debug bundle exported (" + exportedCount + " files)", Toast.LENGTH_LONG).show();
                    refreshStatus();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Debug export failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    refreshStatus();
                });
            }
        });
    }

    private String buildDebugExportFileName() {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        return "sts-debug-" + formatter.format(new Date()) + ".zip";
    }

    private int exportDebugBundle(Uri uri) throws IOException {
        File stsRoot = RuntimePaths.stsRoot(this);
        List<File> debugFiles = new ArrayList<>();
        addDebugFileIfExists(debugFiles, RuntimePaths.latestLog(this));
        addDebugFileIfExists(debugFiles, new File(stsRoot, "jvm_output.log"));
        addDebugFileIfExists(debugFiles, RuntimePaths.enabledModsConfig(this));

        File[] hsErrFiles = stsRoot.listFiles((dir, name) ->
                name != null && name.startsWith("hs_err_pid") && name.endsWith(".log"));
        if (hsErrFiles != null && hsErrFiles.length > 0) {
            Arrays.sort(hsErrFiles, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File hsErrFile : hsErrFiles) {
                addDebugFileIfExists(debugFiles, hsErrFile);
            }
        }

        try (OutputStream output = getContentResolver().openOutputStream(uri);
             ZipOutputStream zipOutput = output == null ? null : new ZipOutputStream(output)) {
            if (zipOutput == null) {
                throw new IOException("Unable to open destination file");
            }
            int exportedCount = 0;
            for (File file : debugFiles) {
                writeFileToZip(zipOutput, stsRoot, file);
                exportedCount++;
            }
            if (exportedCount <= 0) {
                ZipEntry entry = new ZipEntry("sts/README.txt");
                zipOutput.putNextEntry(entry);
                String message = "No debug log files found yet.\n"
                        + "Expected paths under: " + stsRoot.getAbsolutePath() + "\n"
                        + "Files: latestlog.txt, jvm_output.log, hs_err_pid*.log\n";
                zipOutput.write(message.getBytes(StandardCharsets.UTF_8));
                zipOutput.closeEntry();
            }
            return exportedCount;
        }
    }

    private void writeFileToZip(ZipOutputStream zipOutput, File stsRoot, File sourceFile) throws IOException {
        String entryName = buildDebugEntryName(stsRoot, sourceFile);
        ZipEntry entry = new ZipEntry(entryName);
        if (sourceFile.lastModified() > 0) {
            entry.setTime(sourceFile.lastModified());
        }
        zipOutput.putNextEntry(entry);
        try (FileInputStream input = new FileInputStream(sourceFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                zipOutput.write(buffer, 0, read);
            }
        }
        zipOutput.closeEntry();
    }

    private String buildDebugEntryName(File stsRoot, File sourceFile) throws IOException {
        String rootPath = stsRoot.getCanonicalPath();
        String filePath = sourceFile.getCanonicalPath();
        String relativePath;
        if (filePath.startsWith(rootPath + File.separator)) {
            relativePath = filePath.substring(rootPath.length() + 1);
        } else {
            relativePath = sourceFile.getName();
        }
        return "sts/" + relativePath.replace('\\', '/');
    }

    private void addDebugFileIfExists(List<File> files, File file) {
        if (file != null && file.isFile() && file.length() > 0) {
            files.add(file);
        }
    }

    private void refreshStatus() {
        boolean hasJar = RuntimePaths.importedStsJar(this).exists();
        boolean hasMts = RuntimePaths.importedMtsJar(this).exists() || hasBundledAsset("components/mods/ModTheSpire.jar");
        boolean hasBaseMod = RuntimePaths.importedBaseModJar(this).exists() || hasBundledAsset("components/mods/BaseMod.jar");
        boolean hasStsLib = RuntimePaths.importedStsLibJar(this).exists() || hasBundledAsset("components/mods/StSLib.jar");
        float renderScale = readRenderScaleValue();
        RendererBackend selectedRenderer = rendererBackendSpinner != null
                ? selectedRendererFromUi()
                : RendererConfig.readPreferredBackend(this);
        RendererConfig.ResolutionResult rendererDecision =
                RendererConfig.resolveEffectiveBackend(this, selectedRenderer);
        String rendererSelectedLine =
                getString(R.string.renderer_selected_format, selectedRenderer.statusLabel());
        String rendererEffectiveLine = rendererDecision.isFallback()
                ? getString(
                R.string.renderer_effective_reason_format,
                rendererDecision.effective.statusLabel(),
                rendererDecision.reason
        )
                : getString(
                R.string.renderer_effective_format,
                rendererDecision.effective.statusLabel()
        );

        List<ModManager.InstalledMod> mods = ModManager.listInstalledMods(this);
        int optionalTotal = 0;
        int optionalEnabled = 0;
        for (ModManager.InstalledMod mod : mods) {
            if (mod.required) {
                continue;
            }
            optionalTotal++;
            if (mod.enabled) {
                optionalEnabled++;
            }
        }
        updateModsChecklist(mods);

        String status = (hasJar ? "desktop-1.0.jar: OK" : "desktop-1.0.jar: missing")
                + "\nModTheSpire.jar: " + (hasMts ? "OK (bundled)" : "missing")
                + "\nBaseMod.jar: " + (hasBaseMod ? "OK (required)" : "missing (required)")
                + "\nStSLib.jar: " + (hasStsLib ? "OK (required, bundled)" : "missing (required)")
                + "\nOptional mods enabled: " + optionalEnabled + "/" + optionalTotal
                + "\nRender scale: " + String.format(Locale.US, "%.2f", renderScale) + " (0.50-1.00)"
                + "\n" + rendererSelectedLine
                + "\n" + rendererEffectiveLine
                + "\nRuntime pack expected at build time: runtime-pack/jre8-pojav.zip";
        setBusy(false, null);
        statusText.setText(status);
    }

    private void updateModsChecklist(List<ModManager.InstalledMod> mods) {
        modsContainer.removeAllViews();
        boolean hasOptional = false;

        for (ModManager.InstalledMod mod : mods) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(mod.displayName);
            checkBox.setChecked(mod.enabled);
            checkBox.setTag(Boolean.valueOf(mod.required));
            if (mod.required || !mod.installed) {
                checkBox.setEnabled(false);
            } else {
                hasOptional = true;
                String modId = mod.modId;
                checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    try {
                        ModManager.setOptionalModEnabled(this, modId, isChecked);
                    } catch (Throwable error) {
                        Toast.makeText(this, "Failed to update mod selection: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    refreshStatus();
                });
            }
            modsContainer.addView(checkBox);
        }

        if (!hasOptional) {
            TextView emptyText = new TextView(this);
            emptyText.setText("No optional mods installed yet");
            modsContainer.addView(emptyText);
        }
    }

    private boolean hasBundledAsset(String assetPath) {
        try (InputStream ignored = getAssets().open(assetPath)) {
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private void setBusy(boolean busy, @Nullable String message) {
        importButton.setEnabled(!busy);
        importModsButton.setEnabled(!busy);
        importSavesButton.setEnabled(!busy);
        exportDebugButton.setEnabled(!busy);
        renderScaleInput.setEnabled(!busy);
        saveRenderScaleButton.setEnabled(!busy);
        rendererBackendSpinner.setEnabled(!busy);
        launchButton.setEnabled(!busy);
        for (int i = 0; i < modsContainer.getChildCount(); i++) {
            View child = modsContainer.getChildAt(i);
            if (!(child instanceof CheckBox)) {
                continue;
            }
            Object tag = child.getTag();
            boolean required = tag instanceof Boolean && ((Boolean) tag);
            child.setEnabled(!busy && !required);
        }
        if (busy && message != null) {
            statusText.setText(message);
        }
    }

    private void loadRenderScaleInput() {
        renderScaleInput.setText(String.format(Locale.US, "%.2f", readRenderScaleValue()));
    }

    private void setupRendererSpinner() {
        List<String> labels = Arrays.asList(
                RendererBackend.OPENGL_ES2.selectorLabel(),
                RendererBackend.KOPPER_ZINK.selectorLabel()
        );
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                labels
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        rendererBackendSpinner.setAdapter(adapter);

        RendererBackend preferred = RendererConfig.readPreferredBackend(this);
        rendererBackendSpinner.setSelection(rendererToSpinnerPosition(preferred), false);
        rendererBackendSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refreshStatus();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private int rendererToSpinnerPosition(RendererBackend backend) {
        return backend == RendererBackend.KOPPER_ZINK ? 1 : 0;
    }

    private RendererBackend selectedRendererFromUi() {
        return rendererBackendSpinner.getSelectedItemPosition() == 1
                ? RendererBackend.KOPPER_ZINK
                : RendererBackend.OPENGL_ES2;
    }

    private boolean saveRendererSelection() {
        RendererBackend selected = selectedRendererFromUi();
        try {
            RendererConfig.writePreferredBackend(this, selected);
            return true;
        } catch (IOException error) {
            Toast.makeText(
                    this,
                    getString(R.string.renderer_save_failed, String.valueOf(error.getMessage())),
                    Toast.LENGTH_SHORT
            ).show();
            return false;
        }
    }

    private float readRenderScaleValue() {
        File config = renderScaleConfigFile();
        if (!config.exists()) {
            return DEFAULT_RENDER_SCALE;
        }
        try (InputStream input = new FileInputStream(config)) {
            byte[] bytes = new byte[(int) Math.min(config.length(), 64)];
            int read = input.read(bytes);
            if (read <= 0) {
                return DEFAULT_RENDER_SCALE;
            }
            String value = new String(bytes, 0, read, StandardCharsets.UTF_8).trim().replace(',', '.');
            if (value.isEmpty()) {
                return DEFAULT_RENDER_SCALE;
            }
            float parsed = Float.parseFloat(value);
            if (parsed < MIN_RENDER_SCALE) {
                return MIN_RENDER_SCALE;
            }
            if (parsed > MAX_RENDER_SCALE) {
                return MAX_RENDER_SCALE;
            }
            return parsed;
        } catch (Throwable ignored) {
            return DEFAULT_RENDER_SCALE;
        }
    }

    private boolean saveRenderScaleFromInput(boolean showToast) {
        String input = renderScaleInput.getText() == null
                ? ""
                : renderScaleInput.getText().toString().trim().replace(',', '.');

        if (input.isEmpty()) {
            File config = renderScaleConfigFile();
            if (config.exists() && !config.delete()) {
                Toast.makeText(this, "Failed to reset render scale", Toast.LENGTH_SHORT).show();
                return false;
            }
            renderScaleInput.setText(String.format(Locale.US, "%.2f", DEFAULT_RENDER_SCALE));
            if (showToast) {
                Toast.makeText(this, "Render scale reset to default 0.75", Toast.LENGTH_SHORT).show();
            }
            refreshStatus();
            return true;
        }

        final float parsed;
        try {
            parsed = Float.parseFloat(input);
        } catch (NumberFormatException error) {
            Toast.makeText(this, "Invalid render scale, use 0.50 to 1.00", Toast.LENGTH_SHORT).show();
            return false;
        }

        if (parsed < MIN_RENDER_SCALE || parsed > MAX_RENDER_SCALE) {
            Toast.makeText(this, "Render scale must be between 0.50 and 1.00", Toast.LENGTH_SHORT).show();
            return false;
        }

        File config = renderScaleConfigFile();
        File parent = config.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Toast.makeText(this, "Failed to create config directory", Toast.LENGTH_SHORT).show();
            return false;
        }

        String normalized = String.format(Locale.US, "%.2f", parsed);
        try (FileOutputStream out = new FileOutputStream(config, false)) {
            out.write(normalized.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            Toast.makeText(this, "Failed to save render scale: " + error.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }

        renderScaleInput.setText(normalized);
        if (showToast) {
            Toast.makeText(this, "Render scale saved: " + normalized, Toast.LENGTH_SHORT).show();
        }
        refreshStatus();
        return true;
    }

    private File renderScaleConfigFile() {
        return new File(RuntimePaths.stsRoot(this), "render_scale.txt");
    }

    private void appendRendererDecisionLog(String stage, RendererConfig.ResolutionResult decision) {
        String line = "[Launcher/" + stage + "] " + decision.toLogText() + "\n";
        try {
            RuntimePaths.ensureBaseDirs(this);
            File logFile = RuntimePaths.latestLog(this);
            File parent = logFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return;
            }
            try (FileOutputStream output = new FileOutputStream(logFile, true)) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable error) {
            Log.w(TAG, "Failed to append renderer decision log", error);
        }
    }

    private void copyUriToFile(Uri uri, File targetFile) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent);
        }

        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(targetFile, false)) {
            if (input == null) {
                throw new IOException("Unable to open file from picker");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    private int importSaveArchive(Uri uri) throws IOException {
        File stsRoot = RuntimePaths.stsRoot(this);
        if (!stsRoot.exists() && !stsRoot.mkdirs()) {
            throw new IOException("Failed to create save root: " + stsRoot.getAbsolutePath());
        }

        String rootCanonical = stsRoot.getCanonicalPath();
        int importedFiles = 0;

        try (InputStream rawInput = getContentResolver().openInputStream(uri)) {
            if (rawInput == null) {
                throw new IOException("Unable to open selected archive");
            }
            try (java.util.zip.ZipInputStream zipInput = new java.util.zip.ZipInputStream(rawInput)) {
                java.util.zip.ZipEntry entry;
                byte[] buffer = new byte[8192];
                while ((entry = zipInput.getNextEntry()) != null) {
                    String mappedPath = mapArchiveEntryPath(entry.getName());
                    if (mappedPath == null || mappedPath.isEmpty()) {
                        continue;
                    }
                    if ("desktop-1.0.jar".equalsIgnoreCase(mappedPath)) {
                        continue;
                    }
                    if (mappedPath.startsWith("__MACOSX/")) {
                        continue;
                    }

                    File output = new File(stsRoot, mappedPath);
                    String outputCanonical = output.getCanonicalPath();
                    if (!outputCanonical.equals(rootCanonical) && !outputCanonical.startsWith(rootCanonical + File.separator)) {
                        throw new IOException("Unsafe archive entry: " + entry.getName());
                    }

                    if (entry.isDirectory()) {
                        if (!output.exists() && !output.mkdirs()) {
                            throw new IOException("Failed to create directory: " + output.getAbsolutePath());
                        }
                        continue;
                    }

                    File parent = output.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
                    }

                    try (FileOutputStream out = new FileOutputStream(output, false)) {
                        int read;
                        while ((read = zipInput.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                        }
                    }
                    importedFiles++;
                }
            }
        }

        if (importedFiles == 0) {
            throw new IOException("Archive did not contain importable save files");
        }
        return importedFiles;
    }

    @Nullable
    private String mapArchiveEntryPath(String rawEntryName) {
        if (rawEntryName == null) {
            return null;
        }

        String path = rawEntryName.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty() || path.contains("../")) {
            return null;
        }

        int filesSts = path.indexOf("files/sts/");
        if (filesSts >= 0) {
            path = path.substring(filesSts + "files/sts/".length());
        } else if (path.startsWith("sts/")) {
            path = path.substring("sts/".length());
        } else {
            int nestedSts = path.indexOf("/sts/");
            if (nestedSts >= 0) {
                path = path.substring(nestedSts + "/sts/".length());
            } else {
                path = stripWrapperFolder(path);
            }
        }

        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty() || path.contains("../")) {
            return null;
        }
        return path;
    }

    private String stripWrapperFolder(String path) {
        int firstSlash = path.indexOf('/');
        if (firstSlash <= 0 || firstSlash >= path.length() - 1) {
            return path;
        }
        String first = path.substring(0, firstSlash).toLowerCase(Locale.ROOT);
        String remainder = path.substring(firstSlash + 1);
        String second = remainder;
        int secondSlash = remainder.indexOf('/');
        if (secondSlash > 0) {
            second = remainder.substring(0, secondSlash);
        }
        second = second.toLowerCase(Locale.ROOT);

        if (!isLikelySaveTopLevel(first) && isLikelySaveTopLevel(second)) {
            return remainder;
        }
        return path;
    }

    private boolean isLikelySaveTopLevel(String folder) {
        return "betapreferences".equals(folder)
                || "preferences".equals(folder)
                || "saves".equals(folder)
                || "sendtodevs".equals(folder)
                || "runs".equals(folder)
                || "metrics".equals(folder)
                || "home".equals(folder);
    }
}
