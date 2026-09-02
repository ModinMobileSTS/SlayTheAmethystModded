package io.stamethyst.bridge;

import org.junit.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MtsPatchCacheBootstrapTest {
    private static final String PROP_ENABLED = "amethyst.mts.patch_cache.enabled";
    private static final String PROP_CURRENT = "amethyst.mts.patch_cache.current";
    private static final String PROP_JAR = "amethyst.mts.patch_cache.jar";
    private static final String PROP_BASE_JAR = "amethyst.mts.patch_cache.base_jar";
    private static final String PROP_MARKER = "amethyst.mts.patch_cache.marker";
    private static final String PROP_PACKAGE_DIR = "amethyst.mts.patch_cache.package_dir";
    private static final String PROP_EXPECTED = "amethyst.mts.patch_cache.expected";
    private static final String PROP_GAME_DIR = "amethyst.mts.patch_cache.game_dir";
    private static final String PROP_LAUNCHED = "amethyst.test.patch_cache.launched";
    private static final String PROP_LAUNCHED_DIR = "amethyst.test.patch_cache.user_dir";
    private static final String PROP_BASE_VERSION = "amethyst.test.patch_cache.base_version";
    private static final String PROP_PREPARED = "amethyst.test.patch_cache.prepared";
    private static final String PROP_INITIALIZED = "amethyst.test.patch_cache.initialized";
    private static final String PROP_INITIALIZED_DIR = "amethyst.test.patch_cache.initialized_user_dir";
    private static final String PROP_IS_MODDED = "amethyst.test.patch_cache.is_modded";
    private static final String PROP_IS_DEV = "amethyst.test.patch_cache.is_dev";
    private static final String PROP_SCAN_ARCHIVES = "amethyst.test.patch_cache.scan_archives";

    @Test
    public void launchIfCurrent_invokesCachedPrepackagedLauncher() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-bootstrap-").toFile();
        try {
            File cachedJar = buildFakePrepackagedJar(root);
            File marker = new File(root, ".mts_patch_cache");
            File packageDir = new File(root, "package");
            File gameDir = new File(root, "game");
            Files.write(marker.toPath(), "expected\n".getBytes(StandardCharsets.UTF_8));
            writeFakePackageJar(packageDir);

            setCacheProperties(cachedJar, marker, packageDir, "expected", gameDir);

            assertTrue(MtsPatchCacheBootstrap.launchIfCurrent());
            assertEquals("0", System.getProperty(PROP_LAUNCHED));
            assertEquals(root.getAbsolutePath(), System.getProperty(PROP_LAUNCHED_DIR));
        } finally {
            clearCacheProperties();
            deleteRecursively(root);
        }
    }

    @Test
    public void launchIfCurrent_loadsBaseGameClassesFromBaseJar() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-bootstrap-base-").toFile();
        try {
            File baseJar = buildFakeBaseGameJar(root);
            File cachedJar = buildFakePrepackagedJar(root, baseJar);
            File marker = new File(root, ".mts_patch_cache");
            File packageDir = new File(root, "package");
            File gameDir = new File(root, "game");
            Files.write(marker.toPath(), "expected\n".getBytes(StandardCharsets.UTF_8));
            writeFakePackageJar(packageDir);

            setCacheProperties(cachedJar, baseJar, marker, packageDir, "expected", gameDir);

            assertTrue(MtsPatchCacheBootstrap.launchIfCurrent());
            assertEquals("base-game", System.getProperty(PROP_BASE_VERSION));
        } finally {
            clearCacheProperties();
            deleteRecursively(root);
        }
    }

    @Test
    public void launchIfCurrent_prefersPackageJarClassesOverBaseJar() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-bootstrap-package-first-").toFile();
        try {
            File baseJar = buildFakeBaseGameJar(root);
            File cachedJar = buildFakePrepackagedJar(root, baseJar);
            File marker = new File(root, ".mts_patch_cache");
            File packageDir = new File(root, "package");
            File gameDir = new File(root, "game");
            Files.write(marker.toPath(), "expected\n".getBytes(StandardCharsets.UTF_8));
            writeFakePackageJarWithBaseGameOverride(root, packageDir);

            setCacheProperties(cachedJar, baseJar, marker, packageDir, "expected", gameDir);

            assertTrue(MtsPatchCacheBootstrap.launchIfCurrent());
            assertEquals("package-game", System.getProperty(PROP_BASE_VERSION));
        } finally {
            clearCacheProperties();
            deleteRecursively(root);
        }
    }

    @Test
    public void launchIfCurrent_returnsFalseWhenMarkerDoesNotMatch() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-bootstrap-miss-").toFile();
        try {
            File cachedJar = buildFakePrepackagedJar(root);
            File marker = new File(root, ".mts_patch_cache");
            File packageDir = new File(root, "package");
            File gameDir = new File(root, "game");
            Files.write(marker.toPath(), "old\n".getBytes(StandardCharsets.UTF_8));
            writeFakePackageJar(packageDir);

            setCacheProperties(cachedJar, marker, packageDir, "expected", gameDir);

            assertFalse(MtsPatchCacheBootstrap.launchIfCurrent());
        } finally {
            clearCacheProperties();
            deleteRecursively(root);
        }
    }

    @Test
    public void launchIfCurrent_doesNotFallBackAfterTheCachedGameAlreadyStarted() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-bootstrap-late-crash-").toFile();
        try {
            File cachedJar = buildFakePrepackagedJarThatThrowsAfterGameStart(root);
            File marker = new File(root, ".mts_patch_cache");
            File packageDir = new File(root, "package");
            File gameDir = new File(root, "game");
            Files.write(marker.toPath(), "expected\n".getBytes(StandardCharsets.UTF_8));
            writeFakePackageJar(packageDir);

            setCacheProperties(cachedJar, marker, packageDir, "expected", gameDir);

            // The launcher reached the game and only then threw. Returning false here would
            // make the patched Loader.runMods continue into a full second patch-and-launch
            // pass in a JVM that has already booted the game, instead of surfacing the crash.
            RuntimeException propagated = null;
            try {
                MtsPatchCacheBootstrap.launchIfCurrent();
            } catch (RuntimeException error) {
                propagated = error;
            }
            assertEquals("0", System.getProperty(PROP_LAUNCHED));
            assertNotNull("A crash after game start must not trigger the patch fallback", propagated);
            assertEquals("crash after the game started", propagated.getCause().getMessage());
        } finally {
            clearCacheProperties();
            deleteRecursively(root);
        }
    }

    @Test
    public void preparePrepackagedLaunch_marksSettingsAppliesGameDirectoryPreparesAnnotationsAndInitializesWorkshopInfos() throws Throwable {
        File root = Files.createTempDirectory("mts-patch-cache-bootstrap-prepare-").toFile();
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        String previousUserDir = System.getProperty("user.dir");
        try {
            File jar = buildFakeMtsRuntimeJar(root);
            File gameDir = new File(root, "game");
            assertTrue(gameDir.mkdirs());
            URLClassLoader cachedLoader = new URLClassLoader(
                    new java.net.URL[]{jar.toURI().toURL()},
                    null
            );
            Thread.currentThread().setContextClassLoader(cachedLoader);
            System.setProperty(PROP_GAME_DIR, gameDir.getAbsolutePath());
            System.clearProperty(PROP_PREPARED);

            Class<?> mtsLoader = cachedLoader.loadClass("com.evacipated.cardcrawl.modthespire.Loader");
            assertNull(mtsLoader.getMethod("getWorkshopInfos").invoke(null));

            MtsPatchCacheBootstrap.preparePrepackagedLaunch();

            assertEquals(gameDir.getAbsolutePath(), System.getProperty("user.dir"));
            assertEquals("2", System.getProperty(PROP_PREPARED));
            assertNull(System.getProperty(PROP_INITIALIZED));
            assertNull(System.getProperty(PROP_INITIALIZED_DIR));
            Object workshopInfos = mtsLoader.getMethod("getWorkshopInfos").invoke(null);
            assertTrue(workshopInfos instanceof java.util.List);
            assertTrue(((java.util.List<?>) workshopInfos).isEmpty());
            Class<?> settings = cachedLoader.loadClass("com.megacrit.cardcrawl.core.Settings");
            assertTrue(settings.getField("isModded").getBoolean(null));
            assertFalse(settings.getField("isDev").getBoolean(null));
            cachedLoader.close();
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
            if (previousUserDir == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", previousUserDir);
            }
            System.clearProperty(PROP_PREPARED);
            System.clearProperty(PROP_INITIALIZED);
            System.clearProperty(PROP_INITIALIZED_DIR);
            System.clearProperty(PROP_IS_MODDED);
            System.clearProperty(PROP_IS_DEV);
            System.clearProperty(PROP_GAME_DIR);
            deleteRecursively(root);
        }
    }

    @Test
    public void preparePrepackagedPackageUrls_rewritesModInfoUrlsToCachePackageDir() throws Throwable {
        File root = Files.createTempDirectory("mts-patch-cache-bootstrap-package-urls-").toFile();
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        try {
            File jar = buildFakeMtsRuntimeJar(root);
            File packageDir = new File(root, "cache/package");
            assertTrue(packageDir.mkdirs());
            URLClassLoader cachedLoader = new URLClassLoader(
                    new java.net.URL[]{jar.toURI().toURL()},
                    null
            );
            Thread.currentThread().setContextClassLoader(cachedLoader);
            System.setProperty(PROP_PACKAGE_DIR, packageDir.getAbsolutePath());

            MtsPatchCacheBootstrap.preparePrepackagedPackageUrls();

            Class<?> loader = cachedLoader.loadClass("com.evacipated.cardcrawl.modthespire.Loader");
            Object modInfos = loader.getField("MODINFOS").get(null);
            Object firstModInfo = java.lang.reflect.Array.get(modInfos, 0);
            java.net.URL jarUrl = (java.net.URL) firstModInfo.getClass().getField("jarURL").get(firstModInfo);
            assertEquals(
                    new File(packageDir, "ExampleMod-modded.jar").toURI().toURL(),
                    jarUrl
            );
            cachedLoader.close();
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
            System.clearProperty(PROP_PACKAGE_DIR);
            deleteRecursively(root);
        }
    }

    @Test
    public void preparePrepackagedPackageUrls_usesExistingPackageJarForBareJsonAmpersandEscape() throws Throwable {
        File root = Files.createTempDirectory("mts-patch-cache-bootstrap-package-escaped-url-").toFile();
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        try {
            File jar = buildFakeMtsRuntimeJar(
                    root,
                    "file:/launch/package/Floating%20u0026%20Foil%20Cards-modded.jar"
            );
            File packageDir = new File(root, "cache/package");
            assertTrue(packageDir.mkdirs());
            File expectedPackageJar = new File(packageDir, "Floating & Foil Cards-modded.jar");
            assertTrue(expectedPackageJar.createNewFile());
            URLClassLoader cachedLoader = new URLClassLoader(
                    new java.net.URL[]{jar.toURI().toURL()},
                    null
            );
            Thread.currentThread().setContextClassLoader(cachedLoader);
            System.setProperty(PROP_PACKAGE_DIR, packageDir.getAbsolutePath());

            MtsPatchCacheBootstrap.preparePrepackagedPackageUrls();

            Class<?> loader = cachedLoader.loadClass("com.evacipated.cardcrawl.modthespire.Loader");
            Object modInfos = loader.getField("MODINFOS").get(null);
            Object firstModInfo = java.lang.reflect.Array.get(modInfos, 0);
            java.net.URL jarUrl = (java.net.URL) firstModInfo.getClass().getField("jarURL").get(firstModInfo);
            assertEquals(expectedPackageJar.toURI().toURL(), jarUrl);
            cachedLoader.close();
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
            System.clearProperty(PROP_PACKAGE_DIR);
            deleteRecursively(root);
        }
    }

    @Test
    public void preparePrepackagedLaunch_restoresAnnotationDbCacheWithoutScanningMods() throws Throwable {
        File root = Files.createTempDirectory("mts-patch-cache-bootstrap-annotation-cache-").toFile();
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        String previousUserDir = System.getProperty("user.dir");
        try {
            File jar = buildFakeAnnotationRuntimeJar(root);
            File cacheRoot = new File(root, "cache");
            File cachedJar = new File(cacheRoot, "desktop-1.0-modded.jar");
            File packageDir = new File(cacheRoot, "package");
            File gameDir = new File(root, "game");
            assertTrue(cacheRoot.mkdirs());
            assertTrue(packageDir.mkdirs());
            assertTrue(gameDir.mkdirs());
            assertTrue(cachedJar.createNewFile());

            URLClassLoader cachedLoader = new URLClassLoader(
                    new java.net.URL[]{jar.toURI().toURL()},
                    null
            );
            Thread.currentThread().setContextClassLoader(cachedLoader);
            System.setProperty(PROP_JAR, cachedJar.getAbsolutePath());
            System.setProperty(PROP_PACKAGE_DIR, packageDir.getAbsolutePath());
            System.setProperty(PROP_GAME_DIR, gameDir.getAbsolutePath());
            System.clearProperty(PROP_PREPARED);

            Class<?> annotationDb = cachedLoader.loadClass("org.scannotation.AnnotationDB");
            Object db = annotationDb.getDeclaredConstructor().newInstance();
            @SuppressWarnings("unchecked")
            Map<String, java.util.Set<String>> annotationIndex =
                    (Map<String, java.util.Set<String>>) annotationDb.getMethod("getAnnotationIndex").invoke(db);
            annotationIndex.put(
                    "com.evacipated.cardcrawl.modthespire.lib.SpirePatch",
                    Collections.singleton("example.Patch")
            );
            Class<?> patcher = cachedLoader.loadClass("com.evacipated.cardcrawl.modthespire.Patcher");
            @SuppressWarnings("unchecked")
            Map<URL, Object> annotationDbMap =
                    (Map<URL, Object>) patcher.getField("annotationDBMap").get(null);
            annotationDbMap.put(new File(packageDir, "ExampleMod-modded.jar").toURI().toURL(), db);
            MtsPatchAnnotationDbCache.writeFromPatcher(cachedLoader, cacheRoot, packageDir);
            annotationDbMap.clear();

            MtsPatchCacheBootstrap.preparePrepackagedLaunch();

            assertNull(System.getProperty(PROP_PREPARED));
            @SuppressWarnings("unchecked")
            Map<URL, Object> restoredAnnotationDbMap =
                    (Map<URL, Object>) patcher.getField("annotationDBMap").get(null);
            assertEquals(1, restoredAnnotationDbMap.size());
            Object restoredDb = restoredAnnotationDbMap.values().iterator().next();
            @SuppressWarnings("unchecked")
            Map<String, java.util.Set<String>> restoredIndex =
                    (Map<String, java.util.Set<String>>) annotationDb.getMethod("getAnnotationIndex").invoke(restoredDb);
            assertTrue(restoredIndex.get("com.evacipated.cardcrawl.modthespire.lib.SpirePatch").contains("example.Patch"));
            cachedLoader.close();
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
            if (previousUserDir == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", previousUserDir);
            }
            System.clearProperty(PROP_PREPARED);
            System.clearProperty(PROP_GAME_DIR);
            System.clearProperty(PROP_JAR);
            System.clearProperty(PROP_PACKAGE_DIR);
            deleteRecursively(root);
        }
    }

    @Test
    public void bustPrepackagedEnumsFromCache_usesRestoredAnnotationDbWithoutScanningMods() throws Throwable {
        File root = Files.createTempDirectory("mts-patch-cache-bootstrap-enum-cache-").toFile();
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        String previousUserDir = System.getProperty("user.dir");
        try {
            File jar = buildFakeAnnotationRuntimeJar(root);
            File cacheRoot = new File(root, "cache");
            File cachedJar = new File(cacheRoot, "desktop-1.0-modded.jar");
            File packageDir = new File(cacheRoot, "package");
            File gameDir = new File(root, "game");
            assertTrue(cacheRoot.mkdirs());
            assertTrue(packageDir.mkdirs());
            assertTrue(gameDir.mkdirs());
            assertTrue(cachedJar.createNewFile());

            URLClassLoader cachedLoader = new URLClassLoader(
                    new java.net.URL[]{jar.toURI().toURL()},
                    null
            );
            Thread.currentThread().setContextClassLoader(cachedLoader);
            System.setProperty(PROP_JAR, cachedJar.getAbsolutePath());
            System.setProperty(PROP_PACKAGE_DIR, packageDir.getAbsolutePath());
            System.setProperty(PROP_GAME_DIR, gameDir.getAbsolutePath());
            System.clearProperty(PROP_PREPARED);

            Class<?> annotationDb = cachedLoader.loadClass("org.scannotation.AnnotationDB");
            Object db = annotationDb.getDeclaredConstructor().newInstance();
            @SuppressWarnings("unchecked")
            Map<String, java.util.Set<String>> annotationIndex =
                    (Map<String, java.util.Set<String>>) annotationDb.getMethod("getAnnotationIndex").invoke(db);
            annotationIndex.put(
                    "com.evacipated.cardcrawl.modthespire.lib.SpirePatch",
                    Collections.singleton("example.Patch")
            );
            annotationIndex.put(
                    "com.evacipated.cardcrawl.modthespire.lib.SpireEnum",
                    Collections.singleton("example.ExampleEnumHolder")
            );
            Class<?> patcher = cachedLoader.loadClass("com.evacipated.cardcrawl.modthespire.Patcher");
            @SuppressWarnings("unchecked")
            Map<URL, Object> annotationDbMap =
                    (Map<URL, Object>) patcher.getField("annotationDBMap").get(null);
            annotationDbMap.put(new File(packageDir, "ExampleMod-modded.jar").toURI().toURL(), db);
            MtsPatchAnnotationDbCache.writeFromPatcher(cachedLoader, cacheRoot, packageDir);
            annotationDbMap.clear();

            MtsPatchCacheBootstrap.preparePrepackagedLaunch();
            assertTrue(MtsPatchCacheBootstrap.bustPrepackagedEnumsFromCache());

            Class<?> holder = cachedLoader.loadClass("example.ExampleEnumHolder");
            Class<?> enumType = cachedLoader.loadClass("example.ExampleEnum");
            assertEquals(
                    enumType.getField("EXISTING").get(null),
                    holder.getField("NEW_ENUM").get(null)
            );
            assertNull(System.getProperty(PROP_PREPARED));
            cachedLoader.close();
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
            if (previousUserDir == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", previousUserDir);
            }
            System.clearProperty(PROP_PREPARED);
            System.clearProperty(PROP_GAME_DIR);
            System.clearProperty(PROP_JAR);
            System.clearProperty(PROP_PACKAGE_DIR);
            System.clearProperty(PROP_SCAN_ARCHIVES);
            deleteRecursively(root);
        }
    }

    @Test
    public void bustPrepackagedEnumsFromCache_usesMainJarEnumCacheWithoutScanningPatchedJar() throws Throwable {
        File root = Files.createTempDirectory("mts-patch-cache-bootstrap-main-enum-cache-").toFile();
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        String previousUserDir = System.getProperty("user.dir");
        try {
            File jar = buildFakeAnnotationRuntimeJar(root);
            File cacheRoot = new File(root, "cache");
            File cachedJar = new File(cacheRoot, "desktop-1.0-modded.jar");
            File packageDir = new File(cacheRoot, "package");
            File gameDir = new File(root, "game");
            assertTrue(cacheRoot.mkdirs());
            assertTrue(packageDir.mkdirs());
            assertTrue(gameDir.mkdirs());
            assertTrue(cachedJar.createNewFile());
            Files.write(
                    new File(cacheRoot, "main-jar-spire-enums.txt").toPath(),
                    ("schema=1\nexample.ExampleEnumHolder\n").getBytes(StandardCharsets.UTF_8)
            );

            URLClassLoader cachedLoader = new URLClassLoader(
                    new java.net.URL[]{jar.toURI().toURL()},
                    null
            );
            Thread.currentThread().setContextClassLoader(cachedLoader);
            System.setProperty(PROP_JAR, cachedJar.getAbsolutePath());
            System.setProperty(PROP_PACKAGE_DIR, packageDir.getAbsolutePath());
            System.setProperty(PROP_GAME_DIR, gameDir.getAbsolutePath());
            System.clearProperty(PROP_PREPARED);
            System.clearProperty(PROP_SCAN_ARCHIVES);

            Class<?> annotationDb = cachedLoader.loadClass("org.scannotation.AnnotationDB");
            Object db = annotationDb.getDeclaredConstructor().newInstance();
            @SuppressWarnings("unchecked")
            Map<String, java.util.Set<String>> annotationIndex =
                    (Map<String, java.util.Set<String>>) annotationDb.getMethod("getAnnotationIndex").invoke(db);
            annotationIndex.put(
                    "com.evacipated.cardcrawl.modthespire.lib.SpirePatch",
                    Collections.singleton("example.Patch")
            );
            Class<?> patcher = cachedLoader.loadClass("com.evacipated.cardcrawl.modthespire.Patcher");
            @SuppressWarnings("unchecked")
            Map<URL, Object> annotationDbMap =
                    (Map<URL, Object>) patcher.getField("annotationDBMap").get(null);
            annotationDbMap.put(new File(packageDir, "ExampleMod-modded.jar").toURI().toURL(), db);
            MtsPatchAnnotationDbCache.writeFromPatcher(cachedLoader, cacheRoot, packageDir);
            annotationDbMap.clear();

            MtsPatchCacheBootstrap.preparePrepackagedLaunch();
            assertTrue(MtsPatchCacheBootstrap.bustPrepackagedEnumsFromCache());

            Class<?> holder = cachedLoader.loadClass("example.ExampleEnumHolder");
            Class<?> enumType = cachedLoader.loadClass("example.ExampleEnum");
            assertEquals(
                    enumType.getField("EXISTING").get(null),
                    holder.getField("NEW_ENUM").get(null)
            );
            assertNull(System.getProperty(PROP_PREPARED));
            assertNull(System.getProperty(PROP_SCAN_ARCHIVES));
            cachedLoader.close();
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
            if (previousUserDir == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", previousUserDir);
            }
            System.clearProperty(PROP_PREPARED);
            System.clearProperty(PROP_GAME_DIR);
            System.clearProperty(PROP_JAR);
            System.clearProperty(PROP_PACKAGE_DIR);
            System.clearProperty(PROP_SCAN_ARCHIVES);
            deleteRecursively(root);
        }
    }

    @Test
    public void bustPrepackagedEnumsFromCache_fallsBackToPatchedJarScanWhenMainJarEnumCacheIsMissing() throws Throwable {
        File root = Files.createTempDirectory("mts-patch-cache-bootstrap-main-enum-fallback-").toFile();
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        String previousUserDir = System.getProperty("user.dir");
        try {
            File jar = buildFakeAnnotationRuntimeJar(root);
            File cacheRoot = new File(root, "cache");
            File cachedJar = new File(cacheRoot, "desktop-1.0-modded.jar");
            File packageDir = new File(cacheRoot, "package");
            File gameDir = new File(root, "game");
            assertTrue(cacheRoot.mkdirs());
            assertTrue(packageDir.mkdirs());
            assertTrue(gameDir.mkdirs());
            assertTrue(cachedJar.createNewFile());

            URLClassLoader cachedLoader = new URLClassLoader(
                    new java.net.URL[]{jar.toURI().toURL()},
                    null
            );
            Thread.currentThread().setContextClassLoader(cachedLoader);
            System.setProperty(PROP_JAR, cachedJar.getAbsolutePath());
            System.setProperty(PROP_PACKAGE_DIR, packageDir.getAbsolutePath());
            System.setProperty(PROP_GAME_DIR, gameDir.getAbsolutePath());
            System.clearProperty(PROP_PREPARED);
            System.clearProperty(PROP_SCAN_ARCHIVES);

            Class<?> annotationDb = cachedLoader.loadClass("org.scannotation.AnnotationDB");
            Object db = annotationDb.getDeclaredConstructor().newInstance();
            @SuppressWarnings("unchecked")
            Map<String, java.util.Set<String>> annotationIndex =
                    (Map<String, java.util.Set<String>>) annotationDb.getMethod("getAnnotationIndex").invoke(db);
            annotationIndex.put(
                    "com.evacipated.cardcrawl.modthespire.lib.SpirePatch",
                    Collections.singleton("example.Patch")
            );
            Class<?> patcher = cachedLoader.loadClass("com.evacipated.cardcrawl.modthespire.Patcher");
            @SuppressWarnings("unchecked")
            Map<URL, Object> annotationDbMap =
                    (Map<URL, Object>) patcher.getField("annotationDBMap").get(null);
            annotationDbMap.put(new File(packageDir, "ExampleMod-modded.jar").toURI().toURL(), db);
            MtsPatchAnnotationDbCache.writeFromPatcher(cachedLoader, cacheRoot, packageDir);
            annotationDbMap.clear();

            MtsPatchCacheBootstrap.preparePrepackagedLaunch();
            assertTrue(MtsPatchCacheBootstrap.bustPrepackagedEnumsFromCache());

            Class<?> holder = cachedLoader.loadClass("example.ExampleEnumHolder");
            Class<?> enumType = cachedLoader.loadClass("example.ExampleEnum");
            assertEquals(
                    enumType.getField("EXISTING").get(null),
                    holder.getField("NEW_ENUM").get(null)
            );
            assertNull(System.getProperty(PROP_PREPARED));
            assertEquals("1", System.getProperty(PROP_SCAN_ARCHIVES));
            cachedLoader.close();
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
            if (previousUserDir == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", previousUserDir);
            }
            System.clearProperty(PROP_PREPARED);
            System.clearProperty(PROP_GAME_DIR);
            System.clearProperty(PROP_JAR);
            System.clearProperty(PROP_PACKAGE_DIR);
            System.clearProperty(PROP_SCAN_ARCHIVES);
            deleteRecursively(root);
        }
    }

    @Test
    public void mainJarSpireEnumCache_writesScannedPatchedJarEnumIndex() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-main-enum-write-").toFile();
        try {
            File jar = buildFakeAnnotationRuntimeJar(root);
            File cacheRoot = new File(root, "cache");
            File cachedJar = new File(cacheRoot, "desktop-1.0-modded.jar");
            assertTrue(cacheRoot.mkdirs());
            assertTrue(cachedJar.createNewFile());
            System.clearProperty(PROP_SCAN_ARCHIVES);

            URLClassLoader cachedLoader = new URLClassLoader(
                    new java.net.URL[]{jar.toURI().toURL()},
                    null
            );
            try {
                MtsPatchMainJarSpireEnumCache.writeFromPatchedJar(cachedLoader, cacheRoot, cachedJar);
            } finally {
                cachedLoader.close();
            }

            java.util.List<String> lines = Files.readAllLines(
                    new File(cacheRoot, "main-jar-spire-enums.txt").toPath(),
                    StandardCharsets.UTF_8
            );
            assertEquals("schema=1", lines.get(0));
            assertTrue(lines.contains("example.ExampleEnumHolder"));
            assertEquals("1", System.getProperty(PROP_SCAN_ARCHIVES));
        } finally {
            System.clearProperty(PROP_SCAN_ARCHIVES);
            deleteRecursively(root);
        }
    }

    private static void setCacheProperties(File cachedJar, File marker, File packageDir, String expected, File gameDir) {
        System.setProperty(PROP_ENABLED, "true");
        System.setProperty(PROP_CURRENT, "true");
        System.setProperty(PROP_JAR, cachedJar.getAbsolutePath());
        System.clearProperty(PROP_BASE_JAR);
        System.setProperty(PROP_MARKER, marker.getAbsolutePath());
        System.setProperty(PROP_PACKAGE_DIR, packageDir.getAbsolutePath());
        System.setProperty(PROP_EXPECTED, expected);
        System.setProperty(PROP_GAME_DIR, gameDir.getAbsolutePath());
        System.clearProperty(PROP_LAUNCHED);
    }

    private static void setCacheProperties(
            File cachedJar,
            File baseJar,
            File marker,
            File packageDir,
            String expected,
            File gameDir
    ) {
        setCacheProperties(cachedJar, marker, packageDir, expected, gameDir);
        System.setProperty(PROP_BASE_JAR, baseJar.getAbsolutePath());
    }

    private static void clearCacheProperties() {
        System.clearProperty(PROP_ENABLED);
        System.clearProperty(PROP_CURRENT);
        System.clearProperty(PROP_JAR);
        System.clearProperty(PROP_BASE_JAR);
        System.clearProperty(PROP_MARKER);
        System.clearProperty(PROP_PACKAGE_DIR);
        System.clearProperty(PROP_EXPECTED);
        System.clearProperty(PROP_GAME_DIR);
        System.clearProperty(PROP_LAUNCHED);
        System.clearProperty(PROP_LAUNCHED_DIR);
        System.clearProperty(PROP_BASE_VERSION);
        System.clearProperty(PROP_SCAN_ARCHIVES);
    }

    private static File buildFakeMtsRuntimeJar(File root) throws Exception {
        return buildFakeMtsRuntimeJar(root, "file:/launch/package/ExampleMod-modded.jar");
    }

    private static File buildFakeMtsRuntimeJar(File root, String initialJarUrl) throws Exception {
        File sourceDir = new File(root, "runtime-src");
        File classDir = new File(root, "runtime-classes");
        File packageDir = new File(sourceDir, "com/evacipated/cardcrawl/modthespire");
        assertTrue(packageDir.mkdirs());
        assertTrue(classDir.mkdirs());
        String escapedInitialJarUrl = initialJarUrl.replace("\\", "\\\\").replace("\"", "\\\"");

        Files.write(
                new File(packageDir, "ModInfo.java").toPath(),
                (
                        "package com.evacipated.cardcrawl.modthespire;\n" +
                                "public final class ModInfo {\n" +
                                "  public java.net.URL jarURL;\n" +
                                "  public ModInfo() {\n" +
                                "    try {\n" +
                                "      this.jarURL = new java.net.URL(\"" + escapedInitialJarUrl + "\");\n" +
                                "    } catch (Exception e) {\n" +
                                "      throw new RuntimeException(e);\n" +
                                "    }\n" +
                                "  }\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
                new File(packageDir, "Loader.java").toPath(),
                (
                        "package com.evacipated.cardcrawl.modthespire;\n" +
                                "public final class Loader {\n" +
                                "  public static ModInfo[] MODINFOS = new ModInfo[] { new ModInfo(), new ModInfo() };\n" +
                                "  private static java.util.List WORKSHOP_INFOS;\n" +
                                "  public static java.util.List getWorkshopInfos() { return WORKSHOP_INFOS; }\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );
        File settingsDir = new File(sourceDir, "com/megacrit/cardcrawl/core");
        assertTrue(settingsDir.mkdirs());
        Files.write(
                new File(settingsDir, "Settings.java").toPath(),
                (
                        "package com.megacrit.cardcrawl.core;\n" +
                                "public final class Settings {\n" +
                                "  public static boolean isModded;\n" +
                                "  public static boolean isDev = true;\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
                new File(packageDir, "Patcher.java").toPath(),
                (
                        "package com.evacipated.cardcrawl.modthespire;\n" +
                                "public final class Patcher {\n" +
                                "  public static java.util.List findPatches(ModInfo[] infos) {\n" +
                                "    System.setProperty(\"" + PROP_PREPARED + "\", String.valueOf(infos.length));\n" +
                                "    return java.util.Collections.emptyList();\n" +
                                "  }\n" +
                                "  public static void initializeMods(ClassLoader loader, ModInfo[] infos) {\n" +
                                "    System.setProperty(\"" + PROP_INITIALIZED + "\", String.valueOf(infos.length));\n" +
                                "    System.setProperty(\"" + PROP_INITIALIZED_DIR + "\", System.getProperty(\"user.dir\"));\n" +
                                "    try {\n" +
                                "      Class<?> settings = loader.loadClass(\"com.megacrit.cardcrawl.core.Settings\");\n" +
                                "      System.setProperty(\"" + PROP_IS_MODDED + "\", String.valueOf(settings.getField(\"isModded\").getBoolean(null)));\n" +
                                "      System.setProperty(\"" + PROP_IS_DEV + "\", String.valueOf(settings.getField(\"isDev\").getBoolean(null)));\n" +
                                "    } catch (Exception e) {\n" +
                                "      throw new RuntimeException(e);\n" +
                                "    }\n" +
                                "  }\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is required for this test");
        }
        int compileResult = compiler.run(
                null,
                null,
                null,
                "-d",
                classDir.getAbsolutePath(),
                new File(packageDir, "ModInfo.java").getAbsolutePath(),
                new File(packageDir, "Loader.java").getAbsolutePath(),
                new File(packageDir, "Patcher.java").getAbsolutePath(),
                new File(settingsDir, "Settings.java").getAbsolutePath()
        );
        assertEquals(0, compileResult);

        File jar = new File(root, "fake-mts-runtime.jar");
        JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(jar));
        try {
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/ModInfo.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/Loader.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/Patcher.class");
            addClass(jarOut, classDir, "com/megacrit/cardcrawl/core/Settings.class");
        } finally {
            jarOut.close();
        }
        return jar;
    }

    private static File buildFakeAnnotationRuntimeJar(File root) throws Exception {
        File sourceDir = new File(root, "annotation-runtime-src");
        File classDir = new File(root, "annotation-runtime-classes");
        File mtsDir = new File(sourceDir, "com/evacipated/cardcrawl/modthespire");
        File libDir = new File(mtsDir, "lib");
        File annotationDir = new File(sourceDir, "org/scannotation");
        File settingsDir = new File(sourceDir, "com/megacrit/cardcrawl/core");
        File exampleDir = new File(sourceDir, "example");
        assertTrue(mtsDir.mkdirs());
        assertTrue(libDir.mkdirs());
        assertTrue(annotationDir.mkdirs());
        assertTrue(settingsDir.mkdirs());
        assertTrue(exampleDir.mkdirs());
        assertTrue(classDir.mkdirs());

        Files.write(
                new File(annotationDir, "AnnotationDB.java").toPath(),
                (
                        "package org.scannotation;\n" +
                                "public class AnnotationDB implements java.io.Serializable {\n" +
                                "  private final java.util.Map<String, java.util.Set<String>> annotationIndex = new java.util.LinkedHashMap<>();\n" +
                                "  public java.util.Map<String, java.util.Set<String>> getAnnotationIndex() { return annotationIndex; }\n" +
                                "  public void setScanClassAnnotations(boolean enabled) {}\n" +
                                "  public void setScanMethodAnnotations(boolean enabled) {}\n" +
                                "  public void scanArchives(java.net.URL[] urls) {\n" +
                                "    String value = System.getProperty(\"" + PROP_SCAN_ARCHIVES + "\", \"0\");\n" +
                                "    System.setProperty(\"" + PROP_SCAN_ARCHIVES + "\", String.valueOf(Integer.parseInt(value) + 1));\n" +
                                "    annotationIndex.put(\"com.evacipated.cardcrawl.modthespire.lib.SpireEnum\", java.util.Collections.singleton(\"example.ExampleEnumHolder\"));\n" +
                                "  }\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
                new File(mtsDir, "ModInfo.java").toPath(),
                (
                        "package com.evacipated.cardcrawl.modthespire;\n" +
                                "public final class ModInfo {\n" +
                                "  public java.net.URL jarURL;\n" +
                                "  public ModInfo() {\n" +
                                "    try { this.jarURL = new java.net.URL(\"file:/launch/package/ExampleMod-modded.jar\"); }\n" +
                                "    catch (Exception e) { throw new RuntimeException(e); }\n" +
                                "  }\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
                new File(mtsDir, "Loader.java").toPath(),
                (
                        "package com.evacipated.cardcrawl.modthespire;\n" +
                                "public final class Loader {\n" +
                                "  public static ModInfo[] MODINFOS = new ModInfo[] { new ModInfo() };\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
                new File(mtsDir, "Patcher.java").toPath(),
                (
                        "package com.evacipated.cardcrawl.modthespire;\n" +
                                "public final class Patcher {\n" +
                                "  public static java.util.Map<java.net.URL, org.scannotation.AnnotationDB> annotationDBMap = new java.util.LinkedHashMap<>();\n" +
                                "  private static java.util.Map<Class<?>, Object> enumBusterMap = new java.util.LinkedHashMap<>();\n" +
                                "  public static java.util.List findPatches(ModInfo[] infos) {\n" +
                                "    System.setProperty(\"" + PROP_PREPARED + "\", \"scanned\");\n" +
                                "    return java.util.Collections.emptyList();\n" +
                                "  }\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
                new File(mtsDir, "PackageJar.java").toPath(),
                (
                        "package com.evacipated.cardcrawl.modthespire;\n" +
                                "public final class PackageJar {\n" +
                                "  public static final class PrepackagedLauncher {}\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
                new File(mtsDir, "EnumBusterReflect.java").toPath(),
                (
                        "package com.evacipated.cardcrawl.modthespire;\n" +
                                "public final class EnumBusterReflect {\n" +
                                "  private final Class enumType;\n" +
                                "  public EnumBusterReflect(ClassLoader loader, Class enumType) { this.enumType = enumType; }\n" +
                                "  public Enum make(String name) { return Enum.valueOf(enumType, name); }\n" +
                                "  public void addByValue(Enum value) {}\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
                new File(mtsDir, "ReflectionHelper.java").toPath(),
                (
                        "package com.evacipated.cardcrawl.modthespire;\n" +
                                "public final class ReflectionHelper {\n" +
                                "  public static void setStaticFinalField(java.lang.reflect.Field field, Object value) {}\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
                new File(settingsDir, "Settings.java").toPath(),
                (
                        "package com.megacrit.cardcrawl.core;\n" +
                                "public final class Settings {\n" +
                                "  public static boolean isModded;\n" +
                                "  public static boolean isDev = true;\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );
        for (String name : new String[]{"SpirePatch", "SpirePatches", "SpirePatch2", "SpirePatches2"}) {
            Files.write(
                    new File(libDir, name + ".java").toPath(),
                    ("package com.evacipated.cardcrawl.modthespire.lib;\npublic final class " + name + " {}\n")
                            .getBytes(StandardCharsets.UTF_8)
            );
        }
        Files.write(
                new File(libDir, "SpireEnum.java").toPath(),
                (
                        "package com.evacipated.cardcrawl.modthespire.lib;\n" +
                                "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n" +
                                "@java.lang.annotation.Target(java.lang.annotation.ElementType.FIELD)\n" +
                                "public @interface SpireEnum { String name() default \"\"; }\n"
                ).getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
                new File(exampleDir, "ExampleEnum.java").toPath(),
                "package example;\npublic enum ExampleEnum { EXISTING }\n".getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
                new File(exampleDir, "ExampleEnumHolder.java").toPath(),
                (
                        "package example;\n" +
                                "public final class ExampleEnumHolder {\n" +
                                "  @com.evacipated.cardcrawl.modthespire.lib.SpireEnum(name=\"EXISTING\")\n" +
                                "  public static example.ExampleEnum NEW_ENUM;\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is required for this test");
        }
        int compileResult = compiler.run(
                null,
                null,
                null,
                "-d",
                classDir.getAbsolutePath(),
                new File(annotationDir, "AnnotationDB.java").getAbsolutePath(),
                new File(mtsDir, "ModInfo.java").getAbsolutePath(),
                new File(mtsDir, "Loader.java").getAbsolutePath(),
                new File(mtsDir, "Patcher.java").getAbsolutePath(),
                new File(mtsDir, "PackageJar.java").getAbsolutePath(),
                new File(mtsDir, "EnumBusterReflect.java").getAbsolutePath(),
                new File(mtsDir, "ReflectionHelper.java").getAbsolutePath(),
                new File(settingsDir, "Settings.java").getAbsolutePath(),
                new File(libDir, "SpirePatch.java").getAbsolutePath(),
                new File(libDir, "SpirePatches.java").getAbsolutePath(),
                new File(libDir, "SpirePatch2.java").getAbsolutePath(),
                new File(libDir, "SpirePatches2.java").getAbsolutePath(),
                new File(libDir, "SpireEnum.java").getAbsolutePath(),
                new File(exampleDir, "ExampleEnum.java").getAbsolutePath(),
                new File(exampleDir, "ExampleEnumHolder.java").getAbsolutePath()
        );
        assertEquals(0, compileResult);

        File jar = new File(root, "fake-annotation-runtime.jar");
        JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(jar));
        try {
            addClass(jarOut, classDir, "org/scannotation/AnnotationDB.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/ModInfo.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/Loader.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/Patcher.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/PackageJar.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/PackageJar$PrepackagedLauncher.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/EnumBusterReflect.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/ReflectionHelper.class");
            addClass(jarOut, classDir, "com/megacrit/cardcrawl/core/Settings.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/lib/SpirePatch.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/lib/SpirePatches.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/lib/SpirePatch2.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/lib/SpirePatches2.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/lib/SpireEnum.class");
            addClass(jarOut, classDir, "example/ExampleEnum.class");
            addClass(jarOut, classDir, "example/ExampleEnumHolder.class");
        } finally {
            jarOut.close();
        }
        return jar;
    }

    private static File buildFakePrepackagedJar(File root) throws Exception {
        return buildFakePrepackagedJar(root, null);
    }

    private static File buildFakePrepackagedJar(File root, File baseJar) throws Exception {
        File sourceDir = new File(root, "src");
        File classDir = new File(root, "classes");
        File packageDir = new File(sourceDir, "com/evacipated/cardcrawl/modthespire");
        assertTrue(packageDir.mkdirs());
        assertTrue(classDir.mkdirs());

        File source = new File(packageDir, "PackageJar.java");
        String baseVersionLine = baseJar == null
                ? ""
                : "      System.setProperty(\"" + PROP_BASE_VERSION + "\", com.megacrit.cardcrawl.core.CardCrawlGame.VERSION_NUM);\n";
        Files.write(
                source.toPath(),
                (
                        "package com.evacipated.cardcrawl.modthespire;\n" +
                                "public final class PackageJar {\n" +
                                "  public static final class PrepackagedLauncher {\n" +
                                "    public static void main(String[] args) {\n" +
                                "      System.setProperty(\"" + PROP_LAUNCHED + "\", String.valueOf(args.length));\n" +
                                "      System.setProperty(\"" + PROP_LAUNCHED_DIR + "\", System.getProperty(\"user.dir\"));\n" +
                                baseVersionLine +
                                "    }\n" +
                                "  }\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is required for this test");
        }
        int compileResult = baseJar == null
                ? compiler.run(null, null, null, "-d", classDir.getAbsolutePath(), source.getAbsolutePath())
                : compiler.run(
                null,
                null,
                null,
                "-classpath",
                baseJar.getAbsolutePath(),
                "-d",
                classDir.getAbsolutePath(),
                source.getAbsolutePath()
        );
        assertEquals(0, compileResult);

        File jar = new File(root, "desktop-1.0-modded.jar");
        JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(jar));
        try {
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/PackageJar.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/PackageJar$PrepackagedLauncher.class");
            jarOut.putNextEntry(new JarEntry("amethyst-cache-padding.bin"));
            byte[] padding = new byte[1024 * 1024];
            new Random(42L).nextBytes(padding);
            jarOut.write(padding);
            jarOut.closeEntry();
        } finally {
            jarOut.close();
        }
        return jar;
    }

    private static File buildFakeBaseGameJar(File root) throws Exception {
        File sourceDir = new File(root, "base-src");
        File classDir = new File(root, "base-classes");
        File packageDir = new File(sourceDir, "com/megacrit/cardcrawl/core");
        assertTrue(packageDir.mkdirs());
        assertTrue(classDir.mkdirs());

        File source = new File(packageDir, "CardCrawlGame.java");
        Files.write(
                source.toPath(),
                (
                        "package com.megacrit.cardcrawl.core;\n" +
                                "public final class CardCrawlGame {\n" +
                                "  public static String VERSION_NUM = \"base-game\";\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is required for this test");
        }
        int compileResult = compiler.run(null, null, null, "-d", classDir.getAbsolutePath(), source.getAbsolutePath());
        assertEquals(0, compileResult);

        File jar = new File(root, "desktop-1.0.jar");
        JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(jar));
        try {
            addClass(jarOut, classDir, "com/megacrit/cardcrawl/core/CardCrawlGame.class");
        } finally {
            jarOut.close();
        }
        return jar;
    }

    /**
     * Builds a cached launcher that signals it reached the game and then throws, which is
     * what a crash inside DesktopLauncher.main looks like from launchIfCurrent's frame.
     */
    private static File buildFakePrepackagedJarThatThrowsAfterGameStart(File root) throws Exception {
        File sourceDir = new File(root, "src");
        File classDir = new File(root, "classes");
        File packageDir = new File(sourceDir, "com/evacipated/cardcrawl/modthespire");
        assertTrue(packageDir.mkdirs());
        assertTrue(classDir.mkdirs());

        File source = new File(packageDir, "PackageJar.java");
        Files.write(
                source.toPath(),
                (
                        "package com.evacipated.cardcrawl.modthespire;\n" +
                                "public final class PackageJar {\n" +
                                "  public static final class PrepackagedLauncher {\n" +
                                "    public static void main(String[] args) {\n" +
                                "      System.setProperty(\"" + PROP_LAUNCHED + "\", String.valueOf(args.length));\n" +
                                "      throw new RuntimeException(\"crash after the game started\");\n" +
                                "    }\n" +
                                "  }\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is required for this test");
        }
        assertEquals(0, compiler.run(null, null, null, "-d", classDir.getAbsolutePath(), source.getAbsolutePath()));

        File jar = new File(root, "desktop-1.0-modded.jar");
        JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(jar));
        try {
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/PackageJar.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/PackageJar$PrepackagedLauncher.class");
            jarOut.putNextEntry(new JarEntry("amethyst-cache-padding.bin"));
            byte[] padding = new byte[1024 * 1024];
            new Random(42L).nextBytes(padding);
            jarOut.write(padding);
            jarOut.closeEntry();
        } finally {
            jarOut.close();
        }
        return jar;
    }

    private static void writeFakePackageJar(File packageDir) throws Exception {
        assertTrue(packageDir.mkdirs() || packageDir.isDirectory());
        File jar = new File(packageDir, "ExampleMod-modded.jar");
        JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(jar));
        try {
            jarOut.putNextEntry(new JarEntry("example/ExampleMod.class"));
            jarOut.write("mod".getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        } finally {
            jarOut.close();
        }
    }

    private static void writeFakePackageJarWithBaseGameOverride(File root, File packageDir) throws Exception {
        File sourceDir = new File(root, "package-src");
        File classDir = new File(root, "package-classes");
        File classPackageDir = new File(sourceDir, "com/megacrit/cardcrawl/core");
        assertTrue(classPackageDir.mkdirs());
        assertTrue(classDir.mkdirs());

        File source = new File(classPackageDir, "CardCrawlGame.java");
        Files.write(
                source.toPath(),
                (
                        "package com.megacrit.cardcrawl.core;\n" +
                                "public final class CardCrawlGame {\n" +
                                "  public static String VERSION_NUM = \"package-game\";\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is required for this test");
        }
        int compileResult = compiler.run(null, null, null, "-d", classDir.getAbsolutePath(), source.getAbsolutePath());
        assertEquals(0, compileResult);

        assertTrue(packageDir.mkdirs() || packageDir.isDirectory());
        JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(new File(packageDir, "ExampleMod-modded.jar")));
        try {
            addClass(jarOut, classDir, "com/megacrit/cardcrawl/core/CardCrawlGame.class");
        } finally {
            jarOut.close();
        }
    }

    private static void addClass(JarOutputStream jarOut, File classDir, String entryName) throws Exception {
        jarOut.putNextEntry(new JarEntry(entryName));
        Files.copy(new File(classDir, entryName).toPath(), jarOut);
        jarOut.closeEntry();
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
