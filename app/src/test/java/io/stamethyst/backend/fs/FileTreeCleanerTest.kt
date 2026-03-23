package io.stamethyst.backend.fs

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

class FileTreeCleanerTest {
    private val cleanupRoots = ArrayList<File>()

    @After
    fun tearDown() {
        cleanupRoots.forEach(FileTreeCleaner::deleteRecursively)
        cleanupRoots.clear()
    }

    @Test
    fun deleteRecursively_removesDanglingSymlinkEntries() {
        val symlinkRoot = registerTempDir("filetree-cleaner-dangling")
        val target = File(symlinkRoot, "target.txt")
        target.writeText("runtime")
        val link = File(symlinkRoot, "link.txt")

        assumeTrue(canCreateSymbolicLink(target, link))

        assertTrue(target.delete())
        assertTrue(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse(link.exists())

        assertTrue(FileTreeCleaner.deleteRecursively(link))
        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun deleteRecursively_treatsDirectorySymlinkAsLeaf() {
        val cleanupRoot = registerTempDir("filetree-cleaner-root")
        val targetRoot = registerTempDir("filetree-cleaner-target")
        val preservedFile = File(targetRoot, "preserved.txt")
        preservedFile.writeText("keep")
        val link = File(cleanupRoot, "linked-dir")

        assumeTrue(canCreateSymbolicLink(targetRoot, link))

        assertTrue(FileTreeCleaner.deleteRecursively(cleanupRoot))
        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue(targetRoot.isDirectory)
        assertTrue(preservedFile.isFile)
    }

    private fun registerTempDir(prefix: String): File {
        return Files.createTempDirectory(prefix).toFile().also(cleanupRoots::add)
    }

    private fun canCreateSymbolicLink(target: File, link: File): Boolean {
        return try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: java.nio.file.FileSystemException) {
            false
        }
    }
}
