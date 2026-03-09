package io.stamethyst.backend.mods

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class FrierenModCompatPatchResult(
    val patchedAntiPirateMethod: Boolean
)

internal object FrierenModCompatPatcher {
    private const val TARGET_CLASS_ENTRY = "FrierenMod/utils/AntiPirateHelper.class"
    private const val TARGET_METHOD_NAME = "antiPirate"
    private const val TARGET_METHOD_DESCRIPTOR = "()V"
    private const val CODE_ATTRIBUTE_NAME = "Code"
    private const val CLASS_FILE_MAGIC = 0xCAFEBABEL
    private const val RETURN_OPCODE: Byte = 0xB1.toByte()

    @Throws(IOException::class)
    fun patchAntiPirateInPlace(modJar: File): FrierenModCompatPatchResult {
        if (!modJar.isFile) {
            throw IOException("Mod jar not found: ${modJar.absolutePath}")
        }

        val replacements = LinkedHashMap<String, ByteArray>()
        ZipFile(modJar).use { zipFile ->
            val targetEntry = JarFileIoUtils.findEntryIgnoreCase(zipFile, TARGET_CLASS_ENTRY)
            if (targetEntry != null && !targetEntry.isDirectory) {
                val originalClassBytes = JarFileIoUtils.readEntryBytes(zipFile, targetEntry)
                val patchedClassBytes = patchAntiPirateMethodClassBytes(originalClassBytes)
                if (patchedClassBytes != null) {
                    replacements[targetEntry.name] = patchedClassBytes
                }
            }
        }

        if (replacements.isNotEmpty()) {
            rewriteJarWithReplacements(modJar, replacements)
        }

        return FrierenModCompatPatchResult(
            patchedAntiPirateMethod = replacements.isNotEmpty()
        )
    }

    @Throws(IOException::class)
    private fun patchAntiPirateMethodClassBytes(classBytes: ByteArray): ByteArray? {
        val cursor = ClassCursor(classBytes)
        if (cursor.readU4() != CLASS_FILE_MAGIC) {
            throw IOException("Invalid class file magic for $TARGET_CLASS_ENTRY")
        }

        cursor.skip(2) // minor_version
        cursor.skip(2) // major_version
        val utf8Entries = readUtf8ConstantPool(cursor)

        cursor.skip(2) // access_flags
        cursor.skip(2) // this_class
        cursor.skip(2) // super_class

        val interfacesCount = cursor.readU2()
        cursor.skip(interfacesCount * 2)

        val fieldsCount = cursor.readU2()
        repeat(fieldsCount) {
            skipMemberInfo(cursor)
        }

        val methodsCount = cursor.readU2()
        repeat(methodsCount) {
            cursor.skip(2) // access_flags
            val nameIndex = cursor.readU2()
            val descriptorIndex = cursor.readU2()
            val attributesCount = cursor.readU2()
            val isTargetMethod = utf8Entries[nameIndex] == TARGET_METHOD_NAME &&
                utf8Entries[descriptorIndex] == TARGET_METHOD_DESCRIPTOR

            repeat(attributesCount) {
                val attributeStart = cursor.position
                val attributeNameIndex = cursor.readU2()
                val attributeLength = cursor.readU4AsInt()
                val attributeInfoStart = cursor.position
                val attributeInfoEnd = attributeInfoStart + attributeLength

                if (isTargetMethod && utf8Entries[attributeNameIndex] == CODE_ATTRIBUTE_NAME) {
                    if (isAlreadyPatchedCodeAttribute(classBytes, attributeInfoStart, attributeLength)) {
                        return null
                    }
                    val patchedAttribute = buildPatchedCodeAttribute(attributeNameIndex)
                    return replaceRange(
                        source = classBytes,
                        start = attributeStart,
                        end = attributeInfoEnd,
                        replacement = patchedAttribute
                    )
                }

                cursor.skip(attributeLength)
            }
        }

        return null
    }

    @Throws(IOException::class)
    private fun readUtf8ConstantPool(cursor: ClassCursor): Map<Int, String> {
        val constantPoolCount = cursor.readU2()
        val utf8Entries = HashMap<Int, String>(constantPoolCount)
        var index = 1
        while (index < constantPoolCount) {
            when (val tag = cursor.readU1()) {
                1 -> {
                    val length = cursor.readU2()
                    utf8Entries[index] = cursor.readUtf8(length)
                }

                3, 4 -> cursor.skip(4)
                5, 6 -> {
                    cursor.skip(8)
                    index++
                }

                7, 8, 16, 19, 20 -> cursor.skip(2)
                9, 10, 11, 12, 17, 18 -> cursor.skip(4)
                15 -> cursor.skip(3)
                else -> throw IOException("Unsupported class constant pool tag: $tag")
            }
            index++
        }
        return utf8Entries
    }

    @Throws(IOException::class)
    private fun skipMemberInfo(cursor: ClassCursor) {
        cursor.skip(2) // access_flags
        cursor.skip(2) // name_index
        cursor.skip(2) // descriptor_index
        val attributesCount = cursor.readU2()
        repeat(attributesCount) {
            cursor.skip(2) // attribute_name_index
            cursor.skip(cursor.readU4AsInt())
        }
    }

    private fun isAlreadyPatchedCodeAttribute(
        classBytes: ByteArray,
        attributeInfoStart: Int,
        attributeLength: Int
    ): Boolean {
        if (attributeLength < 9) {
            return false
        }
        val codeLength = readU4(classBytes, attributeInfoStart + 4)
        if (codeLength != 1L) {
            return false
        }
        val codeOffset = attributeInfoStart + 8
        if (codeOffset >= classBytes.size) {
            return false
        }
        return classBytes[codeOffset] == RETURN_OPCODE
    }

    private fun buildPatchedCodeAttribute(attributeNameIndex: Int): ByteArray {
        val output = ByteArrayOutputStream(19)
        writeU2(output, attributeNameIndex)
        // Rebuild the Code attribute so stale handlers and StackMapTable entries are dropped too.
        writeU4(output, 13)
        writeU2(output, 0) // max_stack
        writeU2(output, 0) // max_locals
        writeU4(output, 1)
        output.write(RETURN_OPCODE.toInt())
        writeU2(output, 0) // exception_table_length
        writeU2(output, 0) // attributes_count
        return output.toByteArray()
    }

    private fun replaceRange(
        source: ByteArray,
        start: Int,
        end: Int,
        replacement: ByteArray
    ): ByteArray {
        val output = ByteArrayOutputStream(source.size - (end - start) + replacement.size)
        output.write(source, 0, start)
        output.write(replacement)
        output.write(source, end, source.size - end)
        return output.toByteArray()
    }

    @Throws(IOException::class)
    private fun rewriteJarWithReplacements(modJar: File, replacements: Map<String, ByteArray>) {
        val tempJar = File(modJar.absolutePath + ".frierenpatch.tmp")
        val seenNames = LinkedHashSet<String>()
        try {
            ZipFile(modJar).use { zipFile ->
                FileOutputStream(tempJar, false).use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        val entries = zipFile.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val entryName = entry.name
                            if (!seenNames.add(entryName)) {
                                continue
                            }

                            val outEntry = ZipEntry(entryName)
                            if (entry.time > 0) {
                                outEntry.time = entry.time
                            }
                            zipOut.putNextEntry(outEntry)
                            if (!entry.isDirectory) {
                                val replacement = replacements[entryName]
                                if (replacement != null) {
                                    zipOut.write(replacement)
                                } else {
                                    zipFile.getInputStream(entry).use { input ->
                                        JarFileIoUtils.copyStream(input, zipOut)
                                    }
                                }
                            }
                            zipOut.closeEntry()
                        }
                    }
                }
            }

            if (modJar.exists() && !modJar.delete()) {
                throw IOException("Failed to replace ${modJar.absolutePath}")
            }
            if (!tempJar.renameTo(modJar)) {
                throw IOException("Failed to move ${tempJar.absolutePath} -> ${modJar.absolutePath}")
            }
            modJar.setLastModified(System.currentTimeMillis())
        } finally {
            if (tempJar.exists()) {
                tempJar.delete()
            }
        }
    }

    private fun readU4(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toInt() and 0xFF).toLong() shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF).toLong() shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF).toLong() shl 8) or
            (bytes[offset + 3].toInt() and 0xFF).toLong()
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

    private class ClassCursor(
        private val bytes: ByteArray
    ) {
        var position: Int = 0
            private set

        @Throws(IOException::class)
        fun readU1(): Int {
            ensureAvailable(1)
            return bytes[position++].toInt() and 0xFF
        }

        @Throws(IOException::class)
        fun readU2(): Int {
            return (readU1() shl 8) or readU1()
        }

        @Throws(IOException::class)
        fun readU4(): Long {
            return (readU1().toLong() shl 24) or
                (readU1().toLong() shl 16) or
                (readU1().toLong() shl 8) or
                readU1().toLong()
        }

        @Throws(IOException::class)
        fun readU4AsInt(): Int {
            val value = readU4()
            if (value > Int.MAX_VALUE.toLong()) {
                throw IOException("Class attribute is too large: $value")
            }
            return value.toInt()
        }

        @Throws(IOException::class)
        fun readUtf8(length: Int): String {
            ensureAvailable(length)
            val value = String(bytes, position, length, StandardCharsets.UTF_8)
            position += length
            return value
        }

        @Throws(IOException::class)
        fun skip(length: Int) {
            if (length < 0) {
                throw IOException("Negative class file skip: $length")
            }
            ensureAvailable(length)
            position += length
        }

        @Throws(IOException::class)
        private fun ensureAvailable(length: Int) {
            if (position + length > bytes.size) {
                throw IOException("Unexpected end of class file")
            }
        }
    }
}
