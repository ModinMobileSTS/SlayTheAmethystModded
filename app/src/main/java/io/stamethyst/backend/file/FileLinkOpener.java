package io.stamethyst.backend.file;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.util.ArrayDeque;
import java.io.File;
import java.util.Locale;

/**
 * Safely handles URLs or local file paths from bridge/native callbacks.
 * Local files are routed to a SAF export flow instead of being opened directly.
 */
public final class FileLinkOpener {
    private static final String TAG = "FileLinkOpener";
    private static final int MAX_DIRECTORY_SCAN_ENTRIES = 4096;

    private FileLinkOpener() {
    }

    public static void open(Context context, String rawValue) {
        if (context == null || TextUtils.isEmpty(rawValue)) {
            return;
        }
        String value = rawValue.trim();
        if (value.isEmpty()) {
            return;
        }
        try {
            Intent intent = buildIntent(context, value);
            if (intent == null) {
                Log.w(TAG, "Ignore open request, unsupported value: " + value);
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable error) {
            // Never propagate to JNI bridge. Unhandled Java exceptions here can abort ART.
            Log.w(TAG, "Failed to open value: " + value, error);
        }
    }

    private static Intent buildIntent(Context context, String value) {
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return new Intent(Intent.ACTION_VIEW, Uri.parse(value));
        }
        if (value.contains("://")) {
            Uri parsed = Uri.parse(value);
            if ("file".equalsIgnoreCase(parsed.getScheme())) {
                File file = fileFromUri(parsed);
                return file == null ? null : buildSafExportIntent(context, file);
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, parsed);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return intent;
        }
        return buildSafExportIntent(context, new File(value));
    }

    private static File fileFromUri(Uri uri) {
        String path = uri.getPath();
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        return new File(path);
    }

    private static Intent buildSafExportIntent(Context context, File file) {
        File target = resolveOpenTarget(file);
        if (target == null) {
            return null;
        }
        Intent intent = new Intent(context, SafExportActivity.class);
        intent.putExtra(SafExportActivity.EXTRA_SOURCE_PATH, target.getAbsolutePath());
        intent.putExtra(SafExportActivity.EXTRA_SUGGESTED_NAME, target.getName());
        intent.putExtra(SafExportActivity.EXTRA_MIME_TYPE, resolveMimeType(target));
        return intent;
    }

    private static File resolveOpenTarget(File input) {
        if (input == null || !input.exists()) {
            return null;
        }
        if (input.isFile()) {
            return input;
        }
        File newestFile = findNewestRegularFile(input);
        if (newestFile != null) {
            Log.i(TAG, "Directory open request resolved to newest file: " + newestFile.getAbsolutePath());
        } else {
            Log.w(TAG, "Directory open request has no readable files: " + input.getAbsolutePath());
        }
        return newestFile;
    }

    private static File findNewestRegularFile(File rootDirectory) {
        ArrayDeque<File> stack = new ArrayDeque<>();
        stack.push(rootDirectory);
        File newestFile = null;
        long newestModified = Long.MIN_VALUE;
        int scannedEntries = 0;
        while (!stack.isEmpty() && scannedEntries < MAX_DIRECTORY_SCAN_ENTRIES) {
            File current = stack.pop();
            scannedEntries++;
            File[] children = current.listFiles();
            if (children == null || children.length == 0) {
                continue;
            }
            for (File child : children) {
                if (child == null) {
                    continue;
                }
                if (child.isDirectory()) {
                    stack.push(child);
                    continue;
                }
                if (!child.isFile()) {
                    continue;
                }
                long modified = child.lastModified();
                if (newestFile == null || modified > newestModified) {
                    newestFile = child;
                    newestModified = modified;
                }
            }
        }
        return newestFile;
    }

    private static String resolveMimeType(File file) {
        String name = file.getName();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == name.length() - 1) {
            return "*/*";
        }
        String extension = name.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return TextUtils.isEmpty(mimeType) ? "*/*" : mimeType;
    }
}
