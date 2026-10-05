package io.stamethyst.web;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public class GeckoDependencyArchiveTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey())); out.write(entry.getValue()); out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private File container(Map<String, byte[]> apkEntries) throws Exception {
        File dir = temporary.newFolder();
        Files.write(new File(dir, "bundle.zip").toPath(), zip(Map.of("runtime.apk", zip(apkEntries))));
        return dir;
    }
    private File valid() throws Exception {
        return container(Map.of("classes.dex", new byte[]{1, 2}, "lib/arm64-v8a/libxul.so", new byte[]{3, 4}));
    }
    private String hash(File dir) throws Exception { return GeckoDependencyArchive.sha256(new File(dir, "bundle.zip")); }
    @Test public void extractsCodeAndArm64LibrariesAndVerifies() throws Exception {
        File dir = valid(); String hash = hash(dir);
        GeckoDependencyArchive.unpack(dir, hash);
        assertTrue(new File(dir, "runtime.apk").isFile());
        assertArrayEquals(new byte[]{3, 4}, Files.readAllBytes(new File(dir, "lib/libxul.so").toPath()));
        GeckoDependencyArchive.verify(dir, hash);
    }
    @Test public void rejectsWrongPinnedDigestBeforeExtracting() throws Exception {
        File dir = valid();
        assertThrows(IOException.class, () -> GeckoDependencyArchive.unpack(dir, "0".repeat(64)));
        assertFalse(new File(dir, "runtime.apk").exists());
    }
    @Test public void rejectsUnexpectedOuterEntriesAndTraversal() throws Exception {
        File dir = temporary.newFolder();
        Files.write(new File(dir, "bundle.zip").toPath(), zip(Map.of("../runtime.apk", new byte[]{1})));
        assertThrows(IOException.class, () -> GeckoDependencyArchive.unpack(dir, hash(dir)));
        assertFalse(new File(dir.getParentFile(), "runtime.apk").exists());
    }
    @Test public void ignoresOtherAbisAndNestedLibraryPaths() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("lib/arm64-v8a/libxul.so", new byte[]{1});
        entries.put("lib/x86_64/libxul.so", new byte[]{2});
        entries.put("lib/arm64-v8a/../../escape.so", new byte[]{3});
        File dir = container(entries);
        GeckoDependencyArchive.unpack(dir, hash(dir));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(new File(dir, "lib/libxul.so").toPath()));
        assertEquals(1, new File(dir, "lib").list().length);
    }
    @Test public void detectsTamperedNativeLibrary() throws Exception {
        File dir = valid(); String hash = hash(dir);
        GeckoDependencyArchive.unpack(dir, hash);
        File library = new File(dir, "lib/libxul.so");
        assertTrue(library.setWritable(true));
        Files.write(library.toPath(), new byte[]{7});
        assertThrows(IOException.class, () -> GeckoDependencyArchive.verify(dir, hash));
    }
    @Test public void detectsTamperedCode() throws Exception {
        File dir = valid(); String hash = hash(dir);
        GeckoDependencyArchive.unpack(dir, hash);
        File code = new File(dir, "runtime.apk");
        assertTrue(code.setWritable(true));
        Files.write(code.toPath(), new byte[]{7});
        assertThrows(IOException.class, () -> GeckoDependencyArchive.verify(dir, hash));
    }
    @Test public void rejectsContainerWithoutArm64Engine() throws Exception {
        File dir = container(Map.of("lib/x86_64/libxul.so", new byte[]{1}));
        assertThrows(IOException.class, () -> GeckoDependencyArchive.unpack(dir, hash(dir)));
    }
}
