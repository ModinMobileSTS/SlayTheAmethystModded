package io.stamethyst.probe;

import io.stamethyst.probe.monitors.MonitorRegistry;
import org.junit.Test;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.jar.JarFile;
import java.util.Properties;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class GameProbeTest {

    @Test
    public void premainRegistersBuiltinMonitors() {
        GameProbe.premain("port=0", null);
        MonitorRegistry registry = GameProbe.getRegistry();
        assertNotNull(registry);

        assertTrue(registry.registeredTypes().contains("tracing"));
        assertTrue(registry.registeredTypes().contains("play"));
        assertEquals(2, registry.registeredTypes().size());
    }

    @Test
    public void premainWithActualInstrumentation() {
        FakeInstrumentation inst = new FakeInstrumentation();
        GameProbe.premain("port=0", inst);

        assertNotNull(GameProbe.getInstrumentation());
        assertSame(inst, GameProbe.getInstrumentation());
    }

    @Test
    public void premainParsesPortFromArgs() {
        FakeInstrumentation inst = new FakeInstrumentation();
        GameProbe.premain("port=9999", inst);

        assertNotNull(GameProbe.getConnectionManager());
    }

    @Test
    public void premainDefaultPortWhenNotSpecified() {
        FakeInstrumentation inst = new FakeInstrumentation();
        GameProbe.premain("", inst);

        assertNotNull(GameProbe.getConnectionManager());
    }

    @Test
    public void offlineArthasConfigIsExplicitAndBounded() {
        Properties props = new Properties();
        OfflineArthasController.Config disabled = OfflineArthasController.Config.from(props);
        assertFalse(disabled.enabled);

        props.setProperty("arthas", "true");
        props.setProperty("arthasPort", "99999");
        props.setProperty("arthasDelaySeconds", "-1");
        OfflineArthasController.Config enabled = OfflineArthasController.Config.from(props);
        assertTrue(enabled.enabled);
        assertEquals(8099, enabled.port);
        assertEquals(15, enabled.delaySeconds);
    }

    @Test
    public void promptScannerFindsCompleteArthasPrompt() {
        byte[] prompt = OfflineArthasController.RawShell.findPrompt(
            "welcome\n[arthas@123]$ ".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals("[arthas@123]$ ".getBytes(StandardCharsets.US_ASCII), prompt);
        assertNull(OfflineArthasController.RawShell.findPrompt(
            "[arthas@123]$".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void gameClassLoaderCaptureRejectsSystemAndProbeClasses() {
        assertFalse(GameProbe.isCandidateGameClassLoader(
            ClassLoader.getSystemClassLoader(), "com/megacrit/cardcrawl/core/CardCrawlGame"));
        ClassLoader child = new ClassLoader(ClassLoader.getSystemClassLoader()) {};
        assertFalse(GameProbe.isCandidateGameClassLoader(child, "io/stamethyst/arthas/Bridge"));
        assertFalse(GameProbe.isCandidateGameClassLoader(child, "org/example/Unrelated"));
        assertTrue(GameProbe.isCandidateGameClassLoader(
            child, "com/megacrit/cardcrawl/core/CardCrawlGame"));
    }

    private static class FakeInstrumentation implements Instrumentation {
        @Override public void addTransformer(java.lang.instrument.ClassFileTransformer transformer, boolean canRetransform) {}
        @Override public void addTransformer(java.lang.instrument.ClassFileTransformer transformer) {}
        @Override public boolean removeTransformer(java.lang.instrument.ClassFileTransformer transformer) { return true; }
        @Override public boolean isRetransformClassesSupported() { return false; }
        @Override public void retransformClasses(Class<?>... classes) {}
        @Override public boolean isRedefineClassesSupported() { return false; }
        @Override public void redefineClasses(ClassDefinition... definitions) {}
        @Override public boolean isModifiableClass(Class<?> theClass) { return true; }
        @Override public Class<?>[] getAllLoadedClasses() { return new Class<?>[0]; }
        @Override public Class<?>[] getInitiatedClasses(ClassLoader loader) { return new Class<?>[0]; }
        @Override public long getObjectSize(Object objectToSize) { return 0; }
        @Override public void appendToBootstrapClassLoaderSearch(JarFile jarfile) {}
        @Override public void appendToSystemClassLoaderSearch(JarFile jarfile) {}
        @Override public boolean isNativeMethodPrefixSupported() { return false; }
        @Override public void setNativeMethodPrefix(java.lang.instrument.ClassFileTransformer transformer, String prefix) {}
    }
}
