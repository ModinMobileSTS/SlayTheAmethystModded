package io.stamethyst.bridge;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class MtsPatchAnnotationDbCache {
    private static final String CACHE_FILE_NAME = "annotation-db-cache.bin";
    private static final int CACHE_SCHEMA = 1;
    private static final int CACHE_READ_BUFFER_BYTES = 64 * 1024;
    private static final String ANNOTATION_DB = "org.scannotation.AnnotationDB";
    private static final String MTS_PATCHER = "com.evacipated.cardcrawl.modthespire.Patcher";
    private static final String MTS_MOD_INFO = "com.evacipated.cardcrawl.modthespire.ModInfo";

    private MtsPatchAnnotationDbCache() {
    }

    static File resolve(File cacheRoot) {
        return new File(cacheRoot, CACHE_FILE_NAME);
    }

    static void delete(File cacheRoot) {
        File cacheFile = resolve(cacheRoot);
        if (cacheFile.isFile() && !cacheFile.delete()) {
            log("Failed to delete stale MTS annotation DB cache: " + cacheFile.getAbsolutePath());
        }
    }

    static void writeFromPatcher(ClassLoader loader, File cacheRoot, File packageDir) {
        long startedAtNs = System.nanoTime();
        File cacheFile = resolve(cacheRoot);
        try {
            Object annotationDbMap = patcherAnnotationDbMap(loader).get(null);
            if (!(annotationDbMap instanceof Map)) {
                throw new IllegalStateException("Patcher.annotationDBMap is not a Map");
            }
            final Map<String, Object> entries = new LinkedHashMap<String, Object>();
            for (Object rawEntry : ((Map<?, ?>) annotationDbMap).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) rawEntry;
                Object key = entry.getKey();
                Object db = entry.getValue();
                if (!(key instanceof URL) || db == null) {
                    continue;
                }
                URL url = (URL) key;
                for (String cacheKey : cacheKeysForOriginalUrl(url, packageDir)) {
                    entries.put(cacheKey, db);
                }
            }
            if (entries.isEmpty()) {
                throw new IllegalStateException("Patcher.annotationDBMap had no serializable entries");
            }
            AtomicFileWriter.write(cacheFile, new AtomicFileWriter.ContentWriter() {
                @Override
                public void write(FileOutputStream output) throws IOException {
                    ObjectOutputStream objectOutput = new ObjectOutputStream(output);
                    objectOutput.writeInt(CACHE_SCHEMA);
                    objectOutput.writeObject(entries);
                    objectOutput.flush();
                }
            });
            log("Wrote cached MTS annotation DB entries=" + entries.size() + " took " + elapsedMs(startedAtNs) + "ms");
        } catch (Throwable error) {
            if (cacheFile.isFile()) {
                cacheFile.delete();
            }
            log("Failed to write cached MTS annotation DB: " + error);
        }
    }

    /**
     * Rebuilds {@code Patcher.annotationDBMap} from the on-disk cache.
     *
     * <p>Deliberately does not collect per-mod patch sets. Their only consumer is
     * {@code Patcher.injectPatches}, which must never run on a cache hit — the cached
     * jar already carries the injected bytecode. Collecting them cost four
     * {@code Class.forName} lookups and a set copy per mod for a result nobody read.
     */
    static void restoreIntoPatcher(
            ClassLoader loader,
            File cacheRoot,
            File packageDir,
            Object modInfos
    ) throws Exception {
        long startedAtNs = System.nanoTime();
        File cacheFile = resolve(cacheRoot);
        if (!cacheFile.isFile()) {
            throw new IOException("annotation DB cache is missing: " + cacheFile.getAbsolutePath());
        }
        Map<?, ?> cachedEntries = readCachedEntries(cacheFile, loader);
        Class<?> annotationDbClass = Class.forName(ANNOTATION_DB, false, loader);
        Method getAnnotationIndex = annotationDbClass.getMethod("getAnnotationIndex");
        Field jarUrlField = Class.forName(MTS_MOD_INFO, false, loader).getField("jarURL");
        Map<Object, Object> annotationDbMap = new HashMap<Object, Object>();

        for (int i = 0; i < Array.getLength(modInfos); i++) {
            Object modInfo = Array.get(modInfos, i);
            Object rawUrl = jarUrlField.get(modInfo);
            if (!(rawUrl instanceof URL)) {
                throw new IOException("ModInfo.jarURL was not a URL at index " + i);
            }
            URL url = (URL) rawUrl;
            Object cachedDb = findCachedDbForUrl(cachedEntries, url, packageDir);
            if (cachedDb == null) {
                throw new IOException("annotation DB cache missing mod: " + url);
            }
            Object db = cloneAnnotationDb(annotationDbClass, getAnnotationIndex, cachedDb);
            annotationDbMap.put(url, db);
        }

        patcherAnnotationDbMap(loader).set(null, annotationDbMap);
        log("Restored cached MTS annotation DB: mods=" + Array.getLength(modInfos) +
                " entries=" + cachedEntries.size() +
                " took " + elapsedMs(startedAtNs) + "ms");
    }

    private static Map<?, ?> readCachedEntries(File cacheFile, ClassLoader loader) throws Exception {
        // ObjectInputStream makes many small reads while decoding annotation maps.
        try (InputStream buffered = new BufferedInputStream(new FileInputStream(cacheFile), CACHE_READ_BUFFER_BYTES);
             ClassLoaderObjectInputStream input = new ClassLoaderObjectInputStream(buffered, loader)) {
            int schema = input.readInt();
            if (schema != CACHE_SCHEMA) {
                throw new IOException("unsupported annotation DB cache schema: " + schema);
            }
            Object raw = input.readObject();
            if (!(raw instanceof Map)) {
                throw new IOException("annotation DB cache payload was not a Map");
            }
            return (Map<?, ?>) raw;
        }
    }

    private static Object cloneAnnotationDb(
            Class<?> annotationDbClass,
            Method getAnnotationIndex,
            Object cachedDb
    ) throws Exception {
        Constructor<?> constructor = annotationDbClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object db = constructor.newInstance();
        Map<?, ?> cachedIndex = (Map<?, ?>) getAnnotationIndex.invoke(cachedDb);
        @SuppressWarnings("unchecked")
        Map<Object, Object> targetIndex = (Map<Object, Object>) getAnnotationIndex.invoke(db);
        for (Map.Entry<?, ?> entry : cachedIndex.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Set) {
                targetIndex.put(entry.getKey(), new LinkedHashSet<Object>((Set<?>) value));
            } else {
                targetIndex.put(entry.getKey(), value);
            }
        }
        return db;
    }

    private static Object findCachedDbForUrl(Map<?, ?> cachedEntries, URL url, File packageDir) {
        for (String key : cacheKeysForPackageUrl(url, packageDir)) {
            Object db = cachedEntries.get(key);
            if (db != null) {
                return db;
            }
        }
        return null;
    }

    private static Set<String> cacheKeysForOriginalUrl(URL url, File packageDir) {
        LinkedHashSet<String> keys = new LinkedHashSet<String>();
        addUrlKeys(keys, url);
        String fileName = fileName(url);
        addFileNameKeys(keys, fileName, packageDir);
        return keys;
    }

    private static Set<String> cacheKeysForPackageUrl(URL url, File packageDir) {
        LinkedHashSet<String> keys = new LinkedHashSet<String>();
        addUrlKeys(keys, url);
        String fileName = fileName(url);
        addFileNameKeys(keys, fileName, packageDir);
        if (fileName.endsWith("-modded.jar")) {
            addFileNameKeys(keys, fileName.substring(0, fileName.length() - "-modded.jar".length()) + ".jar", packageDir);
        }
        return keys;
    }

    private static void addUrlKeys(Set<String> keys, URL url) {
        keys.add("url:" + url.toExternalForm());
        String path = url.getPath();
        if (path != null) {
            keys.add("path:" + path);
        }
    }

    private static void addFileNameKeys(Set<String> keys, String fileName, File packageDir) {
        if (fileName.length() == 0) {
            return;
        }
        String decoded = decode(fileName);
        keys.add("file:" + fileName);
        keys.add("file:" + decoded);
        String modded = toModdedJarName(decoded);
        keys.add("file:" + modded);
        keys.add("file:" + jsonEscapeApostrophe(modded));
        if (packageDir != null) {
            keys.add("path:" + new File(packageDir, modded).getPath());
            keys.add("path:" + new File(packageDir, jsonEscapeApostrophe(modded)).getPath());
        }
    }

    private static String toModdedJarName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith("-modded.jar")) {
            return fileName;
        }
        if (lower.endsWith(".jar")) {
            return fileName.substring(0, fileName.length() - 4) + "-modded.jar";
        }
        return fileName + "-modded.jar";
    }

    private static String fileName(URL url) {
        try {
            return new File(url.toURI()).getName();
        } catch (Throwable ignored) {
            String path = url.getPath();
            int index = path == null ? -1 : path.lastIndexOf('/');
            return index >= 0 ? path.substring(index + 1) : (path == null ? "" : path);
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Throwable ignored) {
            return value;
        }
    }

    private static String jsonEscapeApostrophe(String value) {
        return value.replace("'", "u0027");
    }

    private static Field patcherAnnotationDbMap(ClassLoader loader) throws Exception {
        Class<?> patcher = Class.forName(MTS_PATCHER, false, loader);
        Field field = patcher.getField("annotationDBMap");
        field.setAccessible(true);
        return field;
    }

    private static long elapsedMs(long startedAtNs) {
        return (System.nanoTime() - startedAtNs) / 1000000L;
    }

    private static void log(String message) {
        System.out.println("[Amethyst] " + message);
    }

    private static final class ClassLoaderObjectInputStream extends ObjectInputStream {
        private final ClassLoader loader;

        ClassLoaderObjectInputStream(InputStream input, ClassLoader loader) throws IOException {
            super(input);
            this.loader = loader;
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass descriptor) throws IOException, ClassNotFoundException {
            try {
                return Class.forName(descriptor.getName(), false, loader);
            } catch (ClassNotFoundException ignored) {
                return super.resolveClass(descriptor);
            }
        }
    }
}
