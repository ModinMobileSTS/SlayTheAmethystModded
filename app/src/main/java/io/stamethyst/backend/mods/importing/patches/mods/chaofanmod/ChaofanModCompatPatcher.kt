package io.stamethyst.backend.mods.importing.patches.mods.chaofanmod

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
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode

internal data class ChaofanModCompatPatchResult(
    val patchedSteamworksHelperInitialization: Boolean
) {
    val hasAnyPatch: Boolean
        get() = patchedSteamworksHelperInitialization
}

internal object ChaofanModCompatPatcher {
    private const val TARGET_CLASS_ENTRY = "io/chaofan/sts/chaofanmod/ChaofanMod.class"
    private const val TARGET_CLASS_INTERNAL_NAME = "io/chaofan/sts/chaofanmod/ChaofanMod"
    private const val TARGET_METHOD_NAME = "receivePostInitialize"
    private const val VOID_METHOD_DESC = "()V"
    private const val STEAMWORKS_HELPER_INTERNAL_NAME =
        "io/chaofan/sts/chaofanmod/utils/SteamworksHelper"
    private const val STEAMWORKS_HELPER_DESC =
        "Lio/chaofan/sts/chaofanmod/utils/SteamworksHelper;"
    private const val BASEMOD_INTERNAL_NAME = "basemod/BaseMod"
    private const val BASEMOD_SUBSCRIBE_DESC = "(Lbasemod/interfaces/ISubscriber;)V"

    @Throws(IOException::class)
    fun patchInPlace(modJar: File): ChaofanModCompatPatchResult {
        if (!modJar.isFile) {
            throw IOException("Mod jar not found: ${modJar.absolutePath}")
        }

        val replacements = LinkedHashMap<String, ByteArray>()
        ZipFile(modJar).use { zipFile ->
            val entry = JarFileIoUtils.findEntryIgnoreCase(zipFile, TARGET_CLASS_ENTRY)
            if (entry != null && !entry.isDirectory) {
                val originalClassBytes = JarFileIoUtils.readEntryBytes(zipFile, entry)
                val patchedClassBytes = patchChaofanModClassBytes(originalClassBytes)
                if (patchedClassBytes != null) {
                    replacements[entry.name] = patchedClassBytes
                }
            }
        }

        if (replacements.isNotEmpty()) {
            rewriteJarWithReplacements(modJar, replacements)
        }

        return ChaofanModCompatPatchResult(
            patchedSteamworksHelperInitialization = replacements.isNotEmpty()
        )
    }

    private fun patchChaofanModClassBytes(classBytes: ByteArray): ByteArray? {
        val classNode = readClassNode(classBytes)
        val postInitialize = classNode.methods.firstOrNull { method ->
            method.name == TARGET_METHOD_NAME && method.desc == VOID_METHOD_DESC
        } ?: return null

        if (!removeSteamworksHelperSubscription(postInitialize)) {
            return null
        }
        return writeClass(classNode)
    }

    private fun removeSteamworksHelperSubscription(method: MethodNode): Boolean {
        var current: AbstractInsnNode? = method.instructions.first
        while (current != null) {
            val next = current.next
            val newHelper = current as? TypeInsnNode
            if (newHelper != null &&
                newHelper.opcode == Opcodes.NEW &&
                newHelper.desc == STEAMWORKS_HELPER_INTERNAL_NAME
            ) {
                val end = findSteamworksHelperSubscriptionEnd(newHelper)
                if (end != null) {
                    removeInclusive(method.instructions, newHelper, end)
                    return true
                }
            }
            current = next
        }
        return false
    }

    private fun findSteamworksHelperSubscriptionEnd(newHelper: TypeInsnNode): AbstractInsnNode? {
        val dup = nextMeaningful(newHelper) as? InsnNode ?: return null
        if (dup.opcode != Opcodes.DUP) {
            return null
        }
        val init = nextMeaningful(dup) as? MethodInsnNode ?: return null
        if (init.opcode != Opcodes.INVOKESPECIAL ||
            init.owner != STEAMWORKS_HELPER_INTERNAL_NAME ||
            init.name != "<init>" ||
            init.desc != VOID_METHOD_DESC
        ) {
            return null
        }
        val putStatic = nextMeaningful(init) as? FieldInsnNode ?: return null
        if (putStatic.opcode != Opcodes.PUTSTATIC ||
            putStatic.owner != TARGET_CLASS_INTERNAL_NAME ||
            putStatic.name != "steamworksHelper" ||
            putStatic.desc != STEAMWORKS_HELPER_DESC
        ) {
            return null
        }
        val getStatic = nextMeaningful(putStatic) as? FieldInsnNode ?: return null
        if (getStatic.opcode != Opcodes.GETSTATIC ||
            getStatic.owner != TARGET_CLASS_INTERNAL_NAME ||
            getStatic.name != "steamworksHelper" ||
            getStatic.desc != STEAMWORKS_HELPER_DESC
        ) {
            return null
        }
        val subscribe = nextMeaningful(getStatic) as? MethodInsnNode ?: return null
        if (subscribe.opcode != Opcodes.INVOKESTATIC ||
            subscribe.owner != BASEMOD_INTERNAL_NAME ||
            subscribe.name != "subscribe" ||
            subscribe.desc != BASEMOD_SUBSCRIBE_DESC
        ) {
            return null
        }
        return subscribe
    }

    private fun nextMeaningful(node: AbstractInsnNode?): AbstractInsnNode? {
        var current = node?.next
        while (current is LabelNode || current is LineNumberNode || current is FrameNode) {
            current = current.next
        }
        return current
    }

    private fun removeInclusive(
        instructions: org.objectweb.asm.tree.InsnList,
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
        val classWriter = ClassWriter(0)
        classNode.accept(classWriter)
        return classWriter.toByteArray()
    }

    @Throws(IOException::class)
    private fun rewriteJarWithReplacements(modJar: File, replacements: Map<String, ByteArray>) {
        val tempJar = File(modJar.absolutePath + ".chaofanpatch.tmp")
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

            JarFileIoUtils.moveFileReplacing(tempJar, modJar)
        } finally {
            if (tempJar.exists()) {
                tempJar.delete()
            }
        }
    }
}
