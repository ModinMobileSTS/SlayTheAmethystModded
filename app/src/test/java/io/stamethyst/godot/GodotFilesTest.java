package io.stamethyst.godot;

import java.io.*;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class GodotFilesTest {
    @Test public void assetMountRejectsUnexpectedEntries() throws Exception {
        File mount = Files.createTempFile("godot-extra-assets", ".zip").toFile();
        try {
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new FileOutputStream(mount))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("classes.dex"));
                zip.write(new byte[]{1}); zip.closeEntry();
            }
            assertThrows(IOException.class, () -> GodotFiles.verifyAssets(mount, "0".repeat(64)));
        } finally { mount.delete(); }
    }
    @Test public void assetMountPreservesPinnedPckAndRejectsWrongHash() throws Exception {
        File root = Files.createTempDirectory("godot-assets").toFile();
        File pck = new File(root, "game.pck"), mount = new File(root, "assets.zip");
        try {
            Files.write(pck.toPath(), new byte[]{'a', 'b', 'c'});
            GodotFiles.writeAssets(pck, mount);
            GodotFiles.verifyAssets(mount, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
            assertThrows(IOException.class, () -> GodotFiles.verifyAssets(mount, "0".repeat(64)));
        } finally { mount.delete(); pck.delete(); root.delete(); }
    }
    @Test public void verifiesPinnedBytes() throws Exception {
        File file = Files.createTempFile("godot-hash", ".bin").toFile();
        try {
            Files.write(file.toPath(), new byte[]{'a', 'b', 'c'});
            GodotFiles.verify(file, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
            assertThrows(IOException.class, () -> GodotFiles.verify(file, "0".repeat(64)));
            assertThrows(IOException.class, () -> GodotFiles.verify(file, ""));
        } finally { file.delete(); }
    }
    @Test public void rejectsSymlinkInsteadOfFollowingIt() throws Exception {
        File root = Files.createTempDirectory("godot-link").toFile();
        File target = new File(root, "target"), link = new File(root, "link");
        try {
            Files.write(target.toPath(), new byte[]{'a', 'b', 'c'});
            Files.createSymbolicLink(link.toPath(), target.toPath());
            assertThrows(IOException.class, () -> GodotFiles.verify(link, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        } finally { link.delete(); target.delete(); root.delete(); }
    }
    @Test public void boundedCopyRejectsExpansion() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> GodotFiles.copyBounded(new ByteArrayInputStream(new byte[100]), out, 99));
        assertEquals(0, out.size());
    }
    @Test public void boundedCopyAcceptsExactLimit() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GodotFiles.copyBounded(new ByteArrayInputStream(new byte[100]), out, 100);
        assertEquals(100, out.size());
    }
}
