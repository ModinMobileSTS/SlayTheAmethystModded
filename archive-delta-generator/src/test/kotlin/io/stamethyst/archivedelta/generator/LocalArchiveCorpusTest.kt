package io.stamethyst.archivedelta.generator

import io.stamethyst.archivedelta.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

class LocalArchiveCorpusTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun genericRealJarRoundtrip() {
        val base = Path.of(System.getProperty("archiveDelta.corpus"))
        assertTrue("Explicit local corpus required: $base", Files.isRegularFile(base))
        val before = Files.readAllBytes(base)
        val contents = ZipFile(base.toFile()).use { z -> z.entries().asSequence().associate { e -> e.name to z.getInputStream(e).use { it.readBytes() } } }
        val names = contents.keys.filterNot { it.endsWith('/') }.sorted()
        assertTrue(names.size >= 2)
        val addition = "archive-delta-experiment/new.txt"; assertFalse(addition in contents)
        val root = temporary.root.toPath()
        val target = zip(root.resolve("target.jar"), contents - names[1] + mapOf(names[0] to "replacement".toByteArray(), addition to "addition".toByteArray()))
        val result = ArchiveDeltaGenerator().generate(base, target, root.resolve("package.zip"), root.resolve("work"))
        ArchivePackages.buildCandidate(base, result.path, root.resolve("result.jar"), root.resolve("work"))
        assertEquals(-1L, Files.mismatch(target, root.resolve("result.jar")))
        assertArrayEquals(before, Files.readAllBytes(base))
        println("base=${before.size} target=${Files.size(target)} package=${Files.size(result.path)} payload=${result.manifest.payload.size} exact=true playableFix=false")
    }
}
