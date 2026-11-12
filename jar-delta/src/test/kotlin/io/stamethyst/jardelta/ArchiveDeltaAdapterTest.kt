package io.stamethyst.jardelta

import io.stamethyst.archivedelta.generator.ArchiveDeltaGenerator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class ArchiveDeltaAdapterTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun explicitNewCodecBuildsCandidateWithoutInstallingIt() {
        val root = temporary.root.toPath()
        val base = writeZip(root.resolve("base.jar"), mapOf("keep" to byteArrayOf(1), "old" to byteArrayOf(2)))
        val target = writeZip(root.resolve("target.jar"), mapOf("keep" to byteArrayOf(1), "new" to byteArrayOf(3)))
        val before = Files.readAllBytes(base)
        val generated = ArchiveDeltaGenerator().generate(base, target, root.resolve("package.zip"), root.resolve("work"))
        val adapter = ArchiveDeltaAdapter()
        assertEquals(generated.manifest, adapter.inspect(generated.path))
        adapter.buildCandidate(base, generated.path, root.resolve("candidate.jar"), root.resolve("work"))
        assertEquals(-1L, Files.mismatch(target, root.resolve("candidate.jar")))
        assertArrayEquals(before, Files.readAllBytes(base))
    }
}
