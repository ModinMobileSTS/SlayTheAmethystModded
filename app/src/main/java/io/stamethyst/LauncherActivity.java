package io.stamethyst;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import net.kdt.pojavlaunch.MainActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class LauncherActivity extends AppCompatActivity {
    private static final float DEFAULT_RENDER_SCALE = 0.75f;
    private static final float MIN_RENDER_SCALE = 0.50f;
    private static final float MAX_RENDER_SCALE = 1.00f;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView statusText;
    private TextView logPathText;
    private Button importButton;
    private Button importSavesButton;
    private EditText renderScaleInput;
    private Button saveRenderScaleButton;
    private Button launchButton;

    private final ActivityResultLauncher<String[]> importJarLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onJarPicked);
    private final ActivityResultLauncher<String[]> importSavesLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onSavesArchivePicked);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MainActivity.init(getApplicationContext());
        setContentView(R.layout.activity_launcher);

        statusText = findViewById(R.id.statusText);
        logPathText = findViewById(R.id.logPathText);
        importButton = findViewById(R.id.importButton);
        importSavesButton = findViewById(R.id.importSavesButton);
        renderScaleInput = findViewById(R.id.renderScaleInput);
        saveRenderScaleButton = findViewById(R.id.saveRenderScaleButton);
        launchButton = findViewById(R.id.launchButton);

        logPathText.setText("Log: " + RuntimePaths.latestLog(this).getAbsolutePath());

        importButton.setOnClickListener(v -> importJarLauncher.launch(new String[]{"application/java-archive", "application/octet-stream", "*/*"}));
        importSavesButton.setOnClickListener(v -> importSavesLauncher.launch(new String[]{"application/zip", "application/x-zip-compressed", "*/*"}));
        saveRenderScaleButton.setOnClickListener(v -> saveRenderScaleFromInput(true));

        launchButton.setOnClickListener(v -> {
            if (!saveRenderScaleFromInput(false)) {
                return;
            }
            prepareAndLaunch();
        });

        loadRenderScaleInput();
        refreshStatus();
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

    private void prepareAndLaunch() {
        setBusy(true, "Preparing runtime/components...");
        executor.execute(() -> {
            try {
                File jar = RuntimePaths.importedStsJar(this);
                StsJarValidator.validate(jar);
                ComponentInstaller.ensureInstalled(this);
                RuntimePackInstaller.ensureInstalled(this);

                runOnUiThread(() -> {
                    setBusy(false, null);
                    startActivity(new Intent(this, StsGameActivity.class));
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Launch preparation failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    refreshStatus();
                });
            }
        });
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

    private void refreshStatus() {
        boolean hasJar = RuntimePaths.importedStsJar(this).exists();
        float renderScale = readRenderScaleValue();
        String status = hasJar ? "desktop-1.0.jar imported" : "Please import desktop-1.0.jar";
        statusText.setText(status
                + "\nRender scale: " + String.format(Locale.US, "%.2f", renderScale) + " (0.50-1.00)"
                + "\nRuntime pack expected at build time: runtime-pack/jre8-pojav.zip");
        setBusy(false, null);
    }

    private void setBusy(boolean busy, @Nullable String message) {
        importButton.setEnabled(!busy);
        importSavesButton.setEnabled(!busy);
        renderScaleInput.setEnabled(!busy);
        saveRenderScaleButton.setEnabled(!busy);
        launchButton.setEnabled(!busy);
        if (busy && message != null) {
            statusText.setText(message);
        }
    }

    private void loadRenderScaleInput() {
        renderScaleInput.setText(String.format(Locale.US, "%.2f", readRenderScaleValue()));
    }

    private float readRenderScaleValue() {
        File config = renderScaleConfigFile();
        if (!config.exists()) {
            return DEFAULT_RENDER_SCALE;
        }
        try (InputStream input = new java.io.FileInputStream(config)) {
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
            try (ZipInputStream zipInput = new ZipInputStream(rawInput)) {
                ZipEntry entry;
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
