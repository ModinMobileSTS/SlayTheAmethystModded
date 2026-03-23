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
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode

internal data class DownfallImportCompatPatchResult(
    val patchedClassEntries: Int,
    val patchedMerchantClassEntries: Int,
    val patchedHexaghostBodyClassEntries: Int,
    val patchedBossMechanicPanelClassEntries: Int
)

internal object DownfallImportCompatPatcher {
    private const val BOSS_MECHANIC_PANEL_ENTRY = "charbosses/BossMechanicDisplayPanel.class"
    private const val CHAR_BOSS_MERCHANT_ENTRY = "charbosses/bosses/Merchant/CharBossMerchant.class"
    private const val FLEEING_MERCHANT_ENTRY = "downfall/monsters/FleeingMerchant.class"
    private const val HEXAGHOST_MY_BODY_ENTRY = "theHexaghost/vfx/MyBody.class"

    private const val EASY_INFO_DISPLAY_PANEL_INTERNAL_NAME = "automaton/EasyInfoDisplayPanel"
    private const val EASY_INFO_DISPLAY_PANEL_CTOR_DESC = "(FFF)V"
    private const val ABSTRACT_PLAYER_INTERNAL_NAME = "com/megacrit/cardcrawl/characters/AbstractPlayer"
    private const val SETTINGS_INTERNAL_NAME = "com/megacrit/cardcrawl/core/Settings"
    private const val SETTINGS_WIDTH_FIELD_NAME = "WIDTH"
    private const val SETTINGS_HEIGHT_FIELD_NAME = "HEIGHT"
    private const val SETTINGS_SCALE_FIELD_NAME = "scale"
    private const val SETTINGS_RENDER_SCALE_FIELD_NAME = "renderScale"
    private const val DRAW_X_FIELD_NAME = "drawX"
    private const val DRAW_X_FIELD_DESC = "F"
    private const val MY_BODY_RENDER_METHOD_NAME = "render"
    private const val MY_BODY_RENDER_METHOD_DESC =
        "(Lcom/badlogic/gdx/graphics/g2d/SpriteBatch;)V"
    private const val MY_BODY_BODY_OFFSET_Y_FIELD_NAME = "BODY_OFFSET_Y"
    private const val MY_BODY_FINE_X_OFFSET_SMALL = 6.0f
    private const val MY_BODY_FINE_X_OFFSET_LARGE = 12.0f
    private const val ORIGINAL_MERCHANT_DRAW_X = 1260.0f
    private const val MERCHANT_RUG_WIDTH_RATIO = 0.5f
    private const val MERCHANT_RUG_LEFT_OFFSET = 34.0f
    private const val MERCHANT_RUG_CENTER_OFFSET = 256.0f
    private const val MY_BODY_X_OFFSET = 270.0f
    private const val LEGACY_MY_BODY_X_OFFSET = 256.0f
    private const val MY_BODY_Y_OFFSET = 256.0f
    private const val LEGACY_MY_BODY_Y_OFFSET_TOO_HIGH = 244.0f
    private const val LEGACY_MY_BODY_Y_OFFSET_TOO_LOW = 268.0f
    private const val BOSS_PANEL_X_RATIO = 0.9f
    private const val BOSS_PANEL_Y_RATIO = 0.51f
    private const val BOSS_PANEL_WIDTH_RATIO = 0.15f
    private const val FLOAT_TOLERANCE = 0.0001f

    @Throws(IOException::class)
    fun patchInPlace(modJar: File): DownfallImportCompatPatchResult {
        if (!modJar.isFile) {
            throw IOException("Mod jar not found: ${modJar.absolutePath}")
        }

        var patchedBossMechanicPanelClassEntries = 0
        var patchedMerchantClassEntries = 0
        var patchedHexaghostBodyClassEntries = 0
        val replacements = LinkedHashMap<String, ByteArray>()

        ZipFile(modJar).use { zipFile ->
            patchClassEntry(zipFile, BOSS_MECHANIC_PANEL_ENTRY) { classBytes ->
                patchBossMechanicPanelClassBytes(classBytes)
            }?.let { (entryName, patchedBytes) ->
                replacements[entryName] = patchedBytes
                patchedBossMechanicPanelClassEntries++
            }

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
            patchedHexaghostBodyClassEntries = patchedHexaghostBodyClassEntries,
            patchedBossMechanicPanelClassEntries = patchedBossMechanicPanelClassEntries
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

    @Throws(IOException::class)
    private fun patchBossMechanicPanelClassBytes(classBytes: ByteArray): ByteArray? {
        val classNode = readClassNode(classBytes)
        val patched = classNode.methods.any { method ->
            method.name == "<init>" &&
                method.desc == "()V" &&
                patchBossMechanicPanelConstructor(classNode.name, method)
        }
        if (!patched) {
            return null
        }
        return writeClass(classNode)
    }

    private fun patchBossMechanicPanelConstructor(ownerInternalName: String, method: MethodNode): Boolean {
        if (usesRelativeBossMechanicPanelLayout(ownerInternalName, method)) {
            return false
        }
        val hasPanelCoordinateReads = method.instructions.toArray().any { node ->
            node is FieldInsnNode &&
                node.opcode == Opcodes.GETSTATIC &&
                (
                    (node.owner == ownerInternalName &&
                        node.name in setOf("X", "Y", "WIDTH") &&
                        node.desc == "I") ||
                        (node.owner == SETTINGS_INTERNAL_NAME &&
                            node.name in setOf(SETTINGS_WIDTH_FIELD_NAME, SETTINGS_HEIGHT_FIELD_NAME) &&
                            node.desc == "I")
                    )
        }
        val hasEasyInfoPanelSuperCall = method.instructions.toArray().any { node ->
            node is MethodInsnNode &&
                node.opcode == Opcodes.INVOKESPECIAL &&
                node.owner == EASY_INFO_DISPLAY_PANEL_INTERNAL_NAME &&
                node.name == "<init>" &&
                node.desc == EASY_INFO_DISPLAY_PANEL_CTOR_DESC
        }
        if (!hasPanelCoordinateReads || !hasEasyInfoPanelSuperCall) {
            return false
        }
        replaceMethodInstructions(method, buildBossMechanicPanelConstructorInstructions())
        method.maxLocals = maxOf(method.maxLocals, 1)
        method.maxStack = maxOf(method.maxStack, 4)
        return true
    }

    private fun usesRelativeBossMechanicPanelLayout(ownerInternalName: String, method: MethodNode): Boolean {
        val instructions = method.instructions.toArray().toList()
        val hasLegacyPanelReads = instructions.any { node ->
            node is FieldInsnNode &&
                node.opcode == Opcodes.GETSTATIC &&
                node.owner == ownerInternalName &&
                node.name in setOf("X", "Y", "WIDTH") &&
                node.desc == "I"
        }
        val hasWidthRead = instructions.any { node ->
            node is FieldInsnNode &&
                node.opcode == Opcodes.GETSTATIC &&
                node.owner == SETTINGS_INTERNAL_NAME &&
                node.name == SETTINGS_WIDTH_FIELD_NAME &&
                node.desc == "I"
        }
        val hasHeightRead = instructions.any { node ->
            node is FieldInsnNode &&
                node.opcode == Opcodes.GETSTATIC &&
                node.owner == SETTINGS_INTERNAL_NAME &&
                node.name == SETTINGS_HEIGHT_FIELD_NAME &&
                node.desc == "I"
        }
        val hasXRatio = instructions.any { node ->
            node is LdcInsnNode && isFloatConstant(node.cst, BOSS_PANEL_X_RATIO)
        }
        val hasYRatio = instructions.any { node ->
            node is LdcInsnNode && isFloatConstant(node.cst, BOSS_PANEL_Y_RATIO)
        }
        val hasWidthRatio = instructions.any { node ->
            node is LdcInsnNode && isFloatConstant(node.cst, BOSS_PANEL_WIDTH_RATIO)
        }
        return !hasLegacyPanelReads &&
            hasWidthRead &&
            hasHeightRead &&
            hasXRatio &&
            hasYRatio &&
            hasWidthRatio
    }

    private fun buildMerchantDrawXInstructions(ownerInternalName: String): InsnList {
        return InsnList().apply {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(FieldInsnNode(Opcodes.GETSTATIC, SETTINGS_INTERNAL_NAME, SETTINGS_WIDTH_FIELD_NAME, "I"))
            add(InsnNode(Opcodes.I2F))
            add(LdcInsnNode(MERCHANT_RUG_WIDTH_RATIO))
            add(InsnNode(Opcodes.FMUL))
            add(LdcInsnNode(MERCHANT_RUG_LEFT_OFFSET))
            add(FieldInsnNode(Opcodes.GETSTATIC, SETTINGS_INTERNAL_NAME, "scale", "F"))
            add(InsnNode(Opcodes.FMUL))
            add(InsnNode(Opcodes.FADD))
            add(LdcInsnNode(MERCHANT_RUG_CENTER_OFFSET))
            add(FieldInsnNode(Opcodes.GETSTATIC, SETTINGS_INTERNAL_NAME, "scale", "F"))
            add(InsnNode(Opcodes.FMUL))
            add(InsnNode(Opcodes.FADD))
            add(FieldInsnNode(Opcodes.PUTFIELD, ownerInternalName, DRAW_X_FIELD_NAME, DRAW_X_FIELD_DESC))
        }
    }

    private fun buildBossMechanicPanelConstructorInstructions(): InsnList {
        return InsnList().apply {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(FieldInsnNode(Opcodes.GETSTATIC, SETTINGS_INTERNAL_NAME, SETTINGS_WIDTH_FIELD_NAME, "I"))
            add(InsnNode(Opcodes.I2F))
            add(LdcInsnNode(BOSS_PANEL_X_RATIO))
            add(InsnNode(Opcodes.FMUL))
            add(FieldInsnNode(Opcodes.GETSTATIC, SETTINGS_INTERNAL_NAME, SETTINGS_HEIGHT_FIELD_NAME, "I"))
            add(InsnNode(Opcodes.I2F))
            add(LdcInsnNode(BOSS_PANEL_Y_RATIO))
            add(InsnNode(Opcodes.FMUL))
            add(FieldInsnNode(Opcodes.GETSTATIC, SETTINGS_INTERNAL_NAME, SETTINGS_WIDTH_FIELD_NAME, "I"))
            add(InsnNode(Opcodes.I2F))
            add(LdcInsnNode(BOSS_PANEL_WIDTH_RATIO))
            add(InsnNode(Opcodes.FMUL))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    EASY_INFO_DISPLAY_PANEL_INTERNAL_NAME,
                    "<init>",
                    EASY_INFO_DISPLAY_PANEL_CTOR_DESC,
                    false
                )
            )
            add(InsnNode(Opcodes.RETURN))
        }
    }

    @Throws(IOException::class)
    private fun patchMyBodyClassBytes(classBytes: ByteArray): ByteArray? {
        val classNode = readClassNode(classBytes)
        val renderMethod = classNode.methods.firstOrNull { method ->
            method.name == MY_BODY_RENDER_METHOD_NAME &&
                method.desc == MY_BODY_RENDER_METHOD_DESC
        } ?: return null

        var patched = false
        if (patchMyBodyScaleReads(classNode)) {
            patched = true
        }
        if (patchMyBodyRenderOffsets(classNode.name, renderMethod)) {
            patched = true
        }
        if (!patched) {
            return null
        }
        return writeClass(classNode)
    }

    private fun patchMyBodyScaleReads(classNode: ClassNode): Boolean {
        var patched = false
        classNode.methods.forEach { method ->
            var current: AbstractInsnNode? = method.instructions.first
            while (current != null) {
                val next = current.next
                if (current is FieldInsnNode &&
                    current.opcode == Opcodes.GETSTATIC &&
                    current.owner == SETTINGS_INTERNAL_NAME &&
                    current.name == SETTINGS_SCALE_FIELD_NAME &&
                    current.desc == "F"
                ) {
                    current.name = SETTINGS_RENDER_SCALE_FIELD_NAME
                    patched = true
                }
                current = next
            }
        }
        return patched
    }

    private fun patchMyBodyRenderOffsets(ownerInternalName: String, method: MethodNode): Boolean {
        var patched = false
        var current: AbstractInsnNode? = method.instructions.first
        while (current != null) {
            if (current is LdcInsnNode) {
                if (normalizeMyBodyXAnchor(current, method.instructions)) {
                    patched = true
                }
                if (normalizeMyBodyXFineOffsetSign(current, method.instructions)) {
                    patched = true
                }
                if (normalizeMyBodyYOffset(ownerInternalName, current, method.instructions)) {
                    patched = true
                }
            }
            current = nextMeaningful(current)
        }
        return patched
    }

    private fun normalizeMyBodyXAnchor(node: LdcInsnNode, instructions: InsnList): Boolean {
        if (!isFloatConstant(node.cst, MY_BODY_X_OFFSET) &&
            !isFloatConstant(node.cst, LEGACY_MY_BODY_X_OFFSET)
        ) {
            return false
        }
        val (fSubNode, scaleNode, scaleMulNode) = findScaledOrPlainFSub(node) ?: return false
        if (findFieldAccessAfter(
                start = fSubNode,
                owner = ABSTRACT_PLAYER_INTERNAL_NAME,
                name = "animX",
                desc = "F",
                maxHops = 2
            ) == null
        ) {
            return false
        }
        var patched = false
        if (!isFloatConstant(node.cst, MY_BODY_X_OFFSET)) {
            node.cst = MY_BODY_X_OFFSET
            patched = true
        }
        if (scaleNode != null && scaleMulNode != null) {
            instructions.remove(scaleNode)
            instructions.remove(scaleMulNode)
            patched = true
        }
        return patched
    }

    private fun normalizeMyBodyXFineOffsetSign(node: LdcInsnNode, instructions: InsnList): Boolean {
        if (!isFloatConstant(node.cst, MY_BODY_FINE_X_OFFSET_SMALL) &&
            !isFloatConstant(node.cst, MY_BODY_FINE_X_OFFSET_LARGE)
        ) {
            return false
        }
        val scaleNode = nextMeaningful(node)
        val scaleMulNode = nextMeaningful(scaleNode)
        val arithmeticNode = nextMeaningful(scaleMulNode)
        val prevNode = previousMeaningful(node)
        val prevPrevNode = previousMeaningful(prevNode)
        if (scaleNode !is FieldInsnNode ||
            scaleNode.opcode != Opcodes.GETSTATIC ||
            scaleNode.owner != SETTINGS_INTERNAL_NAME ||
            !isScaleFieldName(scaleNode.name) ||
            scaleNode.desc != "F" ||
            scaleMulNode?.opcode != Opcodes.FMUL ||
            arithmeticNode?.opcode !in setOf(Opcodes.FADD, Opcodes.FSUB) ||
            prevNode?.opcode != Opcodes.FADD ||
            prevPrevNode !is FieldInsnNode ||
            prevPrevNode.owner != ABSTRACT_PLAYER_INTERNAL_NAME ||
            prevPrevNode.name != "animX" ||
            prevPrevNode.desc != "F"
        ) {
            return false
        }
        val resolvedArithmeticNode = arithmeticNode ?: return false
        if (resolvedArithmeticNode.opcode == Opcodes.FSUB) {
            return false
        }
        instructions.set(resolvedArithmeticNode, InsnNode(Opcodes.FSUB))
        return true
    }

    private fun normalizeMyBodyYOffset(
        ownerInternalName: String,
        node: LdcInsnNode,
        instructions: InsnList
    ): Boolean {
        if (!isFloatConstant(node.cst, MY_BODY_Y_OFFSET) &&
            !isFloatConstant(node.cst, LEGACY_MY_BODY_Y_OFFSET_TOO_HIGH) &&
            !isFloatConstant(node.cst, LEGACY_MY_BODY_Y_OFFSET_TOO_LOW)
        ) {
            return false
        }
        val (fSubNode, scaleNode, scaleMulNode) = findScaledOrPlainFSub(node) ?: return false
        val nextAfterFSub = nextMeaningful(fSubNode)
        if (nextAfterFSub !is FieldInsnNode ||
            nextAfterFSub.owner != ownerInternalName ||
            nextAfterFSub.name != MY_BODY_BODY_OFFSET_Y_FIELD_NAME ||
            nextAfterFSub.desc != "F"
        ) {
            return false
        }
        var patched = false
        if (!isFloatConstant(node.cst, MY_BODY_Y_OFFSET)) {
            node.cst = MY_BODY_Y_OFFSET
            patched = true
        }
        if (scaleNode != null && scaleMulNode != null) {
            instructions.remove(scaleNode)
            instructions.remove(scaleMulNode)
            patched = true
        }
        return patched
    }

    private data class ArithmeticPattern(
        val arithmeticNode: AbstractInsnNode,
        val scaleNode: FieldInsnNode?,
        val scaleMulNode: AbstractInsnNode?
    )

    private fun findFieldAccessAfter(
        start: AbstractInsnNode?,
        owner: String,
        name: String,
        desc: String,
        maxHops: Int
    ): FieldInsnNode? {
        var current = start
        repeat(maxHops) {
            current = nextMeaningful(current)
            val fieldNode = current as? FieldInsnNode ?: return@repeat
            if (fieldNode.owner == owner && fieldNode.name == name && fieldNode.desc == desc) {
                return fieldNode
            }
        }
        return null
    }

    private fun findScaledOrPlainFSub(node: LdcInsnNode): ArithmeticPattern? {
        val next = nextMeaningful(node)
        if (next?.opcode == Opcodes.FSUB) {
            return ArithmeticPattern(next, null, null)
        }
        if (next is FieldInsnNode &&
            next.opcode == Opcodes.GETSTATIC &&
            next.owner == SETTINGS_INTERNAL_NAME &&
            isScaleFieldName(next.name) &&
            next.desc == "F"
        ) {
            val multiplyNode = nextMeaningful(next)
            val fSubNode = nextMeaningful(multiplyNode)
            if (multiplyNode?.opcode == Opcodes.FMUL && fSubNode?.opcode == Opcodes.FSUB) {
                return ArithmeticPattern(fSubNode, next, multiplyNode)
            }
        }
        return null
    }

    private fun isScaleFieldName(name: String): Boolean {
        return name == SETTINGS_SCALE_FIELD_NAME || name == SETTINGS_RENDER_SCALE_FIELD_NAME
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

    private fun replaceMethodInstructions(method: MethodNode, newInstructions: InsnList) {
        method.instructions.toArray().forEach { node ->
            method.instructions.remove(node)
        }
        method.tryCatchBlocks.clear()
        method.localVariables?.clear()
        method.visibleLocalVariableAnnotations?.clear()
        method.invisibleLocalVariableAnnotations?.clear()
        method.instructions.add(newInstructions)
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
