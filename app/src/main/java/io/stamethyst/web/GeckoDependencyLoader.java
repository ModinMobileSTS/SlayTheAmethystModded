package io.stamethyst.web;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import dalvik.system.DexClassLoader;
import io.stamethyst.BuildConfig;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileLock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Download-only code container. No package installation and no Gecko classes in the launcher. */
public final class GeckoDependencyLoader {
    private static final long MAX_DOWNLOAD = 200L * 1024 * 1024;
    private static final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private static volatile DexClassLoader loader;

    private static File directory(Context context) {
        return new File(context.getNoBackupFilesDir(), "web-runtime/" + BuildConfig.WEB_RUNTIME_VERSION);
    }
    public static boolean isInstalled(Context context) {
        File dir = directory(context);
        return new File(dir, "bundle.zip").isFile() && new File(dir, "runtime.apk").isFile()
            && new File(dir, "lib/libxul.so").isFile();
    }
    public static void ensureAsync(Context context, IntConsumer progress, Consumer<String> completed) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        downloads.execute(() -> {
            String error = null;
            try { install(app, value -> main.post(() -> progress.accept(value))); }
            catch (Exception e) { error = e.getMessage() == null ? e.toString() : e.getMessage(); }
            String result = error;
            main.post(() -> completed.accept(result));
        });
    }
    private static void requireSupported() {
        if (Build.VERSION.SDK_INT < 28) throw new IllegalStateException("GeckoView requires Android 9+; use system WebView on Android 8.");
        if (!android.os.Process.is64Bit() || !java.util.Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"))
            throw new IllegalStateException("This GeckoView dependency requires an arm64 launcher process.");
        if (BuildConfig.WEB_RUNTIME_SHA256.length() != 64) throw new IllegalStateException("Web runtime checksum is not configured.");
    }
    private static void install(Context context, IntConsumer progress) throws Exception {
        requireSupported();
        File root = directory(context).getParentFile();
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create web runtime directory");
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(root, "install.lock"), "rw");
             FileLock lock = lockFile.getChannel().lock()) {
            if (isInstalled(context)) {
                try { GeckoDependencyArchive.verify(directory(context), BuildConfig.WEB_RUNTIME_SHA256); return; }
                catch (Exception invalid) {
                    if (loader != null) throw new IOException("Loaded Gecko dependency is damaged; restart before downloading again", invalid);
                    // Preserve the invalid directory until a replacement has been fully verified.
                }
            }
            if (BuildConfig.WEB_RUNTIME_URL.isEmpty()) throw new IOException("Web dependency CDN URL has not been configured yet.");
            File stage = new File(root, BuildConfig.WEB_RUNTIME_VERSION + ".staging");
            deleteOwnedDirectory(stage);
            if (!stage.mkdirs()) throw new IOException("Cannot create dependency staging directory");
            try {
                File bundle = new File(stage, "bundle.zip");
                download(BuildConfig.WEB_RUNTIME_URL, bundle, progress);
                progress.accept(100);
                // Android 14+ requires dynamically loaded code to be read-only before loading.
                GeckoDependencyArchive.unpack(stage, BuildConfig.WEB_RUNTIME_SHA256);
                File target = directory(context);
                deleteOwnedDirectory(target); // Only the incomplete, pinned dependency directory.
                if (!stage.renameTo(target)) throw new IOException("Cannot publish web dependency");
            } finally { deleteOwnedDirectory(stage); }
        }
    }
    private static void download(String address, File destination, IntConsumer progress) throws Exception {
        URL url = new URL(address);
        for (int redirect = 0; redirect < 6; redirect++) {
            if (!"https".equals(url.getProtocol())) throw new IOException("Web dependencies require HTTPS");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("CDN redirect has no Location");
                    url = new URL(url, location);
                    continue;
                }
                if (status != 200) throw new IOException("Web dependency HTTP " + status);
                long total = connection.getContentLengthLong();
                if (total > MAX_DOWNLOAD) throw new IOException("Web dependency exceeds download limit");
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(destination)) {
                    byte[] buffer = new byte[128 * 1024];
                    long received = 0;
                    int lastProgress = -2;
                    for (int n; (n = in.read(buffer)) >= 0;) {
                        received += n;
                        if (received > MAX_DOWNLOAD) throw new IOException("Web dependency exceeds download limit");
                        out.write(buffer, 0, n);
                        int value = total > 0 ? (int) Math.min(99, received * 100 / total) : -1;
                        if (value != lastProgress) { progress.accept(value); lastProgress = value; }
                    }
                    if (total >= 0 && received != total) throw new IOException("Incomplete web dependency download");
                }
                return;
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many CDN redirects");
    }
    private static void deleteOwnedDirectory(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteOwnedDirectory(child);
        if (!file.delete()) throw new IOException("Cannot remove dependency staging file " + file.getName());
    }
    public static synchronized ClassLoader classLoader(Context context) throws Exception {
        if (loader != null) return loader;
        requireSupported();
        File dir = directory(context);
        GeckoDependencyArchive.verify(dir, BuildConfig.WEB_RUNTIME_SHA256);
        loader = new DexClassLoader(new File(dir, "runtime.apk").getPath(), context.getCodeCacheDir().getPath(),
            new File(dir, "lib").getPath(), context.getClassLoader());
        return loader;
    }
    public static Context wrapContext(Context context) throws Exception {
        File dir = directory(context);
        return (Context) classLoader(context).loadClass("io.stamethyst.webruntime.RuntimeContext")
            .getConstructor(Context.class, String.class, String.class)
            .newInstance(context, new File(dir, "runtime.apk").getPath(), new File(dir, "lib").getPath());
    }
    public static Object createHost(Context context) throws Exception {
        try {
            return classLoader(context).loadClass("io.stamethyst.webruntime.GeckoHost")
                .getConstructor(Context.class).newInstance(wrapContext(context));
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IOException("Cannot start GeckoView: " + e.getCause(), e.getCause());
        }
    }
}
