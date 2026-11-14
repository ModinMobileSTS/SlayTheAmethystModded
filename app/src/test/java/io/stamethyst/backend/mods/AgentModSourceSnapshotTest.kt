package io.stamethyst.backend.mods

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentModSourceSnapshotTest {
    @Test
    fun prepare_preservesSourcesForSameContentAndClearsThemForChangedSnapshot() = withAgentSourceCacheRoot { root ->
        val jar = File(root, "mod.jar").apply { writeText("first") }
        val workspace = File(root, "workspace")
        val source = File(workspace, "source")
        val hash = AgentModSourceSnapshot.prepare(workspace, source, jar)
        assertEquals(AgentModJarReader.sha256(jar), hash)
        val cached = File(source, "Example.java").apply { writeText("class Example {}") }
        val metadata = File(workspace, "source-metadata.json")
        metadata.writeText(JSONObject(metadata.readText()).put("legacy", true).toString())
        assertEquals(hash, AgentModSourceSnapshot.prepare(workspace, source, jar))
        assertTrue(cached.isFile)
        assertTrue(JSONObject(metadata.readText()).getBoolean("legacy"))

        replaceKeepingSizeAndMtime(jar, "first".toByteArray())
        assertEquals(hash, AgentModSourceSnapshot.prepare(workspace, source, jar))
        assertTrue("same content keeps the source snapshot even after a file replacement", cached.isFile)

        replaceKeepingSizeAndMtime(jar, "other".toByteArray())
        assertFalse(hash == AgentModSourceSnapshot.prepare(workspace, source, jar))
        assertFalse(cached.exists())
        assertTrue(source.isDirectory)
        assertFalse(JSONObject(metadata.readText()).has("legacy"))
    }

    @Test
    fun sourceIndex_scansColdLegacyTreeOnceThenCountsDistinctNewPaths() = withAgentSourceCacheRoot { root ->
        var scans = 0
        val index = AgentMaterializedSourceIndex(scan = { directory ->
            scans++
            directory.walkTopDown().filter { it.isFile && it.extension == "java" }
                .map { it.relativeTo(directory).invariantSeparatorsPath }.toSet()
        })
        val legacy = File(root, "Legacy.java").apply { writeText("legacy") }
        assertEquals(1, index.count(root, "old", legacy))
        val added = File(root, "nested/Added.java").apply { parentFile.mkdirs(); writeText("added") }
        repeat(10) { assertEquals(2, index.count(root, "old", added)) }
        assertEquals(1, scans)
        added.delete()
        assertEquals(1, index.count(root, "old", added))
        assertEquals(1, index.count(root, "new", legacy))
        assertEquals(2, scans)
    }

    @Test
    fun sourceIndex_coldCountAfterEvictionAndRootReplacementIsAccurate() = withAgentSourceCacheRoot { root ->
        val index = AgentMaterializedSourceIndex(maxSnapshots = 1, maxPaths = 2)
        val first = File(root, "first").apply { mkdirs() }
        val second = File(root, "second").apply { mkdirs() }
        val a = File(first, "A.java").apply { writeText("a") }
        val b = File(second, "B.java").apply { writeText("b") }
        assertEquals(1, index.count(first, "hash", a))
        assertEquals(1, index.count(second, "hash", b))
        File(first, "Extra.java").writeText("extra")
        assertEquals(2, index.count(first, "hash", a))
        first.renameTo(File(root, "old-first"))
        first.mkdirs()
        a.writeText("new")
        assertEquals(1, index.count(first, "hash", a))
    }
}
