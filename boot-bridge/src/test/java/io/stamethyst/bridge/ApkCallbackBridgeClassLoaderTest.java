package io.stamethyst.bridge;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** Exercise the actual cold MTS loader and the cached loader on the game JVM's Java 8 toolchain. */
public class ApkCallbackBridgeClassLoaderTest {
    private static final String BRIDGE = "org.lwjgl.glfw.CallbackBridge";

    @Test
    public void coldMtsLoaderUsesApkContractEvenWithLegacyLwjglInChildUrls() throws Exception {
        try (URLClassLoader parent = launchClasspath()) {
            ByteArrayOutputStream emptyJar = new ByteArrayOutputStream();
            try (JarOutputStream output = new JarOutputStream(emptyJar)) {
                output.finish();
            }
            Class<?> mtsLoader = parent.loadClass("com.evacipated.cardcrawl.modthespire.MTSClassLoader");
            try (URLClassLoader child = (URLClassLoader) mtsLoader
                    .getConstructor(InputStream.class, URL[].class, ClassLoader.class)
                    .newInstance(new ByteArrayInputStream(emptyJar.toByteArray()),
                            new URL[]{url("sts.legacyLwjglJar")}, parent)) {
                assertApkContract(parent, child);
            }
        }
    }

    @Test
    public void cachedMtsLoaderUsesSameApkContractIdentity() throws Exception {
        try (URLClassLoader parent = launchClasspath();
             URLClassLoader child = new MtsPatchCacheBootstrap.ChildFirstJarClassLoader(
                     new URL[]{url("sts.legacyLwjglJar")}, parent)) {
            assertApkContract(parent, child);
        }
    }

    private static URLClassLoader launchClasspath() throws Exception {
        return new URLClassLoader(new URL[]{
                url("sts.callbackBridgeJar"), url("sts.legacyLwjglJar"), url("sts.mtsJar")
        }, null);
    }

    private static void assertApkContract(ClassLoader parent, ClassLoader child) throws Exception {
        Class<?> bridge = child.loadClass(BRIDGE);
        assertSame(parent.loadClass(BRIDGE), bridge);
        assertEquals(url("sts.callbackBridgeJar"), bridge.getProtectionDomain().getCodeSource().getLocation());
        assertEquals(void.class, bridge.getMethod("nativeSetAudioRuntimeState", int.class).getReturnType());
        assertEquals(long.class, bridge.getMethod("nativeGetAudioOperationSnapshot").getReturnType());
    }

    private static URL url(String property) throws Exception {
        return new File(System.getProperty(property)).toURI().toURL();
    }
}
