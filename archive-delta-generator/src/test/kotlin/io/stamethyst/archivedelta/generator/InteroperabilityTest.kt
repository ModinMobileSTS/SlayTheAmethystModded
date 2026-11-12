package io.stamethyst.archivedelta.generator

import com.google.archivepatcher.applier.FileByFileV1DeltaApplier
import io.stamethyst.archivedelta.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipFile

class InteroperabilityTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(name: String): Path {
        val path = temporary.root.toPath().resolve(name)
        javaClass.getResourceAsStream("/upstream/$name")!!.use { Files.copy(it, path) }
        return path
    }
    @Test fun originalGeneratorPatchAppliesWithStrictDecoder() {
        val base = fixture("base.zip"); val target = fixture("target.zip"); val patch = fixture("upstream.patch")
        val root = temporary.root.toPath()
        NativePatch.apply(base, patch, root.resolve("out.zip"), root, ArchiveLimits(), ArchiveControl())
        assertEquals(-1L, Files.mismatch(target, root.resolve("out.zip")))
    }
    @Test fun projectGeneratedPayloadAppliesWithLegacyApplier() {
        val base = fixture("base.zip"); val target = fixture("target.zip"); val root = temporary.root.toPath()
        val result = ArchiveDeltaGenerator().generate(base, target, root.resolve("package.zip"), root.resolve("work"))
        val inflater = Inflater(true)
        try {
            ZipFile(result.path.toFile()).use { zip ->
                InflaterInputStream(zip.getInputStream(zip.getEntry("payload.deflate")), inflater).use { patch ->
                    Files.newOutputStream(root.resolve("out.zip")).use { out ->
                        FileByFileV1DeltaApplier(root.toFile()).applyDelta(base.toFile(), patch, out)
                    }
                }
            }
        } finally { inflater.end() }
        assertEquals(-1L, Files.mismatch(target, root.resolve("out.zip")))
    }
}
