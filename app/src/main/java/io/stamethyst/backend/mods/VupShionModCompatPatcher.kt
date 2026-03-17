package io.stamethyst.backend.mods

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
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode

internal data class VupShionModCompatPatchResult(
    val patchedWebButtonConstructor: Boolean
)

internal object VupShionModCompatPatcher {
    private const val TARGET_CLASS_ENTRY = "VUPShionMod/ui/WebButton.class"
    private const val TARGET_CONSTRUCTOR_DESC =
        "(Ljava/lang/String;FFFFFLcom/badlogic/gdx/graphics/Color;" +
            "Lcom/badlogic/gdx/graphics/Texture;)V"
    private const val STEAM_APPS_INTERNAL_NAME = "com/codedisaster/steamworks/SteamApps"
    private const val NULL_API_EXCEPTION_INTERNAL_NAME = "VUPShionMod/msic/NullApiException"

    @Throws(IOException::class)
    fun patchInPlace(modJar: File): VupShionModCompatPatchResult {
        if (!modJar.isFile) {
            throw IOException("Mod jar not found: ${modJar.absolutePath}")
        }

        val replacements = LinkedHashMap<String, ByteArray>()
        ZipFile(modJar).use { zipFile ->
            patchClassEntry(zipFile, TARGET_CLASS_ENTRY) { classBytes ->
                patchWebButtonClassBytes(classBytes)
            }?.let { (entryName, patchedBytes) ->
                replacements[entryName] = patchedBytes
            }
        }

        if (replacements.isNotEmpty()) {
            rewriteJarWithReplacements(modJar, replacements)
        }

        return VupShionModCompatPatchResult(
            patchedWebButtonConstructor = replacements.isNotEmpty()
        )
    }

    @Throws(IOException::class)
    private fun patchClassEntry(
        zipFile: ZipFile,
        targetEntryName: String,
        transform: (ByteArray) -> ByteArray?
    ): Pair<String, ByteArray>? {
        val entry = JarFileIoUtils.findEntryIgnoreCase(zipFile, targetEntryName) ?: return null
        if (entry.isDirectory) {
            return null
        }
        val originalBytes = JarFileIoUtils.readEntryBytes(zipFile, entry)
        val patchedBytes = transform(originalBytes) ?: return null
        return entry.name to patchedBytes
    }

    @Throws(IOException::class)
    private fun patchWebButtonClassBytes(classBytes: ByteArray): ByteArray? {
        val classNode = readClassNode(classBytes)
        val constructor = classNode.methods.firstOrNull { method ->
            method.name == "<init>" && method.desc == TARGET_CONSTRUCTOR_DESC
        } ?: return null

        if (!removeWorkshopSubscriptionGate(constructor)) {
            return null
        }
        return writeClass(classNode)
    }

    private fun removeWorkshopSubscriptionGate(method: MethodNode): Boolean {
        var current: AbstractInsnNode? = method.instructions.first
        while (current != null) {
            val next = current.next
            val call = current as? MethodInsnNode
            if (call != null &&
                call.opcode == Opcodes.INVOKEVIRTUAL &&
                call.owner == STEAM_APPS_INTERNAL_NAME &&
                call.name == "isSubscribedApp" &&
                call.desc == "(I)Z"
            ) {
                val guardStart = findGuardStart(call)
                val guardEnd = findGuardEnd(call)
                if (guardStart != null && guardEnd != null) {
                    removeInclusive(method.instructions, guardStart, guardEnd)
                    return true
                }
            }
            current = next
        }
        return false
    }

    private fun findGuardStart(call: MethodInsnNode): AbstractInsnNode? {
        var current: AbstractInsnNode? = call
        repeat(8) {
            current = previousMeaningful(current)
            val typeInsn = current as? TypeInsnNode ?: return@repeat
            if (typeInsn.opcode == Opcodes.NEW && typeInsn.desc == STEAM_APPS_INTERNAL_NAME) {
                return typeInsn
            }
        }
        return null
    }

    private fun findGuardEnd(call: MethodInsnNode): AbstractInsnNode? {
        val ifNode = nextMeaningful(call) as? JumpInsnNode ?: return null
        if (ifNode.opcode != Opcodes.IFNE) {
            return null
        }
        val exceptionNew = nextMeaningful(ifNode) as? TypeInsnNode ?: return null
        if (exceptionNew.opcode != Opcodes.NEW ||
            exceptionNew.desc != NULL_API_EXCEPTION_INTERNAL_NAME
        ) {
            return null
        }
        val exceptionInit = nextMeaningful(nextMeaningful(exceptionNew)) as? MethodInsnNode ?: return null
        if (exceptionInit.owner != NULL_API_EXCEPTION_INTERNAL_NAME ||
            exceptionInit.name != "<init>" ||
            exceptionInit.desc != "()V"
        ) {
            return null
        }
        val athrow = nextMeaningful(exceptionInit) ?: return null
        if (athrow.opcode != Opcodes.ATHROW) {
            return null
        }
        return athrow
    }

    private fun previousMeaningful(node: AbstractInsnNode?): AbstractInsnNode? {
        var current = node?.previous
        while (current is LabelNode || current is LineNumberNode || current is FrameNode) {
            current = current.previous
        }
        return current
    }

    private fun nextMeaningful(node: AbstractInsnNode?): AbstractInsnNode? {
        var current = node?.next
        while (current is LabelNode || current is LineNumberNode || current is FrameNode) {
            current = current.next
        }
        return current
    }

    private fun removeInclusive(
        instructions: InsnList,
        start: AbstractInsnNode,
        end: AbstractInsnNode
    ) {
        var current: AbstractInsnNode? = start
        while (current != null) {
            val next = current.next
            instructions.remove(current)
            if (current == end) {
                return
            }
            current = next
        }
    }

    private fun readClassNode(classBytes: ByteArray): ClassNode {
        val classNode = ClassNode()
        ClassReader(classBytes).accept(classNode, 0)
        return classNode
    }

    private fun writeClass(classNode: ClassNode): ByteArray {
        val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        classNode.accept(classWriter)
        return classWriter.toByteArray()
    }

    @Throws(IOException::class)
    private fun rewriteJarWithReplacements(modJar: File, replacements: Map<String, ByteArray>) {
        val tempJar = File(modJar.absolutePath + ".vupshionpatch.tmp")
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
}
