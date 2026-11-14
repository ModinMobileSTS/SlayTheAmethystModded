package io.stamethyst.gdx;

import com.badlogic.gdx.Files.FileType;
import java.io.File;

/** Keep resource reads and FileHandle hierarchy operations on the same portable path. */
public final class ResourceFileHandleCompat {
    private ResourceFileHandleCompat() { }

    public static String normalize(String path, FileType type) {
        if (path == null || (type != FileType.Internal && type != FileType.Classpath)) return path;
        return path.replace('\\', '/');
    }

    public static File normalize(File file, FileType type) {
        if (file == null || (type != FileType.Internal && type != FileType.Classpath)) return file;
        String path = file.getPath();
        // Preserve the original File (including subclasses) when no conversion is needed.
        return path.indexOf('\\') < 0 ? file : new File(normalize(path, type));
    }
}
