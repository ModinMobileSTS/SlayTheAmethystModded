package io.stamethyst.jardelta

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/** Identical data-level experiment for any unsigned JAR; never invokes game/mod bytecode. */
class LocalCorpusTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun applyAndRestoreLocalJar() {
        val corpus = Path.of(System.getProperty("jarDelta.corpus"))
        val source = corpus.resolve("base.jar")
        assertTrue("Download a local corpus JAR to $source first", Files.isRegularFile(source))
        val initial = Files.readAllBytes(source)
        val contents = readZip(source)
        val existing = contents.keys.filter { !it.endsWith('/') }.sorted()
        assertTrue("Corpus requires at least two file entries", existing.size >= 2)
        // Deliberately content-agnostic: replace/delete by lexical position, not mod class identity.
        // This is an archive experiment, NOT a playable compatibility fix.
        val replacement = "delta experiment replacement".toByteArray()
        val added = "delta experiment addition".toByteArray()
        val addition = "jar-delta-experiment/new.txt"
        assertFalse(contents.containsKey(addition))
        val result = contents.minus(existing[1]) + mapOf(existing[0] to replacement, addition to added)
        val directory = temporary.root.toPath()
        val current = Files.write(directory.resolve("current.jar"), initial)
        val delta = patch(directory.resolve("delta.zip"), current, result, listOf(
            DeltaOperation("replace", existing[0], "payload/a", sha(replacement)),
            DeltaOperation("delete", existing[1]),
            DeltaOperation("add", addition, "payload/b", sha(added)),
        ), mapOf("payload/a" to replacement, "payload/b" to added))
        val engine = JarDeltaEngine()
        val applied = engine.apply(current, delta, directory.resolve("history"))
        assertContents(result, current)
        assertArrayEquals(initial, Files.readAllBytes(applied.history.path))
        val restored = engine.restore(current, applied.history)
        assertArrayEquals(initial, Files.readAllBytes(current))
        assertArrayEquals(initial, Files.readAllBytes(source))
        val report = """
            baseSha256=${sha(initial)}
            baseBytes=${initial.size}
            deltaBytes=${applied.metrics.deltaBytes}
            resultBytes=${applied.metrics.resultBytes}
            peakExtraBytes=${applied.metrics.peakExtraBytes}
            applyPhaseNanos=${applied.metrics.phaseNanos}
            restorePhaseNanos=${restored.metrics.phaseNanos}
            unchangedEntries=${contents.size - 2}
            restoredByteIdentical=true
            playableCompatibilityFix=false
        """.trimIndent()
        println(report)
        Files.writeString(corpus.resolve("last-report.txt"), report)
    }
}
