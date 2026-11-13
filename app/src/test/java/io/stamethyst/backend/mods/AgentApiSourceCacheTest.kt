package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentApiSourceCacheTest {
    @Test
    fun write_doesNotRelabelCompletedDecompilationAfterDependencyReplacement() = withAgentSourceCacheRoot { root ->
        val jar = agentCacheClassJar(File(root, "parent.jar"), "SOURCE")
        val dependency = agentCacheClassJar(File(root, "dependency.jar"), "DEP_A", "Dependency")
        val replacement = agentCacheClassJar(File(root, "replacement.jar"), "DEP_B", "Dependency")
        val classpath = "${jar.path}${File.pathSeparator}${dependency.path}"
        val cache = File(root, "cache")
        val tokenA = AgentApiSourceCache.capture(jar, classpath)!!
        val sourceA = AgentPatchClassDecompiler.decompileClass(jar, "example.Sample", classpath, root).source
        replaceKeepingSizeAndMtime(dependency, replacement.readBytes())

        AgentApiSourceCache.write(cache, jar, "example.Sample", sourceA, classpath, tokenA)

        assertNull(AgentApiSourceCache.read(cache, jar, "example.Sample", classpath))
        assertFalse(cache.walkTopDown().any { it.isFile && it.extension == "java" })
        val tokenB = AgentApiSourceCache.capture(jar, classpath)!!
        val sourceB = AgentPatchClassDecompiler.decompileClass(jar, "example.Sample", classpath, root).source
        AgentApiSourceCache.write(cache, jar, "example.Sample", sourceB, classpath, tokenB)
        assertEquals(sourceB, AgentApiSourceCache.read(cache, jar, "example.Sample", classpath, tokenB))
        assertNull(AgentApiSourceCache.read(cache, jar, "example.Sample", classpath, tokenA))
    }

    @Test
    fun write_withoutProvenanceOrWithChangedSourceTokenDoesNotPublish() = withAgentSourceCacheRoot { root ->
        val jar = File(root, "source.jar").apply { writeText("aaaa") }
        val cache = File(root, "cache")
        AgentApiSourceCache.write(cache, jar, "example.Sample", "unknown provenance")
        assertNull(AgentApiSourceCache.read(cache, jar, "example.Sample"))
        val token = AgentApiSourceCache.capture(jar)!!
        replaceKeepingSizeAndMtime(jar, "bbbb".toByteArray())
        AgentApiSourceCache.write(cache, jar, "example.Sample", "old source", sourceToken = token)
        assertNull(AgentApiSourceCache.read(cache, jar, "example.Sample"))
        assertFalse(cache.exists())
    }

    @Test
    fun read_invalidatesDependenciesAndIsolatesEqualNamedArchives() = withAgentSourceCacheRoot { root ->
        val cache = File(root, "cache")
        val first = File(root, "a/game.jar").apply { parentFile.mkdirs(); writeText("aaaa") }
        val second = File(root, "b/game.jar").apply { parentFile.mkdirs(); writeText("bbbb") }
        Files.setLastModifiedTime(second.toPath(), Files.getLastModifiedTime(first.toPath()))
        val dependency = File(root, "dependency.jar").apply { writeText("dependency") }
        writeFixture(cache, first, "example.Sample", "source", dependency.path)
        assertEquals("source", AgentApiSourceCache.read(cache, first, "example.Sample", dependency.path))
        assertNull(AgentApiSourceCache.read(cache, second, "example.Sample", dependency.path))
        assertNull(AgentApiSourceCache.read(cache, first, "example.Sample", ""))
        replaceKeepingSizeAndMtime(dependency, "DEPENDENCY".toByteArray())
        assertNull(AgentApiSourceCache.read(cache, first, "example.Sample", dependency.path))
    }

    @Test
    fun writeThenRead_roundTripsSource() {
        val root = createTempDirectory("api-cache-roundtrip")
        val jar = jar(root, "desktop-1.0.jar")

        writeFixture(root, jar, "com.example.Big", "class Big {}")

        assertEquals("class Big {}", AgentApiSourceCache.read(root, jar, "com.example.Big"))
        assertTrue(AgentApiSourceCache.entryFile(root, jar, "com.example.Big")!!.isFile)
    }

    @Test
    fun read_returnsNullAfterArchiveChanges() {
        val root = createTempDirectory("api-cache-invalidate")
        val jar = jar(root, "desktop-1.0.jar")
        writeFixture(root, jar, "com.example.Big", "class Big {}")

        jar.setLastModified(jar.lastModified() + 60_000)

        assertNull(AgentApiSourceCache.read(root, jar, "com.example.Big"))
    }

    @Test
    fun read_returnsNullForMissingEntry() {
        val root = createTempDirectory("api-cache-missing")
        val jar = jar(root, "desktop-1.0.jar")

        assertNull(AgentApiSourceCache.read(root, jar, "com.example.Missing"))
    }

    @Test
    fun entryFile_rejectsTraversalAndSeparators() {
        val root = createTempDirectory("api-cache-traversal")
        val jar = jar(root, "desktop-1.0.jar")

        assertNull(AgentApiSourceCache.entryFile(root, jar, ".."))
        assertNull(AgentApiSourceCache.entryFile(root, jar, "com.example...Evil"))
        assertNull(AgentApiSourceCache.entryFile(root, jar, "com.evil\\Big"))
        assertNull(AgentApiSourceCache.entryFile(root, jar, ""))
    }

    @Test
    fun entryFile_normalizesDescriptorAndSlashNames() {
        val root = createTempDirectory("api-cache-normalize")
        val jar = jar(root, "desktop-1.0.jar")

        val dotted = AgentApiSourceCache.entryFile(root, jar, "com.example.Big")
        val slashed = AgentApiSourceCache.entryFile(root, jar, "com/example/Big")
        val descriptor = AgentApiSourceCache.entryFile(root, jar, "Lcom/example/Big;")

        assertNotNull(dotted)
        assertEquals(dotted, slashed)
        assertEquals(dotted, descriptor)
    }

    @Test
    fun write_createsEntryUnderFingerprintDirectory() {
        val root = createTempDirectory("api-cache-layout")
        val jar = jar(root, "desktop-1.0.jar")

        writeFixture(root, jar, "com.example.Big", "class Big {}")

        val file = AgentApiSourceCache.entryFile(root, jar, "com.example.Big")!!
        val fingerprintDir = file.relativeTo(root).path.split(File.separator).first()
        assertTrue(
            "entry must sit under a per-archive directory, was $fingerprintDir",
            fingerprintDir.startsWith("desktop-1.0.jar-"),
        )
        assertEquals("com/example/Big.java", file.relativeTo(root).path.split(File.separator).drop(1).joinToString("/"))
        assertFalse("nothing must be written outside the cache root", File(root.parentFile, "Big.java").exists())
    }

    @Test
    fun prune_dropsOldestEntriesUntilWithinFileBudget() {
        val root = createTempDirectory("api-cache-prune")
        val jar = jar(root, "desktop-1.0.jar")
        val names = listOf("com.example.A", "com.example.B", "com.example.C")
        names.forEachIndexed { index, name ->
            writeFixture(root, jar, name, "class X {}")
            val file = AgentApiSourceCache.entryFile(root, jar, name)!!
            file.setLastModified(1_000_000_000_000L + index * 1_000_000L)
        }

        AgentApiSourceCache.prune(root, maxBytes = Long.MAX_VALUE, maxFiles = 2)

        assertNull(AgentApiSourceCache.read(root, jar, "com.example.A"))
        assertNotNull(AgentApiSourceCache.read(root, jar, "com.example.B"))
        assertNotNull(AgentApiSourceCache.read(root, jar, "com.example.C"))
    }

    @Test
    fun prune_dropsOldestEntriesUntilWithinByteBudget() {
        val root = createTempDirectory("api-cache-bytes")
        val jar = jar(root, "desktop-1.0.jar")
        val payload = "x".repeat(200)
        listOf("com.example.A", "com.example.B", "com.example.C").forEachIndexed { index, name ->
            writeFixture(root, jar, name, payload)
            AgentApiSourceCache.entryFile(root, jar, name)!!
                .setLastModified(1_000_000_000_000L + index * 1_000_000L)
        }

        AgentApiSourceCache.prune(root, maxBytes = 300, maxFiles = Int.MAX_VALUE)

        assertNull(AgentApiSourceCache.read(root, jar, "com.example.A"))
        assertNotNull(AgentApiSourceCache.read(root, jar, "com.example.C"))
    }

    @Test
    fun prune_keepsEverythingWithinBudget() {
        val root = createTempDirectory("api-cache-keep")
        val jar = jar(root, "desktop-1.0.jar")
        writeFixture(root, jar, "com.example.A", "class A {}")

        AgentApiSourceCache.prune(root, maxBytes = Long.MAX_VALUE, maxFiles = 10)

        assertNotNull(AgentApiSourceCache.read(root, jar, "com.example.A"))
    }

    private fun writeFixture(root: File, jar: File, name: String, source: String, classpath: String = "") {
        val token = AgentApiSourceCache.capture(jar, classpath)
        AgentApiSourceCache.write(root, jar, name, source, classpath, token)
    }

    private fun jar(root: File, name: String): File =
        File(root, name).apply { writeBytes(ByteArray(16)) }

    private fun createTempDirectory(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()
}
