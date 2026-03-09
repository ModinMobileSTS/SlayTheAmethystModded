package io.stamethyst.backend.mods

import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrierenModCompatPatcherTest {
    @Test
    fun patchAntiPirateInPlace_rewritesTargetMethodAndIsIdempotent() {
        val tempDir = Files.createTempDirectory("frierenmod-patcher-test")
        val jarFile = tempDir.resolve("FrierenMod.jar").toFile()
        createFrierenJar(jarFile)

        val beforePatchError = invokeAntiPirate(jarFile)
        assertTrue(beforePatchError is InterruptedException)

        val firstPatch = FrierenModCompatPatcher.patchAntiPirateInPlace(jarFile)
        assertTrue(firstPatch.patchedAntiPirateMethod)
        assertNull(invokeAntiPirate(jarFile))

        val secondPatch = FrierenModCompatPatcher.patchAntiPirateInPlace(jarFile)
        assertFalse(secondPatch.patchedAntiPirateMethod)
        assertNull(invokeAntiPirate(jarFile))
    }

    @Test
    fun patchAntiPirateInPlace_returnsFalseWhenTargetClassIsMissing() {
        val tempDir = Files.createTempDirectory("frierenmod-patcher-empty")
        val jarFile = tempDir.resolve("OtherMod.jar").toFile()
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("example/Placeholder.class"))
            zipOut.write(byteArrayOf(0x00))
            zipOut.closeEntry()
        }

        val patchResult = FrierenModCompatPatcher.patchAntiPirateInPlace(jarFile)
        assertFalse(patchResult.patchedAntiPirateMethod)
    }

    private fun createFrierenJar(jarFile: File) {
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("FrierenMod/utils/AntiPirateHelper.class"))
            zipOut.write(buildAntiPirateHelperClassBytes())
            zipOut.closeEntry()
        }
    }

    private fun invokeAntiPirate(jarFile: File): Throwable? {
        URLClassLoader(arrayOf(jarFile.toURI().toURL()), null).use { classLoader ->
            val helperClass = Class.forName("FrierenMod.utils.AntiPirateHelper", true, classLoader)
            val antiPirateMethod = helperClass.getMethod("antiPirate")
            return try {
                antiPirateMethod.invoke(null)
                null
            } catch (error: InvocationTargetException) {
                error.targetException
            }
        }
    }

    private fun buildAntiPirateHelperClassBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        writeU4(output, 0xCAFEBABE.toInt())
        writeU2(output, 0)
        writeU2(output, 49)
        writeU2(output, 18)

        writeUtf8Entry(output, "FrierenMod/utils/AntiPirateHelper") // 1
        writeClassEntry(output, 1) // 2
        writeUtf8Entry(output, "java/lang/Object") // 3
        writeClassEntry(output, 3) // 4
        writeUtf8Entry(output, "<init>") // 5
        writeUtf8Entry(output, "()V") // 6
        writeNameAndTypeEntry(output, 5, 6) // 7
        writeMethodRefEntry(output, 4, 7) // 8
        writeUtf8Entry(output, "Code") // 9
        writeUtf8Entry(output, "antiPirate") // 10
        writeUtf8Entry(output, "java/lang/InterruptedException") // 11
        writeClassEntry(output, 11) // 12
        writeUtf8Entry(output, "blocked") // 13
        writeStringEntry(output, 13) // 14
        writeUtf8Entry(output, "(Ljava/lang/String;)V") // 15
        writeNameAndTypeEntry(output, 5, 15) // 16
        writeMethodRefEntry(output, 12, 16) // 17

        writeU2(output, 0x0021)
        writeU2(output, 2)
        writeU2(output, 4)
        writeU2(output, 0)
        writeU2(output, 0)
        writeU2(output, 2)

        writeU2(output, 0x0001)
        writeU2(output, 5)
        writeU2(output, 6)
        writeU2(output, 1)
        writeCodeAttribute(
            output = output,
            maxStack = 1,
            maxLocals = 1,
            code = byteArrayOf(
                0x2A,
                0xB7.toByte(),
                0x00,
                0x08,
                0xB1.toByte()
            )
        )

        writeU2(output, 0x0009)
        writeU2(output, 10)
        writeU2(output, 6)
        writeU2(output, 1)
        writeCodeAttribute(
            output = output,
            maxStack = 3,
            maxLocals = 0,
            code = byteArrayOf(
                0xBB.toByte(),
                0x00,
                0x0C,
                0x59,
                0x12,
                0x0E,
                0xB7.toByte(),
                0x00,
                0x11,
                0xBF.toByte()
            )
        )

        writeU2(output, 0)
        return output.toByteArray()
    }

    private fun writeCodeAttribute(
        output: ByteArrayOutputStream,
        maxStack: Int,
        maxLocals: Int,
        code: ByteArray
    ) {
        writeU2(output, 9)
        writeU4(output, 12 + code.size)
        writeU2(output, maxStack)
        writeU2(output, maxLocals)
        writeU4(output, code.size)
        output.write(code)
        writeU2(output, 0)
        writeU2(output, 0)
    }

    private fun writeUtf8Entry(output: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        output.write(1)
        writeU2(output, bytes.size)
        output.write(bytes)
    }

    private fun writeClassEntry(output: ByteArrayOutputStream, nameIndex: Int) {
        output.write(7)
        writeU2(output, nameIndex)
    }

    private fun writeStringEntry(output: ByteArrayOutputStream, utf8Index: Int) {
        output.write(8)
        writeU2(output, utf8Index)
    }

    private fun writeNameAndTypeEntry(
        output: ByteArrayOutputStream,
        nameIndex: Int,
        descriptorIndex: Int
    ) {
        output.write(12)
        writeU2(output, nameIndex)
        writeU2(output, descriptorIndex)
    }

    private fun writeMethodRefEntry(
        output: ByteArrayOutputStream,
        classIndex: Int,
        nameAndTypeIndex: Int
    ) {
        output.write(10)
        writeU2(output, classIndex)
        writeU2(output, nameAndTypeIndex)
    }

    private fun writeU2(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    private fun writeU4(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 24) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
    }
}
