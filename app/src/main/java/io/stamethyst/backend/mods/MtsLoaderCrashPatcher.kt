package io.stamethyst.backend.mods

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.HashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object MtsLoaderCrashPatcher {
    private const val LOADER_CLASS_ENTRY = "com/evacipated/cardcrawl/modthespire/Loader.class"
    private const val RUN_MODS_METHOD_NAME = "runMods"
    private const val RUN_MODS_METHOD_DESC = "([Ljava/io/File;)V"

    private val SWALLOWED_FAILURE_TYPES = setOf(
        "com/evacipated/cardcrawl/modthespire/MissingDependencyException",
        "com/evacipated/cardcrawl/modthespire/DuplicateModIDException",
        "com/evacipated/cardcrawl/modthespire/MissingModIDException",
        "java/lang/Exception"
    )

    @Throws(IOException::class)
    fun ensurePatchedMtsJar(mtsJar: File?) {
        if (mtsJar == null || !mtsJar.isFile) {
            throw IOException("ModTheSpire.jar not found")
        }

        val originalLoaderBytes = JarFileIoUtils.readJarEntryBytes(mtsJar, LOADER_CLASS_ENTRY)
            ?: throw IOException("Invalid ModTheSpire.jar: missing $LOADER_CLASS_ENTRY")
        val patchedLoaderBytes = patchLoaderBytes(originalLoaderBytes)
        if (patchedLoaderBytes.contentEquals(originalLoaderBytes)) {
            return
        }

        val tempJar = File(mtsJar.absolutePath + ".patching.tmp")
        val seenNames = HashSet<String>()
        FileInputStream(mtsJar).use { fileInput ->
            ZipInputStream(fileInput).use { zipIn ->
                FileOutputStream(tempJar, false).use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        while (true) {
                            val entry = zipIn.nextEntry ?: break
                            val name = entry.name
                            if (entry.isDirectory || !seenNames.add(name)) {
                                zipIn.closeEntry()
                                continue
                            }
                            val outEntry = ZipEntry(name)
                            if (entry.time > 0L) {
                                outEntry.time = entry.time
                            }
                            zipOut.putNextEntry(outEntry)
                            if (name == LOADER_CLASS_ENTRY) {
                                zipOut.write(patchedLoaderBytes)
                            } else {
                                JarFileIoUtils.copyStream(zipIn, zipOut)
                            }
                            zipOut.closeEntry()
                            zipIn.closeEntry()
                        }
                    }
                }
            }
        }

        if (!isPatchedLoaderClass(
                JarFileIoUtils.readJarEntryBytes(tempJar, LOADER_CLASS_ENTRY)
                    ?: throw IOException("Patched MTS jar is missing Loader.class")
            )
        ) {
            if (tempJar.exists()) {
                tempJar.delete()
            }
            throw IOException("Failed to patch ModTheSpire startup failure handling")
        }

        if (mtsJar.exists() && !mtsJar.delete()) {
            if (tempJar.exists()) {
                tempJar.delete()
            }
            throw IOException("Failed to replace ${mtsJar.absolutePath}")
        }
        if (!tempJar.renameTo(mtsJar)) {
            if (tempJar.exists()) {
                tempJar.delete()
            }
            throw IOException("Failed to move ${tempJar.absolutePath} -> ${mtsJar.absolutePath}")
        }
        mtsJar.setLastModified(System.currentTimeMillis())
    }

    internal fun isPatchedLoaderClass(loaderBytes: ByteArray): Boolean {
        val classNode = readClassNode(loaderBytes)
        val runModsMethod = classNode.methods.firstOrNull { method ->
            method.name == RUN_MODS_METHOD_NAME && method.desc == RUN_MODS_METHOD_DESC
        } ?: return false
        return runModsMethod.tryCatchBlocks.none { tryCatch ->
            SWALLOWED_FAILURE_TYPES.contains(tryCatch.type)
        }
    }

    internal fun patchLoaderBytes(loaderBytes: ByteArray): ByteArray {
        val classNode = readClassNode(loaderBytes)
        val runModsMethod = classNode.methods.firstOrNull { method ->
            method.name == RUN_MODS_METHOD_NAME && method.desc == RUN_MODS_METHOD_DESC
        } ?: throw IOException("Unsupported ModTheSpire.jar: Loader.runMods(File[]) not found")

        val originalCatchCount = runModsMethod.tryCatchBlocks.size
        runModsMethod.tryCatchBlocks.removeAll { tryCatch ->
            SWALLOWED_FAILURE_TYPES.contains(tryCatch.type)
        }
        if (runModsMethod.tryCatchBlocks.size == originalCatchCount) {
            return loaderBytes
        }

        val classWriter = ClassWriter(0)
        classNode.accept(classWriter)
        return classWriter.toByteArray()
    }

    private fun readClassNode(loaderBytes: ByteArray): ClassNode {
        val classNode = ClassNode()
        ClassReader(loaderBytes).accept(classNode, 0)
        return classNode
    }
}
