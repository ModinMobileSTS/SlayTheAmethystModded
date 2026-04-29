package io.stamethyst.backend.mods

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode

class StsShaderPrecisionCompatPatcherTest {
    @Test
    fun patchShaderProgramClass_insertsCompatCall_andIsIdempotent() {
        val originalJar = resolveFixtureFile(
            "tools/desktop-1.0.jar",
            "../tools/desktop-1.0.jar"
        )
        assumeTrue(originalJar.isFile)

        val originalBytes = readJarEntry(originalJar, STS_PATCH_SHADER_PROGRAM_CLASS)
        assertFalse(StsShaderPrecisionCompatPatcher.isPatchedShaderProgramClass(originalBytes))

        val patchedBytes = StsShaderPrecisionCompatPatcher.patchShaderProgramClass(originalBytes)
        assertTrue(StsShaderPrecisionCompatPatcher.isPatchedShaderProgramClass(patchedBytes))
        assertTrue(hasCompatCall(patchedBytes, "normalizeVertexShader"))
        assertTrue(hasCompatCall(patchedBytes, "normalizeFragmentShader"))

        val patchedAgain = StsShaderPrecisionCompatPatcher.patchShaderProgramClass(patchedBytes)
        assertArrayEquals(patchedBytes, patchedAgain)
    }

    private fun resolveFixtureFile(vararg candidates: String): File {
        return candidates
            .asSequence()
            .map(::File)
            .firstOrNull { it.isFile }
            ?: File(candidates.first())
    }

    private fun readJarEntry(jarFile: File, entryName: String): ByteArray {
        ZipFile(jarFile).use { zipFile ->
            val entry = zipFile.getEntry(entryName)
            requireNotNull(entry) { "Missing entry $entryName in ${jarFile.absolutePath}" }
            return JarFileIoUtils.readEntryBytes(zipFile, entry)
        }
    }

    private fun hasCompatCall(classBytes: ByteArray, methodName: String): Boolean {
        val classNode = ClassNode()
        ClassReader(classBytes).accept(classNode, ClassReader.SKIP_FRAMES)
        val constructor = requireNotNull(
            classNode.methods.firstOrNull { method ->
                method.name == "<init>" && method.desc == "(Ljava/lang/String;Ljava/lang/String;)V"
            }
        )
        var current = constructor.instructions.first
        while (current != null) {
            val call = current as? MethodInsnNode
            if (call != null &&
                call.owner == "io/stamethyst/gdx/FragmentShaderCompat" &&
                call.name == methodName &&
                call.desc == "(Ljava/lang/String;)Ljava/lang/String;"
            ) {
                return true
            }
            current = current.next
        }
        return false
    }
}
