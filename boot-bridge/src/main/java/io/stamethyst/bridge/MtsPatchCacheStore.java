package io.stamethyst.bridge;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Closeable;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class MtsPatchCacheStore {
    private static final long MIN_CACHE_JAR_BYTES = 1024L * 1024L;
    private static final String PROPERTY_ENABLED = "amethyst.mts.patch_cache.enabled";
    private static final String PROPERTY_JAR = "amethyst.mts.patch_cache.jar";
    private static final String PROPERTY_BASE_JAR = "amethyst.mts.patch_cache.base_jar";
    private static final String PROPERTY_MARKER = "amethyst.mts.patch_cache.marker";
    private static final String PROPERTY_PACKAGE_DIR = "amethyst.mts.patch_cache.package_dir";
    private static final String PROPERTY_EXPECTED = "amethyst.mts.patch_cache.expected";
    private static final String PROPERTY_PACKAGE_JAR_THREADS = "amethyst.mts.patch_cache.package_jar_threads";
    private static final String PROPERTY_MIN_FREE_BYTES = "amethyst.mts.patch_cache.min_free_bytes";
    /**
     * The main jar embeds the base game jar and the package jars add roughly another
     * copy spread across mods, so two base-jar equivalents plus headroom for the
     * apostrophe alias copies and metadata. Deliberately an estimate, not a prediction.
     */
    private static final long CACHE_BUILD_SIZE_FACTOR = 3L;
    private static final long MIN_CACHE_BUILD_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_PACKAGE_JAR_THREADS = 4;
    /**
     * Copy buffer for the fallback merge rewrite. The primary path folds compiled
     * classes into the first fast write, so this only runs when MTS's own package
     * writer produced the jar.
     */
    private static final int MERGE_COPY_BUFFER_BYTES = 64 * 1024;
    /** Worker count used by the last cache-jar write. Read by tests only. */
    static volatile int lastPackageJarThreadCount = 0;
    /**
     * Compiled base-game classes waiting to be folded into the fast main-jar write.
     * Set by {@link #store} right before it invokes MTS's packageJar and consumed by
     * {@link #packageJarFastPath} on the same thread. Package-private so tests can
     * seed it when calling the fast path directly.
     */
    static volatile Map<String, byte[]> pendingCompiledClassOverrides = null;
    /**
     * Whether the last packageJar invocation wrote the main jar through the fast
     * writer (and therefore already applied the pending overrides). store() checks
     * this to skip its serial full-archive merge rewrite.
     */
    static volatile boolean lastPackageJarFastPathTookOver = false;
    /** Distinct threads that ran cache-jar tasks in the last write. Read by tests only. */
    static final Set<String> lastCacheJarThreadNames =
            Collections.synchronizedSet(new LinkedHashSet<String>());
    /** Name of the thread that wrote the main jar in the last write. Read by tests only. */
    static volatile String lastMainJarThreadName = null;
    private static final ThreadLocal<Boolean> COMPILE_CAPTURE_RESTORE_OUT_JAR = new ThreadLocal<Boolean>();

    private MtsPatchCacheStore() {
    }

    public static void beginCompileCapture() {
        COMPILE_CAPTURE_RESTORE_OUT_JAR.remove();
        if (!Boolean.parseBoolean(System.getProperty(PROPERTY_ENABLED, "false"))) {
            return;
        }

        try {
            Class<?> mtsLoaderClass = loadMtsLoaderClass(MtsPatchCacheStore.class.getClassLoader());
            Field packageField = mtsLoaderClass.getDeclaredField("PACKAGE");
            Field outJarField = mtsLoaderClass.getDeclaredField("OUT_JAR");
            packageField.setAccessible(true);
            outJarField.setAccessible(true);
            boolean previousPackage = packageField.getBoolean(null);
            boolean previousOutJar = outJarField.getBoolean(null);
            if (!previousPackage && !previousOutJar) {
                outJarField.setBoolean(null, true);
                COMPILE_CAPTURE_RESTORE_OUT_JAR.set(Boolean.TRUE);
                log("Enabled temporary OUT_JAR capture for MTS patch cache");
            }
        } catch (Throwable error) {
            COMPILE_CAPTURE_RESTORE_OUT_JAR.remove();
            log("Failed to enable temporary OUT_JAR capture for MTS patch cache: " + error);
        }
    }

    public static void finishCompileCapture() {
        boolean shouldRestore = Boolean.TRUE.equals(COMPILE_CAPTURE_RESTORE_OUT_JAR.get());
        COMPILE_CAPTURE_RESTORE_OUT_JAR.remove();
        if (!shouldRestore) {
            return;
        }

        try {
            Class<?> mtsLoaderClass = loadMtsLoaderClass(MtsPatchCacheStore.class.getClassLoader());
            Field outJarField = mtsLoaderClass.getDeclaredField("OUT_JAR");
            outJarField.setAccessible(true);
            outJarField.setBoolean(null, false);
        } catch (Throwable error) {
            log("Failed to restore temporary OUT_JAR capture for MTS patch cache: " + error);
        }
    }

    public static String resolvePackageDirPath() {
        String packageDir = System.getProperty(PROPERTY_PACKAGE_DIR, "").trim();
        if (Boolean.parseBoolean(System.getProperty(PROPERTY_ENABLED, "false")) && packageDir.length() != 0) {
            return packageDir;
        }
        return "package";
    }

    public static boolean packageJarFastPath(
            Object classPool,
            Object entries,
            Object openJarOutputStream,
            String outputPath
    ) {
        if (!Boolean.parseBoolean(System.getProperty(PROPERTY_ENABLED, "false"))) {
            return false;
        }
        // Any bail-out below must leave store() on its serial merge fallback.
        lastPackageJarFastPathTookOver = false;
        long startNs = System.nanoTime();
        boolean closedOriginalOutput = false;
        try {
            if (classPool == null || entries == null || outputPath == null || outputPath.trim().length() == 0) {
                return false;
            }
            PackageJarReflection reflection = PackageJarReflection.create(classPool, entries);
            List<EntrySnapshot> snapshots = reflection.readEntries(entries);
            if (snapshots.isEmpty()) {
                return false;
            }

            closeIfPossible(openJarOutputStream);
            closedOriginalOutput = true;

            File cachedJar = new File(outputPath);
            File parent = cachedJar.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IllegalStateException("Failed to create cache jar dir: " + parent.getAbsolutePath());
            }
            File packageDir = new File(resolvePackageDirPath());
            if (!packageDir.isDirectory() && !packageDir.mkdirs()) {
                throw new IllegalStateException("Failed to create cache package dir: " + packageDir.getAbsolutePath());
            }

            int packageThreads = writeFastCacheJars(
                    reflection,
                    snapshots,
                    cachedJar,
                    packageDir,
                    pendingCompiledClassOverrides
            );
            lastPackageJarFastPathTookOver = true;
            logStep(
                    "packageJarFastPath entries=" + snapshots.size() +
                            " cacheBytes=" + (cachedJar.isFile() ? cachedJar.length() : 0L) +
                            " packageJars=" + countPackageJars(packageDir) +
                            " jarThreads=" + packageThreads,
                    startNs
            );
            return true;
        } catch (Throwable error) {
            if (closedOriginalOutput) {
                throw new IllegalStateException("Failed after taking over MTS PackageJar output", error);
            }
            log("MTS patch cache fast package writer unavailable: " + error);
            return false;
        }
    }

    public static void store(Object classPool) {
        store(classPool, null);
    }

    public static void store(Object classPool, Object compiledClassPath) {
        if (!Boolean.parseBoolean(System.getProperty(PROPERTY_ENABLED, "false"))) {
            return;
        }
        long storeStartNs = System.nanoTime();
        String expectedMarker = System.getProperty(PROPERTY_EXPECTED, "").trim();
        File cachedJar = new File(System.getProperty(PROPERTY_JAR, ""));
        File baseJar = new File(System.getProperty(PROPERTY_BASE_JAR, ""));
        File markerFile = new File(System.getProperty(PROPERTY_MARKER, ""));
        File packageDir = resolvePackageDir(cachedJar);
        File generatedPackageDir = resolveGeneratedPackageDir();
        File diagnosticFile = resolveDiagnosticFile(cachedJar);
        if (expectedMarker.length() == 0 || classPool == null) {
            return;
        }
        if (!hasRoomToBuildCache(cachedJar, baseJar)) {
            return;
        }

        try {
            long cleanupStartNs = System.nanoTime();
            deleteIfExists(markerFile);
            deleteIfExists(diagnosticFile);
            File parent = cachedJar.getParentFile();
            if (parent != null) {
                MtsPatchAnnotationDbCache.delete(parent);
                MtsPatchMainJarSpireEnumCache.delete(parent);
            }
            deletePackageJars(packageDir);
            if (!sameFile(packageDir, generatedPackageDir)) {
                deletePackageJars(generatedPackageDir);
            }
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IllegalStateException("Failed to create cache dir: " + parent.getAbsolutePath());
            }
            if (!packageDir.isDirectory() && !packageDir.mkdirs()) {
                throw new IllegalStateException("Failed to create cache package dir: " + packageDir.getAbsolutePath());
            }
            logStep("cleanup", cleanupStartNs);
            ClassLoader loader = classPool.getClass().getClassLoader();
            Class<?> packageJarClass = Class.forName(
                    "com.evacipated.cardcrawl.modthespire.PackageJar",
                    false,
                    loader
            );
            Method packageJar = packageJarClass.getDeclaredMethod("packageJar", classPool.getClass(), String.class);
            packageJar.setAccessible(true);
            log("Writing MTS patch cache jar: " + cachedJar.getAbsolutePath());
            writeDiagnostic(
                    diagnosticFile,
                    "start user.dir=" + System.getProperty("user.dir") +
                            " cacheJar=" + cachedJar.getAbsolutePath() +
                            " packageDir=" + packageDir.getAbsolutePath()
            );
            long collectStartNs = System.nanoTime();
            // Collected before primeOutJarClasses/packageJar so the overrides can ride
            // along the first fast main-jar write instead of a second full rewrite.
            // The compiledClassPath object is untouched by priming, so collecting here
            // sees exactly what the old post-packageJar collection saw.
            Map<String, byte[]> compiledOverrides =
                    collectCompiledClasses(baseJar, compiledClassPath, diagnosticFile);
            logStep("collectCompiledClasses candidates=" + compiledOverrides.size(), collectStartNs);
            pendingCompiledClassOverrides = compiledOverrides;
            long primeStartNs = System.nanoTime();
            primeOutJarClasses(classPool, diagnosticFile);
            logStep("primeOutJarClasses", primeStartNs);
            long packageStartNs = System.nanoTime();
            lastPackageJarFastPathTookOver = false;
            try {
                invokePackageJarInCacheRoot(packageJar, classPool, cachedJar);
            } finally {
                pendingCompiledClassOverrides = null;
            }
            logStep(
                    "invokePackageJar cacheBytes=" + (cachedJar.isFile() ? cachedJar.length() : 0L) +
                            " packageJars=" + countPackageJars(packageDir),
                    packageStartNs
            );
            long mergeStartNs = System.nanoTime();
            int mergedCompiledClasses;
            if (lastPackageJarFastPathTookOver) {
                // The fast writer already substituted every override into the main jar
                // as it wrote it. Re-running the serial merge would re-read and re-write
                // the whole archive for bytes that are already in place.
                mergedCompiledClasses = compiledOverrides.size();
                logStep(
                        "mergeCompiledClasses skipped, folded into fast write folded=" +
                                mergedCompiledClasses,
                        mergeStartNs
                );
            } else {
                mergedCompiledClasses = mergeCompiledClasses(cachedJar, compiledOverrides, diagnosticFile);
                logStep("mergeCompiledClasses merged=" + mergedCompiledClasses, mergeStartNs);
            }
            writeDiagnostic(
                    diagnosticFile,
                    "after packageJar cacheBytes=" + (cachedJar.isFile() ? cachedJar.length() : 0L) +
                            " packageJars=" + countPackageJars(packageDir) +
                            " mergedCompiledClasses=" + mergedCompiledClasses
            );
            if (countPackageJars(packageDir) == 0 && !sameFile(packageDir, generatedPackageDir)) {
                long migrateStartNs = System.nanoTime();
                migratePackageJars(generatedPackageDir, packageDir, diagnosticFile);
                logStep("migratePackageJars packageJars=" + countPackageJars(packageDir), migrateStartNs);
            }
            long aliasStartNs = System.nanoTime();
            createJsonEscapedPackageAliases(packageDir);
            logStep("createJsonEscapedPackageAliases packageJars=" + countPackageJars(packageDir), aliasStartNs);
            long validateStartNs = System.nanoTime();
            if (!cachedJar.isFile() || cachedJar.length() < MIN_CACHE_JAR_BYTES) {
                throw new IllegalStateException(
                        "Cache jar was not created or is too small: " +
                                cachedJar.getAbsolutePath() +
                                " bytes=" +
                                (cachedJar.isFile() ? cachedJar.length() : 0L)
                );
            }
            int packageJarCount = countPackageJars(packageDir);
            if (packageJarCount == 0) {
                throw new IllegalStateException("Cache package jars were not created: " + packageDir.getAbsolutePath());
            }
            logStep("validateCacheArtifacts packageJars=" + packageJarCount, validateStartNs);
            if (parent != null) {
                long metadataStartNs = System.nanoTime();
                writeMetadataCaches(loader, parent, packageDir, cachedJar);
                logStep("writeMetadataCaches", metadataStartNs);
            }
            long markerStartNs = System.nanoTime();
            syncCacheArtifacts(cachedJar, packageDir, parent);
            logStep("syncCacheArtifacts", markerStartNs);
            long markerWriteStartNs = System.nanoTime();
            writeMarker(markerFile, expectedMarker);
            logStep("writeMarker", markerWriteStartNs);
            log("MTS patch cache is ready: packageJars=" + packageJarCount);
            logStep("store total", storeStartNs);
            deleteIfExists(diagnosticFile);
        } catch (Throwable error) {
            deleteIfExists(markerFile);
            deleteIfExists(cachedJar);
            File parent = cachedJar.getParentFile();
            if (parent != null) {
                MtsPatchAnnotationDbCache.delete(parent);
                MtsPatchMainJarSpireEnumCache.delete(parent);
            }
            deletePackageJars(packageDir);
            if (!sameFile(packageDir, generatedPackageDir)) {
                deletePackageJars(generatedPackageDir);
            }
            writeFailureDiagnostic(diagnosticFile, error);
            log("Failed to write MTS patch cache: " + error);
            error.printStackTrace(System.out);
        }
    }

    /**
     * Fallback merge, used only when MTS's own package writer produced the cached jar
     * (fast path unavailable or failed). Rewrites the whole archive once so the
     * compiled base-game class bytes win over whatever the writer embedded.
     *
     * <p>The copy stays stream-based on purpose. A raw zip-record-level rewrite would
     * avoid inflating every entry, but it means hand-parsing local headers and
     * rebuilding the central directory on the durability-critical artifact; that risk
     * is not worth taking for a path that now almost never runs.
     */
    private static int mergeCompiledClasses(
            File cachedJar,
            Map<String, byte[]> classes,
            File diagnosticFile
    ) throws Exception {
        if (classes.isEmpty()) {
            return 0;
        }

        long rewriteStartNs = System.nanoTime();
        File tempJar = new File(cachedJar.getAbsolutePath() + ".merge.tmp");
        Set<String> written = new HashSet<String>();
        ZipInputStream input = new ZipInputStream(new FileInputStream(cachedJar));
        try {
            ZipOutputStream output = new ZipOutputStream(new FileOutputStream(tempJar, false));
            output.setLevel(Deflater.NO_COMPRESSION);
            try {
                byte[] buffer = new byte[MERGE_COPY_BUFFER_BYTES];
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (classes.containsKey(name) || !written.add(name)) {
                        input.closeEntry();
                        continue;
                    }
                    ZipEntry replacement = new ZipEntry(name);
                    if (entry.getTime() >= 0L) {
                        replacement.setTime(entry.getTime());
                    }
                    output.putNextEntry(replacement);
                    if (!entry.isDirectory()) {
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                        }
                    }
                    output.closeEntry();
                    input.closeEntry();
                }
                for (Map.Entry<String, byte[]> override : classes.entrySet()) {
                    if (!written.add(override.getKey())) {
                        continue;
                    }
                    output.putNextEntry(new ZipEntry(override.getKey()));
                    output.write(override.getValue());
                    output.closeEntry();
                }
            } finally {
                output.close();
            }
        } finally {
            input.close();
        }

        // Replace in place without opening a window where neither jar exists. rename(2)
        // over an existing path is atomic on the Android filesystems we target; the
        // delete+copy path is only a fallback for filesystems that refuse it.
        fsyncFile(tempJar);
        if (!tempJar.renameTo(cachedJar)) {
            if (!cachedJar.delete()) {
                deleteIfExists(tempJar);
                throw new IllegalStateException("Failed to replace cache jar: " + cachedJar.getAbsolutePath());
            }
            if (!tempJar.renameTo(cachedJar)) {
                copyFile(tempJar, cachedJar);
                deleteIfExists(tempJar);
            }
        }
        logStep("rewriteCachedJar compiledClasses=" + classes.size(), rewriteStartNs);
        return classes.size();
    }

    private static Map<String, byte[]> collectCompiledClasses(
            File baseJar,
            Object compiledClassPath,
            File diagnosticFile
    ) {
        Map<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        if (compiledClassPath == null) {
            writeDiagnostic(diagnosticFile, "no compiled classpath available for cache merge");
            return result;
        }

        try {
            Field classesField = findField(compiledClassPath.getClass(), "classes");
            if (classesField == null) {
                writeDiagnostic(diagnosticFile, "compiled classpath has no classes field: " + compiledClassPath.getClass().getName());
                return result;
            }
            classesField.setAccessible(true);
            Object rawClasses = classesField.get(compiledClassPath);
            if (!(rawClasses instanceof Map)) {
                writeDiagnostic(diagnosticFile, "compiled classpath classes field is not a map");
                return result;
            }

            Map<?, ?> classes = (Map<?, ?>) rawClasses;
            for (Map.Entry<?, ?> entry : classes.entrySet()) {
                if (!(entry.getKey() instanceof String) || entry.getValue() == null) {
                    continue;
                }
                String className = (String) entry.getKey();
                Object info = entry.getValue();
                Field classFileField = findField(info.getClass(), "classfile");
                if (classFileField == null) {
                    continue;
                }
                classFileField.setAccessible(true);
                Object rawBytes = classFileField.get(info);
                if (!(rawBytes instanceof byte[])) {
                    continue;
                }
                URL origin = readOriginUrl(info);
                if (!shouldMergeCompiledClass(className, origin, baseJar)) {
                    continue;
                }
                result.put(className.replace('.', '/') + ".class", (byte[]) rawBytes);
            }
            writeDiagnostic(
                    diagnosticFile,
                    "compiled classpath merge candidates=" + result.size() +
                            " compiledCount=" + classes.size()
            );
        } catch (Throwable error) {
            writeDiagnostic(diagnosticFile, "failed to collect compiled classes: " + error);
        }
        return result;
    }

    private static URL readOriginUrl(Object info) {
        try {
            Field urlField = findField(info.getClass(), "url");
            if (urlField == null) {
                return null;
            }
            urlField.setAccessible(true);
            Object rawUrl = urlField.get(info);
            return rawUrl instanceof URL ? (URL) rawUrl : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean shouldMergeCompiledClass(String className, URL origin, File baseJar) {
        if (className.startsWith("com.megacrit.")) {
            return true;
        }
        return baseJar.isFile() && origin != null && sameFile(resolveOriginJar(origin), baseJar);
    }

    private static File resolveOriginJar(URL origin) {
        try {
            String spec = origin.toString();
            if (spec.startsWith("jar:")) {
                spec = spec.substring(4);
            }
            int bang = spec.indexOf('!');
            if (bang >= 0) {
                spec = spec.substring(0, bang);
            }
            if (spec.startsWith("file:")) {
                return new File(new URL(spec).toURI());
            }
            return new File(origin.toURI());
        } catch (Throwable ignored) {
            return new File(origin.getPath());
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Writes the merged main jar and every mod's package jar. The main jar and each
     * package jar target a distinct file and share only immutable state, so all of them
     * run on one pool rather than the main jar blocking the package jars. The main jar
     * is the single largest write (it carries the whole base game jar), so overlapping
     * it with the package jars is where most of the wall-clock saving comes from.
     * Returns the number of worker threads actually used.
     */
    private static int writeFastCacheJars(
            PackageJarReflection reflection,
            List<EntrySnapshot> entries,
            File cachedJar,
            File packageDir,
            Map<String, byte[]> compiledOverrides
    ) throws Exception {
        List<Callable<Void>> tasks = new ArrayList<Callable<Void>>();
        // Built on the calling thread: createClassPath() and the Loader/MODINFOS
        // reflection reads touch MTS statics, so they stay off the workers. The
        // overrides map comes from the same thread's store() call and is only read
        // inside the tasks.
        tasks.add(mainJarTask(reflection, entries, cachedJar, compiledOverrides));
        tasks.addAll(packageJarTasks(reflection, entries, packageDir));
        return runCacheJarTasks(tasks);
    }

    private static int runCacheJarTasks(List<Callable<Void>> tasks) throws Exception {
        lastCacheJarThreadNames.clear();
        lastMainJarThreadName = null;
        if (tasks.isEmpty()) {
            lastPackageJarThreadCount = 0;
            return 0;
        }
        int threads = packageJarThreadCount(tasks.size());
        lastPackageJarThreadCount = threads;
        runTasks(tasks, threads, "amethyst-cache-jar-", "Cache jar write failed");
        return threads;
    }

    /**
     * Runs every task, either inline when {@code threads <= 1} or on a throwaway fixed
     * pool. Always waits for completion and rethrows the first failure.
     */
    private static void runTasks(
            List<Callable<Void>> tasks,
            int threads,
            final String threadNamePrefix,
            String failureMessage
    ) throws Exception {
        if (tasks.isEmpty()) {
            return;
        }
        if (threads <= 1) {
            for (Callable<Void> task : tasks) {
                task.call();
            }
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, threadNamePrefix + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
        try {
            List<Future<Void>> futures = executor.invokeAll(tasks);
            // invokeAll waits for completion, but failures only surface on get().
            // Propagate the first one so store() falls back to the normal MTS flow
            // instead of committing a marker over a partially written cache.
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    throw new IllegalStateException(failureMessage, cause);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static Callable<Void> mainJarTask(
            PackageJarReflection reflection,
            final List<EntrySnapshot> entries,
            final File cachedJar,
            final Map<String, byte[]> compiledOverrides
    ) throws Exception {
        final Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, "com.evacipated.cardcrawl.modthespire.PackageJar$PrepackagedLauncher");
        attributes.put(Attributes.Name.CLASS_PATH, reflection.createClassPath());
        attributes.put(new Attributes.Name("Created-By"), "ModTheSpire");

        // The four source streams are opened here rather than inside the worker: each
        // one reads an MTS static or a ProtectionDomain, which must not race with the
        // package jar workers.
        final InputStream mtsJar = reflection.openMtsJar();
        final InputStream kotlinJar = reflection.openKotlinJar();
        final InputStream corePatchesJar = reflection.openCorePatchesJar();
        final InputStream baseGameJar = reflection.openBaseGameJar();

        return new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                String threadName = Thread.currentThread().getName();
                lastMainJarThreadName = threadName;
                lastCacheJarThreadNames.add(threadName);
                writeFastMainJar(manifest, entries, cachedJar, mtsJar, kotlinJar, corePatchesJar, baseGameJar, compiledOverrides);
                return null;
            }
        };
    }

    /**
     * Writes the merged main jar in one pass. Compiled base-game class bytes carried
     * in {@code compiledOverrides} are substituted inline wherever a matching entry
     * appears, and classes that no source jar contains are appended at the end — the
     * same content and precedence the old post-hoc merge rewrite produced, minus the
     * second full read and write of the archive.
     */
    private static void writeFastMainJar(
            Manifest manifest,
            List<EntrySnapshot> entries,
            File cachedJar,
            InputStream mtsJar,
            InputStream kotlinJar,
            InputStream corePatchesJar,
            InputStream baseGameJar,
            Map<String, byte[]> compiledOverrides
    ) throws Exception {
        final Map<String, byte[]> overrides =
                compiledOverrides == null
                        ? Collections.<String, byte[]>emptyMap()
                        : compiledOverrides;
        Map<String, EntrySnapshot> entriesByPath = mapEntriesByPath(entries);
        Set<String> written = new HashSet<String>();
        FileOutputStream fileOutput = new FileOutputStream(cachedJar, false);
        try {
            JarOutputStream output = new JarOutputStream(fileOutput, manifest);
            try {
                output.setLevel(Deflater.NO_COMPRESSION);
                writeSelectedJarEntries(output, written, entriesByPath, mtsJar, null, "MTS", overrides);
                writeSelectedJarEntries(output, written, entriesByPath, kotlinJar, null, "KOTLIN", overrides);
                writeOutJarEntries(output, written, entries, null, overrides);
                writeSelectedJarEntries(output, written, entriesByPath, corePatchesJar, null, "COREPATCH", overrides);
                writeSelectedJarEntries(output, written, entriesByPath, baseGameJar, null, "BASEGAME", overrides);
                appendRemainingOverrides(output, written, overrides);
            } finally {
                output.close();
            }
        } finally {
            fileOutput.close();
        }
    }

    /**
     * Appends override classes that matched no source entry (brand-new classes
     * introduced by patches). Already-substituted names are skipped by the written
     * set. Iteration order follows the collection map, so it stays deterministic.
     */
    private static void appendRemainingOverrides(
            JarOutputStream output,
            Set<String> written,
            Map<String, byte[]> overrides
    ) throws Exception {
        for (Map.Entry<String, byte[]> override : overrides.entrySet()) {
            writeByteArrayEntry(output, written, override.getKey(), override.getValue());
        }
    }

    /**
     * Builds one task per mod package jar. Each mod reads its own source jar and writes
     * its own target jar, sharing only immutable state.
     */
    private static List<PackageJarTask> packageJarTasks(
            PackageJarReflection reflection,
            List<EntrySnapshot> entries,
            File packageDir
    ) throws Exception {
        final Map<String, EntrySnapshot> entriesByPath = mapEntriesByPath(entries);
        final List<EntrySnapshot> sharedEntries = entries;
        Object[] modInfos = reflection.modInfos();

        // Group by target path first. Two mods whose source jars share a file name
        // collapse onto one target; MTS's serial loop let the later mod overwrite the
        // earlier one, and running them concurrently would instead interleave both
        // into the same file. Keeping only the last mod per target both preserves the
        // original last-wins result and removes the collision.
        Map<String, PackageJarTask> tasksByTarget = new LinkedHashMap<String, PackageJarTask>();
        for (int index = 0; index < modInfos.length; index++) {
            Object modInfo = modInfos[index];
            URL jarUrl = reflection.modJarUrl(modInfo);
            String modId = reflection.modId(modInfo);
            File sourceJar = new File(jarUrl.toURI());
            File targetJar = new File(packageDir, reflection.createModdedJarName(sourceJar.getName()));
            PackageJarTask task =
                    new PackageJarTask(sharedEntries, entriesByPath, jarUrl, modId, sourceJar, targetJar);
            PackageJarTask displaced = tasksByTarget.put(targetJar.getAbsolutePath(), task);
            if (displaced != null) {
                log("Duplicate package jar target, keeping last mod: " + targetJar.getName());
            }
        }
        return new ArrayList<PackageJarTask>(tasksByTarget.values());
    }

    private static int packageJarThreadCount(int taskCount) {
        String override = System.getProperty(PROPERTY_PACKAGE_JAR_THREADS, "").trim();
        if (override.length() != 0) {
            try {
                int requested = Integer.parseInt(override);
                if (requested >= 1) {
                    return Math.min(requested, taskCount);
                }
            } catch (NumberFormatException ignored) {
                log("Ignoring invalid " + PROPERTY_PACKAGE_JAR_THREADS + ": " + override);
            }
        }
        // The work is a read-modify-write over jars, so it is bound by storage as much
        // as by CPU. Capping at 4 keeps the pool from thrashing the flash on the phones
        // this runs on while still covering the common many-small-mods case.
        int cpus = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(Math.min(cpus, MAX_PACKAGE_JAR_THREADS), taskCount));
    }

    private static void writeSinglePackageJar(
            List<EntrySnapshot> entries,
            Map<String, EntrySnapshot> entriesByPath,
            URL jarUrl,
            String modId,
            File sourceJar,
            File targetJar
    ) throws Exception {
        Set<String> written = new HashSet<String>();
        FileOutputStream fileOutput = new FileOutputStream(targetJar, false);
        try {
            JarOutputStream output = new JarOutputStream(fileOutput);
            try {
                output.setLevel(Deflater.NO_COMPRESSION);
                writeOutJarEntries(output, written, entries, jarUrl, null);
                writeSelectedJarEntries(output, written, entriesByPath, new FileInputStream(sourceJar), modId, "MOD", null);
            } finally {
                output.close();
            }
        } finally {
            fileOutput.close();
        }
    }

    /**
     * One mod's package jar write. Holds only immutable shared state; {@code written}
     * is created per call so tasks never touch each other's data.
     */
    private static final class PackageJarTask implements Callable<Void> {
        private final List<EntrySnapshot> entries;
        private final Map<String, EntrySnapshot> entriesByPath;
        private final URL jarUrl;
        private final String modId;
        private final File sourceJar;
        private final File targetJar;

        PackageJarTask(
                List<EntrySnapshot> entries,
                Map<String, EntrySnapshot> entriesByPath,
                URL jarUrl,
                String modId,
                File sourceJar,
                File targetJar
        ) {
            this.entries = entries;
            this.entriesByPath = entriesByPath;
            this.jarUrl = jarUrl;
            this.modId = modId;
            this.sourceJar = sourceJar;
            this.targetJar = targetJar;
        }

        @Override
        public Void call() throws Exception {
            lastCacheJarThreadNames.add(Thread.currentThread().getName());
            writeSinglePackageJar(entries, entriesByPath, jarUrl, modId, sourceJar, targetJar);
            return null;
        }
    }

    private static Map<String, EntrySnapshot> mapEntriesByPath(List<EntrySnapshot> entries) {
        Map<String, EntrySnapshot> byPath = new LinkedHashMap<String, EntrySnapshot>();
        for (EntrySnapshot entry : entries) {
            byPath.put(entry.path, entry);
        }
        return byPath;
    }

    /**
     * Writes one source jar's selected entries into the main jar. When
     * {@code overrides} is non-null and holds bytes for an entry, those bytes replace
     * the source's own content — compiled patch classes must win over every original
     * copy of the class regardless of which section declares it first.
     */
    private static void writeSelectedJarEntries(
            JarOutputStream output,
            Set<String> written,
            Map<String, EntrySnapshot> entriesByPath,
            InputStream input,
            String modId,
            String type,
            Map<String, byte[]> overrides
    ) throws Exception {
        if (input == null) {
            return;
        }
        try {
            JarInputStream jarInput = new JarInputStream(input);
            try {
                byte[] buffer = new byte[8192];
                JarEntry jarEntry;
                while ((jarEntry = jarInput.getNextJarEntry()) != null) {
                    String name = jarEntry.getName();
                    EntrySnapshot expected = entriesByPath.get(name);
                    if (expected == null) {
                        continue;
                    }
                    // Directory entries carry no bytes and legitimately belong to every jar that
                    // has content under them, but MTS deduplicates entries globally by path, so
                    // the first mod to declare `com/github/` becomes its sole owner. Applying the
                    // ownership filter to directories would therefore strip them from every other
                    // mod's package jar. That silently breaks classpath scanning: Spring resolves
                    // `classpath*:com/github/**` by enumerating getResources("com/github/"), which
                    // only matches jars that physically contain that directory entry. Classes stay
                    // loadable, so the jar just disappears from component scanning.
                    if (jarEntry.isDirectory()) {
                        writeDirectoryEntry(output, written, name);
                        continue;
                    }
                    if (!type.equals(expected.type) || !Objects.equals(modId, expected.modId)) {
                        continue;
                    }
                    byte[] overrideBytes = overrides == null ? null : overrides.get(name);
                    if (overrideBytes != null) {
                        writeByteArrayEntry(output, written, name, overrideBytes);
                    } else {
                        writeStreamEntry(output, written, name, jarInput, buffer);
                    }
                }
            } finally {
                jarInput.close();
            }
        } finally {
            input.close();
        }
    }

    private static void writeOutJarEntries(
            JarOutputStream output,
            Set<String> written,
            List<EntrySnapshot> entries,
            URL locationUrl,
            Map<String, byte[]> overrides
    ) throws Exception {
        for (EntrySnapshot entry : entries) {
            if (!"OUTJAR".equals(entry.type) || !Objects.equals(locationUrl, entry.locationUrl) || entry.bytes == null) {
                continue;
            }
            byte[] overrideBytes = overrides == null ? null : overrides.get(entry.path);
            if (overrideBytes != null) {
                writeByteArrayEntry(output, written, entry.path, overrideBytes);
            } else {
                writeByteArrayEntry(output, written, entry.path, entry.bytes);
            }
        }
    }

    private static void writeStreamEntry(
            JarOutputStream output,
            Set<String> written,
            String name,
            InputStream input,
            byte[] buffer
    ) throws Exception {
        if (!written.add(name)) {
            return;
        }
        output.putNextEntry(new JarEntry(name));
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        output.closeEntry();
    }

    private static void writeDirectoryEntry(
            JarOutputStream output,
            Set<String> written,
            String name
    ) throws Exception {
        if (!written.add(name)) {
            return;
        }
        output.putNextEntry(new JarEntry(name));
        output.closeEntry();
    }

    private static void writeByteArrayEntry(
            JarOutputStream output,
            Set<String> written,
            String name,
            byte[] bytes
    ) throws Exception {
        if (!written.add(name)) {
            return;
        }
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static void closeIfPossible(Object value) throws Exception {
        if (value instanceof Closeable) {
            ((Closeable) value).close();
        }
    }

    private static final class EntrySnapshot {
        final String path;
        final String modId;
        final String type;
        final byte[] bytes;
        final URL locationUrl;

        EntrySnapshot(String path, String modId, String type, byte[] bytes, URL locationUrl) {
            this.path = path;
            this.modId = modId;
            this.type = type;
            this.bytes = bytes;
            this.locationUrl = locationUrl;
        }
    }

    private static final class PackageJarReflection {
        final Class<?> packageJarClass;
        final Class<?> loaderClass;
        final Field entriesField;
        final Field entryPathField;
        final Field entryModIdField;
        final Field entryTypeField;
        final Field entryBytesField;
        final Field entryLocationUrlField;
        final Field modInfosField;
        final Field modInfoJarUrlField;
        final Field modInfoIdField;
        final Field stsJarField;
        final Field kotlinJarField;
        final Field corePatchesJarField;
        final Method createClassPathMethod;
        final Method createModdedJarNameMethod;

        private PackageJarReflection(
                Class<?> packageJarClass,
                Class<?> loaderClass,
                Field entriesField,
                Field entryPathField,
                Field entryModIdField,
                Field entryTypeField,
                Field entryBytesField,
                Field entryLocationUrlField,
                Field modInfosField,
                Field modInfoJarUrlField,
                Field modInfoIdField,
                Field stsJarField,
                Field kotlinJarField,
                Field corePatchesJarField,
                Method createClassPathMethod,
                Method createModdedJarNameMethod
        ) {
            this.packageJarClass = packageJarClass;
            this.loaderClass = loaderClass;
            this.entriesField = entriesField;
            this.entryPathField = entryPathField;
            this.entryModIdField = entryModIdField;
            this.entryTypeField = entryTypeField;
            this.entryBytesField = entryBytesField;
            this.entryLocationUrlField = entryLocationUrlField;
            this.modInfosField = modInfosField;
            this.modInfoJarUrlField = modInfoJarUrlField;
            this.modInfoIdField = modInfoIdField;
            this.stsJarField = stsJarField;
            this.kotlinJarField = kotlinJarField;
            this.corePatchesJarField = corePatchesJarField;
            this.createClassPathMethod = createClassPathMethod;
            this.createModdedJarNameMethod = createModdedJarNameMethod;
        }

        static PackageJarReflection create(Object classPool, Object entries) throws Exception {
            ClassLoader loader = classPool.getClass().getClassLoader();
            Class<?> packageJarClass = Class.forName(
                    "com.evacipated.cardcrawl.modthespire.PackageJar",
                    false,
                    loader
            );
            Class<?> loaderClass = loadMtsLoaderClass(loader);
            Field entriesField = requireField(entries.getClass(), "entries");
            Object values = entriesField.get(entries);
            Class<?> entryClass = null;
            if (values instanceof Map) {
                Collection<?> collection = ((Map<?, ?>) values).values();
                for (Object entry : collection) {
                    if (entry != null) {
                        entryClass = entry.getClass();
                        break;
                    }
                }
            }
            if (entryClass == null) {
                throw new IllegalStateException("PackageJar entries are empty or unsupported");
            }
            Field modInfosField = requireField(loaderClass, "MODINFOS");
            Object[] modInfos = (Object[]) modInfosField.get(null);
            Class<?> modInfoClass = modInfos.length == 0 ? null : modInfos[0].getClass();
            if (modInfoClass == null) {
                throw new IllegalStateException("MTS Loader.MODINFOS is empty");
            }
            Method createClassPath = packageJarClass.getDeclaredMethod("createClassPath");
            createClassPath.setAccessible(true);
            Method createModdedJarName = packageJarClass.getDeclaredMethod("createModdedJarName", String.class);
            createModdedJarName.setAccessible(true);
            return new PackageJarReflection(
                    packageJarClass,
                    loaderClass,
                    entriesField,
                    requireField(entryClass, "path"),
                    requireField(entryClass, "modID"),
                    requireField(entryClass, "type"),
                    requireField(entryClass, "b"),
                    requireField(entryClass, "locationURL"),
                    modInfosField,
                    requireField(modInfoClass, "jarURL"),
                    requireField(modInfoClass, "ID"),
                    requireField(loaderClass, "STS_JAR"),
                    requireField(loaderClass, "KOTLIN_JAR"),
                    requireField(loaderClass, "COREPATCHES_JAR"),
                    createClassPath,
                    createModdedJarName
            );
        }

        List<EntrySnapshot> readEntries(Object entries) throws Exception {
            Object raw = entriesField.get(entries);
            if (!(raw instanceof Map)) {
                throw new IllegalStateException("PackageJar entries field is not a Map");
            }
            Collection<?> values = ((Map<?, ?>) raw).values();
            List<EntrySnapshot> snapshots = new ArrayList<EntrySnapshot>(values.size());
            for (Object entry : values) {
                if (entry == null) {
                    continue;
                }
                Object type = entryTypeField.get(entry);
                snapshots.add(new EntrySnapshot(
                        (String) entryPathField.get(entry),
                        (String) entryModIdField.get(entry),
                        type == null ? null : String.valueOf(type),
                        (byte[]) entryBytesField.get(entry),
                        (URL) entryLocationUrlField.get(entry)
                ));
            }
            return snapshots;
        }

        String createClassPath() throws Exception {
            return (String) createClassPathMethod.invoke(null);
        }

        String createModdedJarName(String fileName) throws Exception {
            return (String) createModdedJarNameMethod.invoke(null, fileName);
        }

        Object[] modInfos() throws Exception {
            Object value = modInfosField.get(null);
            return value == null ? new Object[0] : (Object[]) value;
        }

        URL modJarUrl(Object modInfo) throws Exception {
            return (URL) modInfoJarUrlField.get(modInfo);
        }

        String modId(Object modInfo) throws Exception {
            return (String) modInfoIdField.get(modInfo);
        }

        InputStream openMtsJar() throws Exception {
            File source = new File(loaderClass.getProtectionDomain().getCodeSource().getLocation().toURI());
            return source.isFile() ? new FileInputStream(source) : null;
        }

        InputStream openKotlinJar() throws Exception {
            return loaderClass.getResourceAsStream((String) kotlinJarField.get(null));
        }

        InputStream openCorePatchesJar() throws Exception {
            return loaderClass.getResourceAsStream((String) corePatchesJarField.get(null));
        }

        InputStream openBaseGameJar() throws Exception {
            return new FileInputStream(new File((String) stsJarField.get(null)));
        }

        private static Field requireField(Class<?> type, String name) throws Exception {
            Field field = findField(type, name);
            if (field == null) {
                throw new NoSuchFieldException(type.getName() + "." + name);
            }
            field.setAccessible(true);
            return field;
        }
    }

    private static void invokePackageJarInCacheRoot(Method packageJar, Object classPool, File cachedJar)
            throws Throwable {
        String previousUserDir = System.getProperty("user.dir");
        File cacheRoot = cachedJar.getParentFile();
        try {
            if (cacheRoot != null) {
                System.setProperty("user.dir", cacheRoot.getAbsolutePath());
            }
            packageJar.invoke(null, classPool, cachedJar.getAbsolutePath());
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            throw cause == null ? error : cause;
        } finally {
            if (previousUserDir == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", previousUserDir);
            }
        }
    }

    private static File resolvePackageDir(File cachedJar) {
        String raw = System.getProperty(PROPERTY_PACKAGE_DIR, "").trim();
        if (raw.length() != 0) {
            return new File(raw);
        }
        File parent = cachedJar.getParentFile();
        return new File(parent == null ? new File(".") : parent, "package");
    }

    private static File resolveGeneratedPackageDir() {
        String launchUserDir = System.getProperty("user.dir", "").trim();
        if (launchUserDir.length() == 0) {
            return new File("package");
        }
        return new File(launchUserDir, "package");
    }

    private static File resolveDiagnosticFile(File cachedJar) {
        File parent = cachedJar.getParentFile();
        return new File(parent == null ? new File(".") : parent, "mts_patch_cache_debug.log");
    }

    private static void primeOutJarClasses(Object classPool, File diagnosticFile) throws Exception {
        Class<?> classPoolClass = classPool.getClass();
        ClassLoader loader = classPoolClass.getClassLoader();
        Method getOutJarClasses = classPoolClass.getMethod("getOutJarClasses");
        Object outJarClasses = getOutJarClasses.invoke(classPool);
        if (outJarClasses != null) {
            writeDiagnostic(
                    diagnosticFile,
                    "reusing existing outJar outJarCount=" + collectionSize(outJarClasses)
            );
            return;
        }

        Method getModifiedClasses = classPoolClass.getMethod("getModifiedClasses");
        Class<?> mtsLoaderClass = loadMtsLoaderClass(loader);
        Field packageField = mtsLoaderClass.getDeclaredField("PACKAGE");
        Field outJarField = mtsLoaderClass.getDeclaredField("OUT_JAR");
        packageField.setAccessible(true);
        outJarField.setAccessible(true);
        boolean previousPackage = packageField.getBoolean(null);
        boolean previousOutJar = outJarField.getBoolean(null);
        Object modifiedClasses;
        try {
            if (!previousPackage && !previousOutJar) {
                packageField.setBoolean(null, true);
            }
            modifiedClasses = getModifiedClasses.invoke(classPool);
            outJarClasses = getOutJarClasses.invoke(classPool);
        } finally {
            packageField.setBoolean(null, previousPackage);
            outJarField.setBoolean(null, previousOutJar);
        }
        if (outJarClasses == null) {
            throw new IllegalStateException("MTSClassPool.getOutJarClasses() returned null while preparing cache");
        }
        writeDiagnostic(
                diagnosticFile,
                "primed outJar modifiedCount=" + collectionSize(modifiedClasses) +
                        " outJarCount=" + collectionSize(outJarClasses)
        );
    }

    private static Class<?> loadMtsLoaderClass(ClassLoader preferredLoader) throws ClassNotFoundException {
        ClassNotFoundException failure = null;
        ClassLoader[] candidates = new ClassLoader[] {
                preferredLoader,
                Thread.currentThread().getContextClassLoader(),
                MtsPatchCacheStore.class.getClassLoader()
        };
        for (ClassLoader loader : candidates) {
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName("com.evacipated.cardcrawl.modthespire.Loader", false, loader);
            } catch (ClassNotFoundException error) {
                failure = error;
            }
        }
        throw failure == null
                ? new ClassNotFoundException("com.evacipated.cardcrawl.modthespire.Loader")
                : failure;
    }

    private static int countPackageJars(File packageDir) {
        File[] files = packageDir.isDirectory() ? packageDir.listFiles() : null;
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File file : files) {
            if (isJar(file)) {
                count++;
            }
        }
        return count;
    }

    private static void migratePackageJars(File sourceDir, File targetDir, File diagnosticFile) throws Exception {
        File[] files = sourceDir.isDirectory() ? sourceDir.listFiles() : null;
        if (files == null) {
            writeDiagnostic(diagnosticFile, "no generated package dir to migrate: " + sourceDir.getAbsolutePath());
            return;
        }
        if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
            throw new IllegalStateException("Failed to create cache package dir: " + targetDir.getAbsolutePath());
        }
        int migrated = 0;
        for (File source : files) {
            if (!isJar(source)) {
                continue;
            }
            File target = new File(targetDir, source.getName());
            deleteIfExists(target);
            if (!source.renameTo(target)) {
                copyFile(source, target);
                if (!source.delete()) {
                    source.deleteOnExit();
                }
            }
            migrated++;
        }
        writeDiagnostic(
                diagnosticFile,
                "migrated packageJars=" + migrated +
                        " from=" + sourceDir.getAbsolutePath() +
                        " to=" + targetDir.getAbsolutePath()
        );
    }

    private static void deletePackageJars(File packageDir) {
        File[] files = packageDir.isDirectory() ? packageDir.listFiles() : null;
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (isJar(file)) {
                file.delete();
            }
        }
    }

    private static boolean isJar(File file) {
        return file.isFile() && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
    }

    private static void createJsonEscapedPackageAliases(File packageDir) throws Exception {
        File[] files = packageDir.isDirectory() ? packageDir.listFiles() : null;
        if (files == null) {
            return;
        }
        // Each alias is a full copy of one package jar into a distinct target, so the
        // copies are independent. Snapshot the listing first: the loop adds files to the
        // same directory it is scanning.
        List<Callable<Void>> tasks = new ArrayList<Callable<Void>>();
        for (File file : files) {
            if (!isJar(file) || file.getName().indexOf('\'') < 0) {
                continue;
            }
            final File source = file;
            final File alias = new File(packageDir, file.getName().replace("'", "u0027"));
            if (alias.exists()) {
                continue;
            }
            tasks.add(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    copyFile(source, alias);
                    return null;
                }
            });
        }
        runTasks(tasks, packageJarThreadCount(tasks.size()), "amethyst-cache-alias-", "Package alias copy failed");
    }

    private static void copyFile(File source, File target) throws Exception {
        FileInputStream input = new FileInputStream(source);
        try {
            FileOutputStream output = new FileOutputStream(target, false);
            try {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            } finally {
                output.close();
            }
        } finally {
            input.close();
        }
    }

    private static void writeMarker(File markerFile, final String expectedMarker) throws Exception {
        // The marker is the commit point for the whole cache. It must only become
        // durable after every artifact it vouches for is already on disk, otherwise a
        // power loss can leave a "current" marker pointing at truncated jars.
        AtomicFileWriter.write(markerFile, new AtomicFileWriter.ContentWriter() {
            @Override
            public void write(FileOutputStream output) throws java.io.IOException {
                output.write(expectedMarker.getBytes(StandardCharsets.UTF_8));
                output.write('\n');
            }
        });
    }

    /**
     * Flushes the cache artifacts to stable storage before the marker is written.
     * Without this the filesystem is free to make the small marker durable ahead of
     * the large jars, which would turn an interrupted build into a silent cache hit
     * on incomplete data.
     */
    private static void syncCacheArtifacts(File cachedJar, File packageDir, File cacheRoot) {
        // Each fsync blocks on the device with no CPU work to overlap, so issuing them
        // concurrently lets the storage stack coalesce the flushes instead of paying
        // one full round trip per file. The directory fsyncs stay last and serial:
        // they are what makes the preceding file syncs reachable through the tree.
        List<Callable<Void>> tasks = new ArrayList<Callable<Void>>();
        tasks.add(fsyncTask(cachedJar));
        File[] files = packageDir.isDirectory() ? packageDir.listFiles() : null;
        if (files != null) {
            for (File file : files) {
                if (isJar(file)) {
                    tasks.add(fsyncTask(file));
                }
            }
        }
        if (cacheRoot != null) {
            tasks.add(fsyncTask(MtsPatchAnnotationDbCache.resolve(cacheRoot)));
            tasks.add(fsyncTask(MtsPatchMainJarSpireEnumCache.resolve(cacheRoot)));
        }
        try {
            runTasks(tasks, packageJarThreadCount(tasks.size()), "amethyst-cache-fsync-", "Cache fsync failed");
        } catch (Exception error) {
            // fsyncFile already swallows per-file failures; anything surfacing here is
            // a pool problem. Fall back to a serial pass rather than skipping durability.
            log("Parallel cache fsync failed, falling back to serial: " + error);
            for (Callable<Void> task : tasks) {
                try {
                    task.call();
                } catch (Exception ignored) {
                    // fsyncFile never throws; nothing actionable left.
                }
            }
        }
        fsyncDirectory(packageDir);
        fsyncDirectory(cacheRoot);
    }

    /**
     * Writes the annotation DB and SpireEnum index caches. They target different files
     * and read different sources — the annotation DB serializes {@code Patcher
     * .annotationDBMap}, the enum index rescans the finished main jar — so they run
     * concurrently. The enum scan is the slower of the two because it walks the whole
     * main jar.
     *
     * <p>Both helpers swallow their own failures and delete their partial output, so a
     * worker never propagates; the caches are optional and a miss only costs a slower
     * cache hit later.
     */
    private static void writeMetadataCaches(
            final ClassLoader loader,
            final File cacheRoot,
            final File packageDir,
            final File cachedJar
    ) throws Exception {
        List<Callable<Void>> tasks = new ArrayList<Callable<Void>>(2);
        tasks.add(new Callable<Void>() {
            @Override
            public Void call() {
                MtsPatchAnnotationDbCache.writeFromPatcher(loader, cacheRoot, packageDir);
                return null;
            }
        });
        tasks.add(new Callable<Void>() {
            @Override
            public Void call() {
                MtsPatchMainJarSpireEnumCache.writeFromPatchedJar(loader, cacheRoot, cachedJar);
                return null;
            }
        });
        runTasks(tasks, packageJarThreadCount(tasks.size()), "amethyst-cache-meta-", "Metadata cache write failed");
    }

    /**
     * Refuses to start a cache build that cannot possibly fit.
     *
     * The build writes the merged main jar — which embeds the whole base game jar — plus
     * one package jar per mod, plus a duplicate copy for every mod whose name contains an
     * apostrophe. Without this check a full filesystem surfaces as an IOException partway
     * through, after {@code store} has already deleted the previous cache: the run then
     * falls back correctly, but every subsequent launch repeats the same doomed build and
     * pays its full cost.
     *
     * The estimate is deliberately crude — the base jar times a small factor — because
     * the exact output size is unknown until the jars are written, and the goal is only
     * to catch the hopeless case, not to predict precisely. Unknown free space (0 from
     * {@code getUsableSpace}) is treated as permission to proceed rather than a refusal,
     * so an unreadable filesystem does not disable caching outright.
     */
    private static boolean hasRoomToBuildCache(File cachedJar, File baseJar) {
        try {
            File cacheRoot = cachedJar.getParentFile();
            if (cacheRoot == null) {
                return true;
            }
            File probe = cacheRoot.isDirectory() ? cacheRoot : cacheRoot.getParentFile();
            if (probe == null || !probe.isDirectory()) {
                return true;
            }
            long usableBytes = probe.getUsableSpace();
            if (usableBytes <= 0L) {
                return true;
            }
            long requiredBytes = requiredCacheBuildBytes(baseJar);
            if (requiredBytes <= 0L || usableBytes >= requiredBytes) {
                return true;
            }
            log("Skipping MTS patch cache build: needs about " + (requiredBytes / (1024L * 1024L)) +
                    "MB but only " + (usableBytes / (1024L * 1024L)) + "MB is free at " +
                    probe.getAbsolutePath());
            return false;
        } catch (Throwable error) {
            log("Failed to check free space for MTS patch cache, proceeding: " + error);
            return true;
        }
    }

    /**
     * Estimated bytes the build needs, or 0 to skip the check entirely.
     *
     * The property override exists so tests can force both branches without filling a
     * real filesystem, and so a device that trips the heuristic wrongly can be unblocked
     * without a new build.
     */
    private static long requiredCacheBuildBytes(File baseJar) {
        String override = System.getProperty(PROPERTY_MIN_FREE_BYTES, "").trim();
        if (override.length() > 0) {
            try {
                return Long.parseLong(override);
            } catch (NumberFormatException ignored) {
                // Fall through to the heuristic.
            }
        }
        long baseJarBytes = baseJar.isFile() ? baseJar.length() : 0L;
        return Math.max(MIN_CACHE_BUILD_BYTES, baseJarBytes * CACHE_BUILD_SIZE_FACTOR);
    }

    private static Callable<Void> fsyncTask(final File file) {
        return new Callable<Void>() {
            @Override
            public Void call() {
                fsyncFile(file);
                return null;
            }
        };
    }

    private static void fsyncFile(File file) {
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            java.io.FileInputStream input = new java.io.FileInputStream(file);
            try {
                input.getFD().sync();
            } finally {
                input.close();
            }
        } catch (Throwable ignored) {
            // Best effort: a failed fsync must not fail the cache build.
        }
    }

    private static void fsyncDirectory(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        java.nio.channels.FileChannel channel = null;
        try {
            channel = java.nio.channels.FileChannel.open(
                    directory.toPath(),
                    java.nio.file.StandardOpenOption.READ
            );
            channel.force(true);
        } catch (Throwable ignored) {
            // Directory fsync is not portable; ignore failures.
        } finally {
            if (channel != null) {
                try {
                    channel.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void deleteIfExists(File file) {
        if (file.exists()) {
            file.delete();
        }
    }

    private static boolean sameFile(File left, File right) {
        try {
            return left.getCanonicalFile().equals(right.getCanonicalFile());
        } catch (Throwable ignored) {
            return left.getAbsoluteFile().equals(right.getAbsoluteFile());
        }
    }

    private static int collectionSize(Object value) {
        return value instanceof Collection ? ((Collection<?>) value).size() : -1;
    }

    private static void writeFailureDiagnostic(File diagnosticFile, Throwable error) {
        Throwable cause = error instanceof InvocationTargetException && ((InvocationTargetException) error).getCause() != null
                ? ((InvocationTargetException) error).getCause()
                : error;
        StringWriter stackTrace = new StringWriter();
        cause.printStackTrace(new PrintWriter(stackTrace));
        writeDiagnostic(
                diagnosticFile,
                "failed error=" + error + "\nrootCause=" + cause + "\n" + stackTrace
        );
    }

    private static void writeDiagnostic(File diagnosticFile, String message) {
        try {
            File parent = diagnosticFile.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            FileOutputStream output = new FileOutputStream(diagnosticFile, true);
            try {
                output.write(message.getBytes(StandardCharsets.UTF_8));
                output.write('\n');
            } finally {
                output.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void log(String message) {
        System.out.println("[Amethyst] " + message);
    }

    private static void logStep(String label, long startNs) {
        log("MTS patch cache step " + label + " took " + elapsedMs(startNs) + "ms");
    }

    private static long elapsedMs(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1000000L);
    }
}
