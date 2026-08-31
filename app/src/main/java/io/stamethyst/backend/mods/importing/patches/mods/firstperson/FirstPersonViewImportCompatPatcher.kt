package io.stamethyst.backend.mods.importing.patches.mods.firstperson

import io.stamethyst.backend.mods.JarFileIoUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode

internal data class FirstPersonViewImportCompatPatchResult(
    val patchedClassEntries: Int,
    val patchedYawInputCalls: Int,
    val patchedPitchInputCalls: Int
) {
    val hasAnyPatch: Boolean
        get() = patchedClassEntries > 0
}

/**
 * Replaces only FirstPersonView's camera cursor reads. The rest of the mod's
 * mouse/touch input remains untouched for card targeting and the normal UI.
 */
internal object FirstPersonViewImportCompatPatcher {
    private const val TARGET_CLASS_ENTRY = "sts/fps/renderer/FirstPersonRenderer.class"
    private const val TARGET_METHOD_NAME = "update"
    private const val TARGET_METHOD_DESC = "()V"
    private const val GDX_INPUT_OWNER = "com/badlogic/gdx/Input"
    private const val GDX_OWNER = "com/badlogic/gdx/Gdx"
    private const val GDX_INPUT_FIELD = "input"
    private const val GDX_INPUT_DESC = "Lcom/badlogic/gdx/Input;"
    private const val BRIDGE_OWNER = "io/stamethyst/bridge/FirstPersonGyroBridge"
    private const val BRIDGE_DESC = "(Ljava/lang/Object;)I"
    private const val PATCHED_X_NAME = "getCursorX"
    private const val PATCHED_Y_NAME = "getCursorY"

    @Throws(IOException::class)
    fun patchInPlace(modJar: File): FirstPersonViewImportCompatPatchResult {
        if (!modJar.isFile) {
            throw IOException("Mod jar not found: ${modJar.absolutePath}")
        }
        var patchedYawInputCalls = 0
        var patchedPitchInputCalls = 0
        val replacements = LinkedHashMap<String, ByteArray>()
        ZipFile(modJar).use { zipFile ->
            val entry = JarFileIoUtils.findEntryIgnoreCase(zipFile, TARGET_CLASS_ENTRY)
                ?: return FirstPersonViewImportCompatPatchResult(0, 0, 0)
            if (entry.isDirectory) {
                return FirstPersonViewImportCompatPatchResult(0, 0, 0)
            }
            val originalBytes = JarFileIoUtils.readEntryBytes(zipFile, entry)
            val result = patchClassBytes(originalBytes)
            if (result != null) {
                replacements[entry.name] = result.bytes
                patchedYawInputCalls = result.patchedYawInputCalls
                patchedPitchInputCalls = result.patchedPitchInputCalls
            }
        }
        if (replacements.isNotEmpty()) {
            rewriteJarWithReplacements(modJar, replacements)
        }
        return FirstPersonViewImportCompatPatchResult(
            patchedClassEntries = replacements.size,
            patchedYawInputCalls = patchedYawInputCalls,
            patchedPitchInputCalls = patchedPitchInputCalls
        )
    }

    private data class ClassPatchResult(
        val bytes: ByteArray,
        val patchedYawInputCalls: Int,
        val patchedPitchInputCalls: Int
    )

    private fun patchClassBytes(classBytes: ByteArray): ClassPatchResult? {
        val classNode = ClassNode()
        ClassReader(classBytes).accept(classNode, 0)
        val updateMethod = classNode.methods.firstOrNull {
            it.name == TARGET_METHOD_NAME && it.desc == TARGET_METHOD_DESC
        } ?: return null
        var patchedYawInputCalls = 0
        var patchedPitchInputCalls = 0
        for (instruction in updateMethod.instructions.toArray()) {
            if (instruction !is MethodInsnNode || instruction.owner != GDX_INPUT_OWNER ||
                instruction.desc != "()I") {
                continue
            }
            val bridgeName = when (instruction.name) {
                "getX" -> PATCHED_X_NAME
                "getY" -> PATCHED_Y_NAME
                else -> continue
            }
            if (instruction.opcode != Opcodes.INVOKEINTERFACE) continue
            val inputLoad = previousExecutableInstruction(instruction)
            if (inputLoad !is FieldInsnNode || inputLoad.opcode != Opcodes.GETSTATIC ||
                inputLoad.owner != GDX_OWNER || inputLoad.name != GDX_INPUT_FIELD ||
                inputLoad.desc != GDX_INPUT_DESC) {
                // The bridge is only for the renderer's direct Gdx.input reads;
                // leave any other Input receiver in this method untouched.
                continue
            }
            instruction.opcode = Opcodes.INVOKESTATIC
            instruction.owner = BRIDGE_OWNER
            instruction.name = bridgeName
            instruction.desc = BRIDGE_DESC
            instruction.itf = false
            if (bridgeName == PATCHED_X_NAME) {
                patchedYawInputCalls++
            } else {
                patchedPitchInputCalls++
            }
        }
        if (patchedYawInputCalls == 0 && patchedPitchInputCalls == 0) return null
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        classNode.accept(writer)
        return ClassPatchResult(
            bytes = writer.toByteArray(),
            patchedYawInputCalls = patchedYawInputCalls,
            patchedPitchInputCalls = patchedPitchInputCalls
        )
    }

    private fun previousExecutableInstruction(instruction: AbstractInsnNode): AbstractInsnNode? {
        var previous = instruction.previous
        while (previous != null && (previous.type == AbstractInsnNode.LABEL ||
                previous.type == AbstractInsnNode.LINE ||
                previous.type == AbstractInsnNode.FRAME)) {
            previous = previous.previous
        }
        return previous
    }

    @Throws(IOException::class)
    private fun rewriteJarWithReplacements(modJar: File, replacements: Map<String, ByteArray>) {
        val tempJar = File(modJar.absolutePath + ".firstpersonpatch.tmp")
        val seenNames = LinkedHashSet<String>()
        try {
            ZipFile(modJar).use { zipFile ->
                FileOutputStream(tempJar, false).use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        val entries = zipFile.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (!seenNames.add(entry.name)) continue
                            val outputEntry = ZipEntry(entry.name)
                            if (entry.time > 0) outputEntry.time = entry.time
                            zipOut.putNextEntry(outputEntry)
                            if (!entry.isDirectory) {
                                val replacement = replacements[entry.name]
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
            JarFileIoUtils.moveFileReplacing(tempJar, modJar)
        } finally {
            if (tempJar.exists()) tempJar.delete()
        }
    }
}
