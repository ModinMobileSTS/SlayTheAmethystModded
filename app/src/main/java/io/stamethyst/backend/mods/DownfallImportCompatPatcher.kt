package io.stamethyst.backend.mods

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode

internal data class DownfallImportCompatPatchResult(
    val patchedClassEntries: Int,
    val patchedMerchantClassEntries: Int,
    val patchedHexaghostBodyClassEntries: Int
)

internal object DownfallImportCompatPatcher {
    private const val CHAR_BOSS_MERCHANT_ENTRY = "charbosses/bosses/Merchant/CharBossMerchant.class"
    private const val FLEEING_MERCHANT_ENTRY = "downfall/monsters/FleeingMerchant.class"
    private const val HEXAGHOST_MY_BODY_ENTRY = "theHexaghost/vfx/MyBody.class"

    private const val SETTINGS_INTERNAL_NAME = "com/megacrit/cardcrawl/core/Settings"
    private const val DRAW_X_FIELD_NAME = "drawX"
    private const val DRAW_X_FIELD_DESC = "F"
    private const val MY_BODY_RENDER_METHOD_NAME = "render"
    private const val MY_BODY_RENDER_METHOD_DESC =
        "(Lcom/badlogic/gdx/graphics/g2d/SpriteBatch;)V"
    private const val ORIGINAL_MERCHANT_DRAW_X = 1260.0f
    private const val MERCHANT_DRAW_X_WIDTH_RATIO = 0.75f
    private const val MERCHANT_DRAW_X_OFFSET_XSCALE = 180.0f
    private const val MY_BODY_X_OFFSET = 270.0f
    private const val MY_BODY_Y_OFFSET = 256.0f
    private const val FLOAT_TOLERANCE = 0.0001f

    @Throws(IOException::class)
    fun patchInPlace(modJar: File): DownfallImportCompatPatchResult {
        if (!modJar.isFile) {
            throw IOException("Mod jar not found: ${modJar.absolutePath}")
        }

        var patchedMerchantClassEntries = 0
        var patchedHexaghostBodyClassEntries = 0
        val replacements = LinkedHashMap<String, ByteArray>()

        ZipFile(modJar).use { zipFile ->
            patchClassEntry(zipFile, CHAR_BOSS_MERCHANT_ENTRY) { classBytes ->
                patchMerchantClassBytes(classBytes)
            }?.let { (entryName, patchedBytes) ->
                replacements[entryName] = patchedBytes
                patchedMerchantClassEntries++
            }

            patchClassEntry(zipFile, FLEEING_MERCHANT_ENTRY) { classBytes ->
                patchMerchantClassBytes(classBytes)
            }?.let { (entryName, patchedBytes) ->
                replacements[entryName] = patchedBytes
                patchedMerchantClassEntries++
            }

            patchClassEntry(zipFile, HEXAGHOST_MY_BODY_ENTRY) { classBytes ->
                patchMyBodyClassBytes(classBytes)
            }?.let { (entryName, patchedBytes) ->
                replacements[entryName] = patchedBytes
                patchedHexaghostBodyClassEntries++
            }
        }

        if (replacements.isNotEmpty()) {
            rewriteJarWithReplacements(modJar, replacements)
        }

        return DownfallImportCompatPatchResult(
            patchedClassEntries = replacements.size,
            patchedMerchantClassEntries = patchedMerchantClassEntries,
            patchedHexaghostBodyClassEntries = patchedHexaghostBodyClassEntries
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
    private fun patchMerchantClassBytes(classBytes: ByteArray): ByteArray? {
        val classNode = readClassNode(classBytes)
        val patched = classNode.methods.any { method ->
            method.name == "<init>" && patchMerchantDrawXAssignment(classNode.name, method)
        }
        if (!patched) {
            return null
        }
        return writeClass(classNode)
    }

    private fun patchMerchantDrawXAssignment(ownerInternalName: String, method: MethodNode): Boolean {
        var current: AbstractInsnNode? = method.instructions.first
        while (current != null) {
            val next = current.next
            if (current is FieldInsnNode &&
                current.opcode == Opcodes.PUTFIELD &&
                current.owner == ownerInternalName &&
                current.name == DRAW_X_FIELD_NAME &&
                current.desc == DRAW_X_FIELD_DESC
            ) {
                val multiplyNode = previousMeaningful(current)
                val scaleNode = previousMeaningful(multiplyNode)
                val constantNode = previousMeaningful(scaleNode)
                val receiverNode = previousMeaningful(constantNode)
                if (receiverNode is VarInsnNode &&
                    receiverNode.opcode == Opcodes.ALOAD &&
                    receiverNode.`var` == 0 &&
                    constantNode is LdcInsnNode &&
                    isFloatConstant(constantNode.cst, ORIGINAL_MERCHANT_DRAW_X) &&
                    scaleNode is FieldInsnNode &&
                    scaleNode.opcode == Opcodes.GETSTATIC &&
                    scaleNode.owner == SETTINGS_INTERNAL_NAME &&
                    scaleNode.name == "scale" &&
                    scaleNode.desc == "F" &&
                    multiplyNode?.opcode == Opcodes.FMUL
                ) {
                    method.instructions.insertBefore(
                        receiverNode,
                        buildMerchantDrawXInstructions(ownerInternalName)
                    )
                    removeInclusive(method.instructions, receiverNode, current)
                    return true
                }
            }
            current = next
        }
        return false
    }

    private fun buildMerchantDrawXInstructions(ownerInternalName: String): InsnList {
        return InsnList().apply {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(FieldInsnNode(Opcodes.GETSTATIC, SETTINGS_INTERNAL_NAME, "WIDTH", "I"))
            add(InsnNode(Opcodes.I2F))
            add(LdcInsnNode(MERCHANT_DRAW_X_WIDTH_RATIO))
            add(InsnNode(Opcodes.FMUL))
            add(LdcInsnNode(MERCHANT_DRAW_X_OFFSET_XSCALE))
            add(FieldInsnNode(Opcodes.GETSTATIC, SETTINGS_INTERNAL_NAME, "xScale", "F"))
            add(InsnNode(Opcodes.FMUL))
            add(InsnNode(Opcodes.FSUB))
            add(FieldInsnNode(Opcodes.PUTFIELD, ownerInternalName, DRAW_X_FIELD_NAME, DRAW_X_FIELD_DESC))
        }
    }

    @Throws(IOException::class)
    private fun patchMyBodyClassBytes(classBytes: ByteArray): ByteArray? {
        val classNode = readClassNode(classBytes)
        val renderMethod = classNode.methods.firstOrNull { method ->
            method.name == MY_BODY_RENDER_METHOD_NAME &&
                method.desc == MY_BODY_RENDER_METHOD_DESC
        } ?: return null

        if (!patchMyBodyRenderOffsets(renderMethod)) {
            return null
        }
        return writeClass(classNode)
    }

    private fun patchMyBodyRenderOffsets(method: MethodNode): Boolean {
        var patched = false
        var current: AbstractInsnNode? = method.instructions.first
        while (current != null) {
            val next = current.next
            if (current is LdcInsnNode &&
                shouldScaleMyBodyOffset(current) &&
                nextMeaningful(current)?.opcode == Opcodes.FSUB
            ) {
                method.instructions.insert(
                    current,
                    InsnList().apply {
                        add(FieldInsnNode(Opcodes.GETSTATIC, SETTINGS_INTERNAL_NAME, "scale", "F"))
                        add(InsnNode(Opcodes.FMUL))
                    }
                )
                patched = true
            }
            current = next
        }
        return patched
    }

    private fun shouldScaleMyBodyOffset(node: LdcInsnNode): Boolean {
        return isFloatConstant(node.cst, MY_BODY_X_OFFSET) ||
            isFloatConstant(node.cst, MY_BODY_Y_OFFSET)
    }

    private fun isFloatConstant(value: Any?, expected: Float): Boolean {
        val actual = value as? Float ?: return false
        return abs(actual - expected) <= FLOAT_TOLERANCE
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
        val classWriter = ClassWriter(0)
        classNode.accept(classWriter)
        return classWriter.toByteArray()
    }

    @Throws(IOException::class)
    private fun rewriteJarWithReplacements(modJar: File, replacements: Map<String, ByteArray>) {
        val tempJar = File(modJar.absolutePath + ".downfallpatch.tmp")
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
