package io.stamethyst.bridge;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MtsPatchCacheBootstrap {
    private static final String PROPERTY_ENABLED = "amethyst.mts.patch_cache.enabled";
    private static final String PROPERTY_CURRENT = "amethyst.mts.patch_cache.current";
    private static final String PROPERTY_JAR = "amethyst.mts.patch_cache.jar";
    private static final String PROPERTY_BASE_JAR = "amethyst.mts.patch_cache.base_jar";
    private static final String PROPERTY_MARKER = "amethyst.mts.patch_cache.marker";
    private static final String PROPERTY_PACKAGE_DIR = "amethyst.mts.patch_cache.package_dir";
    private static final String PROPERTY_EXPECTED = "amethyst.mts.patch_cache.expected";
    private static final String PROPERTY_GAME_DIR = "amethyst.mts.patch_cache.game_dir";
    private static final long MIN_CACHE_JAR_BYTES = 1024L * 1024L;
    private static final String PREPACKAGED_LAUNCHER =
            "com.evacipated.cardcrawl.modthespire.PackageJar$PrepackagedLauncher";
    private static final String MTS_LOADER = "com.evacipated.cardcrawl.modthespire.Loader";
    private static final String MTS_MOD_INFO = "com.evacipated.cardcrawl.modthespire.ModInfo";
    private static final String MTS_PATCHER = "com.evacipated.cardcrawl.modthespire.Patcher";
    private static final String GAME_SETTINGS = "com.megacrit.cardcrawl.core.Settings";
    private static final String ANNOTATION_DB = "org.scannotation.AnnotationDB";
    private static final String SPIRE_ENUM = "com.evacipated.cardcrawl.modthespire.lib.SpireEnum";
    private static final String ENUM_BUSTER_REFLECT = "com.evacipated.cardcrawl.modthespire.EnumBusterReflect";
    private static final String REFLECTION_HELPER = "com.evacipated.cardcrawl.modthespire.ReflectionHelper";

    private MtsPatchCacheBootstrap() {
    }

    public static boolean launchIfCurrent() {
        if (!Boolean.parseBoolean(System.getProperty(PROPERTY_ENABLED, "false"))) {
            return false;
        }
        if (!Boolean.parseBoolean(System.getProperty(PROPERTY_CURRENT, "false"))) {
            return false;
        }

        File cachedJar = new File(System.getProperty(PROPERTY_JAR, ""));
        File baseJar = new File(System.getProperty(PROPERTY_BASE_JAR, ""));
        File markerFile = new File(System.getProperty(PROPERTY_MARKER, ""));
        File packageDir = resolvePackageDir();
        String expectedMarker = System.getProperty(PROPERTY_EXPECTED, "").trim();
        if (expectedMarker.length() == 0 || !cachedJar.isFile() || cachedJar.length() < MIN_CACHE_JAR_BYTES) {
            log("Patch cache miss: cached jar is missing");
            return false;
        }
        if (!hasPackageJars(packageDir)) {
            log("Patch cache miss: package jars are missing");
            return false;
        }
        if (!markerMatches(markerFile, expectedMarker)) {
            log("Patch cache miss: marker changed");
            return false;
        }

        try {
            log("Launching cached MTS patch jar: " + cachedJar.getAbsolutePath());
            invokeCachedLauncher(cachedJar, baseJar, packageDir, readMtsArgs());
            return true;
        } catch (CachedGameLaunchFailure gameFailure) {
            // The cached launcher had already started the game when this failed, so
            // falling back would re-run the whole patch pipeline inside a JVM that is
            // no longer clean. Propagate instead, keeping the real crash as the cause.
            log("Cached MTS launch failed after the game started, not falling back: " + gameFailure.getCause());
            throw gameFailure;
        } catch (Throwable error) {
            log("Patch cache launch failed, falling back to ModTheSpire patching: " + error);
            error.printStackTrace(System.out);
            return false;
        }
    }

    /**
     * Marks a failure raised after control passed to the cached game launcher, to
     * separate it from the setup failures that the ModTheSpire path can still retry.
     */
    private static final class CachedGameLaunchFailure extends RuntimeException {
        CachedGameLaunchFailure(Throwable cause) {
            super(cause);
        }
    }

    private static boolean markerMatches(File markerFile, String expectedMarker) {
        try {
            if (!markerFile.isFile()) {
                return false;
            }
            String actual = new String(Files.readAllBytes(markerFile.toPath()), StandardCharsets.UTF_8).trim();
            return expectedMarker.equals(actual);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static File resolvePackageDir() {
        String raw = System.getProperty(PROPERTY_PACKAGE_DIR, "").trim();
        return raw.length() == 0 ? new File("package") : new File(raw);
    }

    private static boolean hasPackageJars(File packageDir) {
        File[] files = packageDir.isDirectory() ? packageDir.listFiles() : null;
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (file.isFile() && file.length() > 0L && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return true;
            }
        }
        return false;
    }

    private static String[] readMtsArgs() {
        try {
            Class<?> loader = Class.forName(MTS_LOADER);
            Object rawArgs = loader.getField("ARGS").get(null);
            if (rawArgs instanceof String[]) {
                return (String[]) rawArgs;
            }
        } catch (Throwable ignored) {
        }
        return new String[0];
    }

    public static void preparePrepackagedLaunch() throws Throwable {
        long startedAtNs = System.nanoTime();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = MtsPatchCacheBootstrap.class.getClassLoader();
        }

        markSettingsAsModded(loader);
        applyGameWorkingDirectory();

        Class<?> mtsLoader = Class.forName(MTS_LOADER, false, loader);
        initializeWorkshopInfos(mtsLoader);
        Object modInfos = mtsLoader.getField("MODINFOS").get(null);
        if (modInfos == null || !modInfos.getClass().isArray()) {
            log("Skipping cached MTS annotation DB preparation: no mod infos");
            return;
        }

        prepareAnnotationDatabase(loader, modInfos);
        log("Prepared cached MTS launch state: mods=" + Array.getLength(modInfos));
        logElapsed("Prepared cached MTS prepackaged launch", startedAtNs);
    }

    private static void initializeWorkshopInfos(Class<?> mtsLoader) throws IllegalAccessException {
        Field workshopInfos;
        try {
            workshopInfos = mtsLoader.getDeclaredField("WORKSHOP_INFOS");
        } catch (NoSuchFieldException ignored) {
            return;
        }
        workshopInfos.setAccessible(true);
        if (workshopInfos.get(null) == null) {
            // Loader.main normally creates this list, but cached launches bypass that path.
            workshopInfos.set(null, new ArrayList<Object>());
            log("Initialized cached MTS workshop infos: items=0");
        }
    }

    public static boolean bustPrepackagedEnumsFromCache() throws Throwable {
        long startedAtNs = System.nanoTime();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = MtsPatchCacheBootstrap.class.getClassLoader();
        }

        Set<String> enumClassNames = collectCachedSpireEnumClasses(loader);
        if (enumClassNames == null) {
            log("Cached MTS enum DB unavailable, falling back to original enum scan");
            return false;
        }
        if (enumClassNames.isEmpty()) {
            logElapsed("Skipped cached MTS enum scan: no SpireEnum entries", startedAtNs);
            return true;
        }

        int fieldCount = bustEnums(loader, enumClassNames);
        log("Applied cached MTS SpireEnum entries: classes=" + enumClassNames.size() +
                " fields=" + fieldCount);
        logElapsed("Applied cached MTS SpireEnum entries", startedAtNs);
        return true;
    }

    private static Set<String> collectCachedSpireEnumClasses(ClassLoader loader) throws Throwable {
        Class<?> patcher = Class.forName(MTS_PATCHER, false, loader);
        Field annotationDbMapField = patcher.getField("annotationDBMap");
        Object rawMap = annotationDbMapField.get(null);
        if (!(rawMap instanceof Map)) {
            return null;
        }
        Map<?, ?> annotationDbMap = (Map<?, ?>) rawMap;
        if (annotationDbMap.isEmpty()) {
            return null;
        }

        Class<?> annotationDbClass = Class.forName(ANNOTATION_DB, false, loader);
        Method getAnnotationIndex = annotationDbClass.getMethod("getAnnotationIndex");
        Class<?> spireEnumClass = Class.forName(SPIRE_ENUM, false, loader);
        String spireEnumName = spireEnumClass.getName();
        Set<String> enumClassNames = new LinkedHashSet<String>();
        for (Object db : annotationDbMap.values()) {
            addSpireEnumClasses(getAnnotationIndex, spireEnumName, db, enumClassNames);
        }
        addPrepackagedJarSpireEnumClasses(loader, annotationDbClass, getAnnotationIndex, spireEnumName, enumClassNames);
        return enumClassNames;
    }

    private static void addPrepackagedJarSpireEnumClasses(
            ClassLoader loader,
            Class<?> annotationDbClass,
            Method getAnnotationIndex,
            String spireEnumName,
            Set<String> enumClassNames
    ) throws Throwable {
        Set<String> cachedEnumClassNames = readCachedPrepackagedJarSpireEnumClasses();
        if (cachedEnumClassNames != null) {
            enumClassNames.addAll(cachedEnumClassNames);
            log("Loaded cached MTS main jar SpireEnum entries: classes=" + cachedEnumClassNames.size());
            return;
        }

        Class<?> launcher = Class.forName(PREPACKAGED_LAUNCHER, false, loader);
        URL launcherUrl = launcher.getProtectionDomain().getCodeSource().getLocation();
        Object db = annotationDbClass.getDeclaredConstructor().newInstance();
        annotationDbClass.getMethod("setScanClassAnnotations", boolean.class).invoke(db, false);
        annotationDbClass.getMethod("setScanMethodAnnotations", boolean.class).invoke(db, false);
        annotationDbClass.getMethod("scanArchives", URL[].class).invoke(db, (Object) new URL[]{launcherUrl});
        addSpireEnumClasses(getAnnotationIndex, spireEnumName, db, enumClassNames);
    }

    private static Set<String> readCachedPrepackagedJarSpireEnumClasses() {
        File cacheRoot = new File(System.getProperty(PROPERTY_JAR, "")).getParentFile();
        if (cacheRoot == null) {
            return null;
        }
        try {
            return MtsPatchMainJarSpireEnumCache.read(cacheRoot);
        } catch (Throwable error) {
            log("Cached MTS main jar SpireEnum index missed, scanning patched jar: " + error);
            return null;
        }
    }

    private static void addSpireEnumClasses(
            Method getAnnotationIndex,
            String spireEnumName,
            Object db,
            Set<String> enumClassNames
    ) throws Throwable {
        if (db == null) {
            return;
        }
        Object rawIndex = getAnnotationIndex.invoke(db);
        if (!(rawIndex instanceof Map)) {
            return;
        }
        Object rawClasses = ((Map<?, ?>) rawIndex).get(spireEnumName);
        if (!(rawClasses instanceof Iterable)) {
            return;
        }
        for (Object item : (Iterable<?>) rawClasses) {
            if (item instanceof String) {
                enumClassNames.add((String) item);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static int bustEnums(ClassLoader loader, Set<String> enumClassNames) throws Throwable {
        Class<?> patcher = Class.forName(MTS_PATCHER, false, loader);
        Field enumBusterMapField = patcher.getDeclaredField("enumBusterMap");
        enumBusterMapField.setAccessible(true);
        Map<Class<?>, Object> enumBusterMap = (Map<Class<?>, Object>) enumBusterMapField.get(null);

        Class<? extends Annotation> spireEnumAnnotation =
                (Class<? extends Annotation>) Class.forName(SPIRE_ENUM, false, loader);
        Method spireEnumName = spireEnumAnnotation.getMethod("name");
        Class<?> enumBusterClass = Class.forName(ENUM_BUSTER_REFLECT, true, loader);
        Constructor<?> enumBusterConstructor = enumBusterClass.getConstructor(ClassLoader.class, Class.class);
        Method make = enumBusterClass.getMethod("make", String.class);
        Method addByValue = enumBusterClass.getMethod("addByValue", Enum.class);
        Method setStaticFinalField = Class.forName(REFLECTION_HELPER, true, loader)
                .getMethod("setStaticFinalField", Field.class, Object.class);

        int fieldCount = 0;
        for (String className : enumClassNames) {
            Class<?> holder = loader.loadClass(className);
            Field[] fields = holder.getDeclaredFields();
            for (Field field : fields) {
                Annotation annotation = field.getDeclaredAnnotation(spireEnumAnnotation);
                if (annotation == null) {
                    continue;
                }
                String enumName = field.getName();
                String configuredName = String.valueOf(spireEnumName.invoke(annotation));
                if (!configuredName.isEmpty()) {
                    enumName = configuredName;
                }

                Class<?> enumType = field.getType();
                Object enumBuster = enumBusterMap.get(enumType);
                if (enumBuster == null) {
                    enumBuster = enumBusterConstructor.newInstance(loader, enumType);
                    enumBusterMap.put(enumType, enumBuster);
                }
                Object enumValue = make.invoke(enumBuster, enumName);
                addByValue.invoke(enumBuster, enumValue);
                try {
                    setStaticFinalField.invoke(null, enumType.getField(enumName), enumValue);
                } catch (NoSuchFieldException ignored) {
                }
                field.setAccessible(true);
                field.set(null, enumValue);
                fieldCount++;
            }
        }
        return fieldCount;
    }

    private static void prepareAnnotationDatabase(ClassLoader loader, Object modInfos) throws Throwable {
        long startedAtNs = System.nanoTime();
        File cacheRoot = new File(System.getProperty(PROPERTY_JAR, "")).getParentFile();
        if (cacheRoot != null) {
            try {
                // Called for its side effect of populating Patcher.annotationDBMap,
                // which the SpireEnum pass in bustPrepackagedEnumsFromCache reads
                // afterwards. It deliberately does not collect patch sets: their only
                // consumer is Patcher.injectPatches, which must not run on a cache hit
                // because the cached jar already carries the injected bytecode.
                MtsPatchAnnotationDbCache.restoreIntoPatcher(loader, cacheRoot, resolvePackageDir(), modInfos);
                log("Prepared cached MTS annotation DB from cache: mods=" + Array.getLength(modInfos));
                logElapsed("Prepared cached MTS annotation DB", startedAtNs);
                return;
            } catch (Throwable error) {
                log("Cached MTS annotation DB restore missed, scanning mods: " + error);
            }
        }

        Class<?> patcher = Class.forName(MTS_PATCHER, false, loader);
        Method findPatches = patcher.getMethod("findPatches", modInfos.getClass());
        try {
            findPatches.invoke(null, (Object) modInfos);
            log("Prepared cached MTS annotation DB: mods=" + Array.getLength(modInfos));
            logElapsed("Prepared cached MTS annotation DB", startedAtNs);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            throw cause == null ? error : cause;
        }
    }

    public static void preparePrepackagedPackageUrls() throws Throwable {
        long startedAtNs = System.nanoTime();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = MtsPatchCacheBootstrap.class.getClassLoader();
        }

        Class<?> mtsLoader = Class.forName(MTS_LOADER, false, loader);
        Object modInfos = mtsLoader.getField("MODINFOS").get(null);
        if (modInfos == null || !modInfos.getClass().isArray()) {
            log("Skipping cached MTS package URL preparation: no mod infos");
            return;
        }

        File packageDir = resolvePackageDir();
        Class<?> modInfoClass = Class.forName(MTS_MOD_INFO, false, loader);
        java.lang.reflect.Field jarUrlField = modInfoClass.getField("jarURL");
        int prepared = 0;
        for (int i = 0; i < Array.getLength(modInfos); i++) {
            Object modInfo = Array.get(modInfos, i);
            Object rawUrl = jarUrlField.get(modInfo);
            if (!(rawUrl instanceof URL)) {
                continue;
            }
            String fileName = packageFileName((URL) rawUrl);
            if (fileName.length() == 0) {
                continue;
            }
            jarUrlField.set(modInfo, new File(packageDir, fileName).toURI().toURL());
            prepared++;
        }
        log("Prepared cached MTS package URLs: mods=" + prepared + " packageDir=" + packageDir.getAbsolutePath());
        logElapsed("Prepared cached MTS package URLs", startedAtNs);
    }

    private static void markSettingsAsModded(ClassLoader loader) throws Throwable {
        try {
            Class<?> settings = Class.forName(GAME_SETTINGS, false, loader);
            settings.getDeclaredField("isModded").set(null, Boolean.TRUE);
            settings.getDeclaredField("isDev").set(null, Boolean.FALSE);
        } catch (ClassNotFoundException ignored) {
        } catch (NoSuchFieldException ignored) {
        }
    }

    private static void applyGameWorkingDirectory() {
        String rawGameDir = System.getProperty(PROPERTY_GAME_DIR, "").trim();
        if (rawGameDir.length() == 0) {
            return;
        }
        String gameDir = new File(rawGameDir).getAbsolutePath();
        System.setProperty("user.dir", gameDir);
        log("Using game working directory for cached MTS launch: " + gameDir);
    }

    private static String packageFileName(URL url) {
        try {
            return new File(url.toURI()).getName();
        } catch (Throwable ignored) {
            String path = url.getPath();
            int index = path.lastIndexOf('/');
            return index >= 0 ? path.substring(index + 1) : path;
        }
    }

    private static void invokeCachedLauncher(File cachedJar, File baseJar, File packageDir, String[] args) throws Throwable {
        long urlStartNs = System.nanoTime();
        URL[] urls = buildCacheUrls(cachedJar, baseJar, packageDir);
        logElapsed("Built cached MTS URL classpath entries=" + urls.length, urlStartNs);
        long loaderStartNs = System.nanoTime();
        ChildFirstJarClassLoader loader = new ChildFirstJarClassLoader(
                urls,
                MtsPatchCacheBootstrap.class.getClassLoader()
        );
        logElapsed("Created cached MTS classloader", loaderStartNs);
        Thread thread = Thread.currentThread();
        ClassLoader previousContextLoader = thread.getContextClassLoader();
        String previousUserDir = System.getProperty("user.dir");
        try {
            thread.setContextClassLoader(loader);
            File cacheRoot = cachedJar.getParentFile();
            if (cacheRoot != null) {
                System.setProperty("user.dir", cacheRoot.getAbsolutePath());
            }
            long launcherLoadStartNs = System.nanoTime();
            Class<?> launcher = Class.forName(PREPACKAGED_LAUNCHER, true, loader);
            logElapsed("Loaded cached MTS prepackaged launcher", launcherLoadStartNs);
            long methodLookupStartNs = System.nanoTime();
            Method main = launcher.getMethod("main", String[].class);
            logElapsed("Resolved cached MTS launcher main", methodLookupStartNs);
            log("Invoking cached MTS prepackaged launcher");
            try {
                main.invoke(null, (Object) args);
            } catch (Throwable gameFailure) {
                // Everything above this point can still be retried by the normal
                // ModTheSpire path. Once the launcher itself is running, the JVM has
                // taken static MTS state, loaded the game classes, and possibly opened
                // a window, so a second patch-and-launch pass in the same JVM cannot
                // succeed. Mark the failure so the caller reports it instead of
                // silently falling back.
                Throwable cause = gameFailure instanceof InvocationTargetException
                        ? gameFailure.getCause()
                        : gameFailure;
                throw new CachedGameLaunchFailure(cause == null ? gameFailure : cause);
            }
        } finally {
            if (previousUserDir == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", previousUserDir);
            }
            thread.setContextClassLoader(previousContextLoader);
        }
    }

    private static URL[] buildCacheUrls(File cachedJar, File baseJar, File packageDir) throws Exception {
        List<URL> urls = new ArrayList<URL>();
        urls.add(cachedJar.toURI().toURL());
        File[] files = packageDir.isDirectory() ? packageDir.listFiles() : null;
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    urls.add(file.toURI().toURL());
                }
            }
        }
        if (baseJar.isFile()) {
            urls.add(baseJar.toURI().toURL());
        }
        return urls.toArray(new URL[urls.size()]);
    }

    private static void log(String message) {
        System.out.println("[Amethyst] " + message);
    }

    private static void logElapsed(String message, long startedAtNs) {
        long elapsedMs = (System.nanoTime() - startedAtNs) / 1000000L;
        log(message + " took " + elapsedMs + "ms");
    }

    static final class ChildFirstJarClassLoader extends URLClassLoader {
        static {
            // Without this the JVM serializes every loadClass call on a single lock.
            // Mod scanner threads, BaseMod, and the GDX asset threads all load
            // classes concurrently through this loader, so a single lock is both a
            // startup bottleneck and a deadlock risk once a parent-first delegation
            // happens while another thread holds the parent's lock.
            registerAsParallelCapable();
        }

        ChildFirstJarClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = isParentFirst(name) ? loadParentFirst(name) : loadChildFirst(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private Class<?> loadParentFirst(String name) throws ClassNotFoundException {
            try {
                return super.loadClass(name, false);
            } catch (ClassNotFoundException parentMiss) {
                // The reserved namespaces are not guaranteed to be complete in the
                // parent. `io.stamethyst.bridge.FirstPersonGyroBridge`, for example,
                // ships inside the gdx patch that is merged into the cached jar and
                // has never been on the launch classpath. Falling back keeps the
                // namespace list from turning into a hardcoded per-class exception.
                try {
                    return findClass(name);
                } catch (ClassNotFoundException childMiss) {
                    throw parentMiss;
                }
            }
        }

        private Class<?> loadChildFirst(String name) throws ClassNotFoundException {
            try {
                return findClass(name);
            } catch (ClassNotFoundException childMiss) {
                return super.loadClass(name, false);
            }
        }

        @Override
        public URL getResource(String name) {
            // Class lookup is child-first, so resource lookup has to match. Otherwise
            // the parent's unpatched ModTheSpire.jar can answer for a resource whose
            // class-side counterpart came from the cached patched jar.
            URL childResource = findResource(name);
            if (childResource != null) {
                return childResource;
            }
            return super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            List<URL> ordered = new ArrayList<URL>();
            addAll(ordered, findResources(name));
            ClassLoader parent = getParent();
            if (parent != null) {
                addAll(ordered, parent.getResources(name));
            }
            return Collections.enumeration(ordered);
        }

        private static void addAll(List<URL> target, Enumeration<URL> source) {
            if (source == null) {
                return;
            }
            while (source.hasMoreElements()) {
                URL url = source.nextElement();
                if (url != null && !target.contains(url)) {
                    target.add(url);
                }
            }
        }

        /**
         * Namespaces that must resolve to a single class identity across the parent
         * and the cached jars. Anything loaded on both sides would otherwise produce
         * two distinct Class objects and fail with ClassCastException the moment an
         * instance crosses the boundary.
         *
         * ModTheSpire's own classes are deliberately absent: the cached jar carries
         * the patched copies and those are the ones the game must run.
         */
        private boolean isParentFirst(String name) {
            return startsWithPackage(name, "java") ||
                    startsWithPackage(name, "javax") ||
                    startsWithPackage(name, "sun") ||
                    startsWithPackage(name, "jdk") ||
                    startsWithPackage(name, "org.w3c.dom") ||
                    startsWithPackage(name, "org.xml.sax") ||
                    startsWithPackage(name, "org.ietf.jgss") ||
                    startsWithPackage(name, "com.badlogic.gdx") ||
                    startsWithPackage(name, "org.lwjgl") ||
                    startsWithPackage(name, "org.apache.logging.log4j") ||
                    startsWithPackage(name, "org.slf4j") ||
                    startsWithPackage(name, "io.stamethyst.bridge");
        }

        /**
         * Matches on a package boundary so an unrelated mod class such as
         * `javafx.Thing` or `sunset.Mod` is not mistaken for a reserved namespace.
         */
        private static boolean startsWithPackage(String name, String packageName) {
            return name.startsWith(packageName) &&
                    name.length() > packageName.length() &&
                    name.charAt(packageName.length()) == '.';
        }
    }
}
