package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AgentCompileClasspathCacheTest {
    @Test
    fun transientDirectoryReadFailureRetriesWithoutNeedingIdentityChange() = withAgentSourceCacheRoot { root ->
        val mods = File(root, "mods").apply { mkdirs() }
        val dependency = jar(File(mods, "Base.jar"))
        val game = jar(File(root, "game.jar"))
        val before = AgentSourceFileIdentity.read(mods)
        var lists = 0
        val cache = AgentCompileClasspathCache(list = {
            lists++
            if (lists == 1) null else it.listFiles()?.toList()
        })
        fun resolve() = cache.resolve("test", listOf(game), mods, null) { it }
        assertEquals(listOf(game), resolve().entries)
        assertEquals(before, AgentSourceFileIdentity.read(mods))
        assertEquals(listOf(game, dependency), resolve().entries)
        assertEquals(listOf(game, dependency), resolve().entries)
        assertEquals(2, lists)
    }

    @Test
    fun stableCandidatesListOnceAndValidateOnceEachWhileAddDeleteUpdateInvalidate() = withAgentSourceCacheRoot { root ->
        val mods = File(root, "mods").apply { mkdirs() }
        val game = jar(File(root, "game.jar"))
        val base = jar(File(mods, "Base.jar"))
        var lists = 0
        var validations = 0
        val cache = AgentCompileClasspathCache(list = { lists++; it.listFiles().orEmpty().toList() })
        fun resolve() = cache.resolve("test", listOf(game), mods, null) {
            validations++
            it.takeIf(AgentPatchSourceCompiler::isReadableZip)
        }
        repeat(10) { assertEquals(listOf(game, base), resolve().entries) }
        assertEquals(1, lists)
        assertEquals(2, validations)

        val added = jar(File(mods, "Added.jar"))
        assertEquals(listOf(game, added, base), resolve().entries)
        assertEquals(2, lists)
        assertEquals(3, validations)
        base.setLastModified(base.lastModified() + 60_000)
        resolve()
        assertEquals(2, lists)
        assertEquals(4, validations)
        added.delete()
        assertEquals(listOf(game, base), resolve().entries)
        assertEquals(3, lists)
        assertEquals(4, validations)
        replaceKeepingSizeAndMtime(game, game.readBytes())
        resolve()
        assertEquals(5, validations)
    }

    @Test
    fun missingDirectoryAndMalformedJarRecoverAndEvictionRevalidates() = withAgentSourceCacheRoot { root ->
        val mods = File(root, "mods")
        val parent = File(root, "parent.jar").apply { writeText("bad") }
        val cache = AgentCompileClasspathCache(maxEntries = 1)
        var validations = 0
        fun resolve() = cache.resolve("test", emptyList(), mods, parent) {
            validations++
            it.takeIf(AgentPatchSourceCompiler::isReadableZip)
        }
        repeat(2) { assertEquals(listOf(parent), resolve().unindexable) }
        assertEquals(2, validations)
        jar(parent)
        assertEquals(listOf(parent), resolve().entries)
        mods.mkdirs()
        val dependency = jar(File(mods, "dependency.jar"))
        assertEquals(listOf(dependency, parent), resolve().entries)
        resolve()
        assertEquals(7, validations)
    }

    @Test
    fun changedOrDeletedOverlayIsRebuiltAndConcurrentQueriesSeeOnlyCompletedOutput() = withAgentSourceCacheRoot { root ->
        val parent = jar(File(root, "parent.jar"))
        val output = File(root, "overlay.jar")
        val cache = AgentCompileClasspathCache()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        var validations = 0
        fun resolve() = cache.resolve("test", listOf(parent), File(root, "mods"), null) {
            validations++
            if (validations == 1) {
                output.writeText("partial")
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            jar(output)
        }
        try {
            val first = pool.submit<CompileClasspathResolution> { resolve() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = pool.submit<CompileClasspathResolution> { resolve() }
            assertFalse(second.isDone)
            release.countDown()
            assertEquals(listOf(output), first.get(5, TimeUnit.SECONDS).entries)
            assertEquals(listOf(output), second.get(5, TimeUnit.SECONDS).entries)
            assertEquals(1, validations)
            assertTrue(AgentPatchSourceCompiler.isReadableZip(output))
            output.writeText("corrupted")
            resolve()
            assertEquals(2, validations)
            output.delete()
            resolve()
            assertEquals(3, validations)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    private fun jar(file: File): File {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("marker.class"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        return file
    }
}
