package io.stamethyst.backend.mods

import java.io.IOException
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.MethodNode

internal object StsUiTouchCompatPatcher {
    private const val TIP_HELPER_INTERNAL_NAME = "com/megacrit/cardcrawl/helpers/TipHelper"
    private const val TIP_HELPER_RENDER_METHOD_NAME = "render"
    private const val TIP_HELPER_RENDER_METHOD_DESC =
        "(Lcom/badlogic/gdx/graphics/g2d/SpriteBatch;)V"
    private const val ABSTRACT_PLAYER_INTERNAL_NAME =
        "com/megacrit/cardcrawl/characters/AbstractPlayer"
    private const val INPUT_HELPER_INTERNAL_NAME =
        "com/megacrit/cardcrawl/helpers/input/InputHelper"
    private const val DROP_ZONE_HOVER_FIELD_NAME = "isHoveringDropZone"
    private const val TOUCH_MOUSE_DOWN_FIELD_NAME = "isMouseDown"
    private const val BOOLEAN_FIELD_DESC = "Z"

    private data class MemberRef(
        val name: String,
        val desc: String
    )

    private data class MergeSpec(
        val fields: List<MemberRef>,
        val methods: List<MemberRef>
    )

    private val mergeSpecs: Map<String, MergeSpec> = linkedMapOf(
        STS_PATCH_TIP_HELPER_CLASS to MergeSpec(
            fields = emptyList(),
            methods = listOf(
                MemberRef("render", "(Lcom/badlogic/gdx/graphics/g2d/SpriteBatch;)V")
            )
        ),
        STS_PATCH_SINGLE_CARD_VIEW_POPUP_CLASS to MergeSpec(
            fields = listOf(
                MemberRef("COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED_PROP", "Ljava/lang/String;"),
                MemberRef("DEFAULT_BOTTOM_TOGGLE_W", "F"),
                MemberRef("DEFAULT_BOTTOM_TOGGLE_H", "F"),
                MemberRef("DEFAULT_BOTTOM_TOGGLE_CENTER_Y", "F"),
                MemberRef("TOUCH_BOTTOM_TOGGLE_W", "F"),
                MemberRef("TOUCH_BOTTOM_TOGGLE_H", "F"),
                MemberRef("TOUCH_BOTTOM_TOGGLE_CENTER_Y", "F"),
                MemberRef("compendiumUpgradeTouchFixEnabled", "Ljava/lang/Boolean;")
            ),
            methods = listOf(
                MemberRef(
                    "open",
                    "(Lcom/megacrit/cardcrawl/cards/AbstractCard;" +
                        "Lcom/megacrit/cardcrawl/cards/CardGroup;)V"
                ),
                MemberRef(
                    "open",
                    "(Lcom/megacrit/cardcrawl/cards/AbstractCard;)V"
                ),
                MemberRef("close", "()V"),
                MemberRef("update", "()V"),
                MemberRef("updateUpgradePreview", "()V"),
                MemberRef("refreshBottomToggleHitboxes", "()V"),
                MemberRef("getBottomToggleWidth", "()F"),
                MemberRef("getBottomToggleHeight", "()F"),
                MemberRef("getBottomToggleCenterY", "()F"),
                MemberRef("isCompendiumUpgradeTouchFixActive", "()Z"),
                MemberRef("isCompendiumUpgradeTouchFixEnabled", "()Z"),
                MemberRef("parseBooleanLike", "(Ljava/lang/String;)Ljava/lang/Boolean;")
            )
        ),
        STS_PATCH_COLOR_TAB_BAR_CLASS to MergeSpec(
            fields = listOf(
                MemberRef("COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED_PROP", "Ljava/lang/String;"),
                MemberRef("VIEW_UPGRADE_HB_W", "F"),
                MemberRef("VIEW_UPGRADE_HB_H", "F"),
                MemberRef("TOUCH_VIEW_UPGRADE_HB_W", "F"),
                MemberRef("TOUCH_VIEW_UPGRADE_HB_H", "F"),
                MemberRef("TOUCH_VIEW_UPGRADE_HB_CENTER_Y_OFFSET", "F"),
                MemberRef("compendiumUpgradeTouchFixEnabled", "Ljava/lang/Boolean;")
            ),
            methods = listOf(
                MemberRef("update", "(F)V"),
                MemberRef("toggleViewUpgrade", "()V"),
                MemberRef("updateViewUpgradeHitbox", "(F)V"),
                MemberRef("getViewUpgradeHitboxWidth", "()F"),
                MemberRef("getViewUpgradeHitboxHeight", "()F"),
                MemberRef("getViewUpgradeHitboxCenterYOffset", "()F"),
                MemberRef("isCompendiumUpgradeTouchFixActive", "()Z"),
                MemberRef("isCompendiumUpgradeTouchFixEnabled", "()Z"),
                MemberRef("parseBooleanLike", "(Ljava/lang/String;)Ljava/lang/Boolean;")
            )
        )
    )

    fun isMethodMergeClassEntry(entryName: String): Boolean = mergeSpecs.containsKey(entryName)

    @Throws(IOException::class)
    fun mergePatchedClass(
        entryName: String,
        targetClassBytes: ByteArray,
        donorClassBytes: ByteArray
    ): ByteArray {
        if (entryName == STS_PATCH_TIP_HELPER_CLASS) {
            return patchTipHelperClass(targetClassBytes)
        }

        val mergeSpec = mergeSpecs[entryName]
            ?: return targetClassBytes

        val targetClass = readClassNode(targetClassBytes)
        val donorClass = readClassNode(donorClassBytes)
        if (targetClass.name != donorClass.name) {
            throw IOException(
                "Mismatched donor class for $entryName: " +
                    "target=${targetClass.name}, donor=${donorClass.name}"
            )
        }

        mergeSpec.fields.forEach { member ->
            val donorField = donorClass.fields.firstOrNull { field ->
                field.name == member.name && field.desc == member.desc
            } ?: throw IOException("Missing donor field for $entryName: ${member.name}${member.desc}")
            val targetIndex = targetClass.fields.indexOfFirst { field ->
                field.name == member.name && field.desc == member.desc
            }
            if (targetIndex >= 0) {
                targetClass.fields[targetIndex] = donorField
            } else {
                targetClass.fields.add(donorField)
            }
        }

        mergeSpec.methods.forEach { member ->
            val donorMethod = donorClass.methods.firstOrNull { method ->
                method.name == member.name && method.desc == member.desc
            } ?: throw IOException("Missing donor method for $entryName: ${member.name}${member.desc}")
            val targetIndex = targetClass.methods.indexOfFirst { method ->
                method.name == member.name && method.desc == member.desc
            }
            if (targetIndex >= 0) {
                targetClass.methods[targetIndex] = donorMethod
            } else {
                targetClass.methods.add(donorMethod)
            }
        }

        return writeClass(targetClass)
    }

    private fun readClassNode(classBytes: ByteArray): ClassNode {
        val classNode = ClassNode()
        ClassReader(classBytes).accept(classNode, 0)
        return classNode
    }

    @Throws(IOException::class)
    private fun patchTipHelperClass(targetClassBytes: ByteArray): ByteArray {
        val targetClass = readClassNode(targetClassBytes)
        if (targetClass.name != TIP_HELPER_INTERNAL_NAME) {
            throw IOException("Unexpected target class for $STS_PATCH_TIP_HELPER_CLASS: ${targetClass.name}")
        }

        val renderMethod = targetClass.methods.firstOrNull { method ->
            method.name == TIP_HELPER_RENDER_METHOD_NAME &&
                method.desc == TIP_HELPER_RENDER_METHOD_DESC
        } ?: throw IOException("Missing render method for $STS_PATCH_TIP_HELPER_CLASS")

        if (isTipHelperTouchGuardPatched(renderMethod)) {
            return targetClassBytes
        }

        val hoverGuardJump = findTipHelperDropZoneHoverGuard(renderMethod)
            ?: throw IOException("Unsupported TipHelper.render bytecode: drop-zone hover guard not found")
        renderMethod.instructions.insert(
            hoverGuardJump,
            InsnList().apply {
                add(
                    FieldInsnNode(
                        Opcodes.GETSTATIC,
                        INPUT_HELPER_INTERNAL_NAME,
                        TOUCH_MOUSE_DOWN_FIELD_NAME,
                        BOOLEAN_FIELD_DESC
                    )
                )
                add(JumpInsnNode(Opcodes.IFEQ, hoverGuardJump.label))
            }
        )

        return writeClass(targetClass)
    }

    private fun isTipHelperTouchGuardPatched(renderMethod: MethodNode): Boolean {
        val hoverGuardJump = findTipHelperDropZoneHoverGuard(renderMethod) ?: return false
        val mouseDownField = nextMeaningful(hoverGuardJump.next) as? FieldInsnNode ?: return false
        if (mouseDownField.opcode != Opcodes.GETSTATIC ||
            mouseDownField.owner != INPUT_HELPER_INTERNAL_NAME ||
            mouseDownField.name != TOUCH_MOUSE_DOWN_FIELD_NAME ||
            mouseDownField.desc != BOOLEAN_FIELD_DESC
        ) {
            return false
        }

        val mouseDownJump = nextMeaningful(mouseDownField.next) as? JumpInsnNode ?: return false
        return mouseDownJump.opcode == Opcodes.IFEQ && mouseDownJump.label === hoverGuardJump.label
    }

    private fun findTipHelperDropZoneHoverGuard(renderMethod: MethodNode): JumpInsnNode? {
        var current = renderMethod.instructions.first
        while (current != null) {
            val jump = current as? JumpInsnNode
            if (jump != null && jump.opcode == Opcodes.IFEQ) {
                val hoverField = previousMeaningful(jump.previous) as? FieldInsnNode
                if (hoverField != null &&
                    hoverField.owner == ABSTRACT_PLAYER_INTERNAL_NAME &&
                    hoverField.name == DROP_ZONE_HOVER_FIELD_NAME &&
                    hoverField.desc == BOOLEAN_FIELD_DESC
                ) {
                    return jump
                }
            }
            current = current.next
        }
        return null
    }

    private fun previousMeaningful(node: org.objectweb.asm.tree.AbstractInsnNode?) =
        walkMeaningful(node, forward = false)

    private fun nextMeaningful(node: org.objectweb.asm.tree.AbstractInsnNode?) =
        walkMeaningful(node, forward = true)

    private fun walkMeaningful(
        start: org.objectweb.asm.tree.AbstractInsnNode?,
        forward: Boolean
    ): org.objectweb.asm.tree.AbstractInsnNode? {
        var current = start
        while (current != null) {
            if (current.opcode >= 0) {
                return current
            }
            current = if (forward) current.next else current.previous
        }
        return null
    }

    private fun writeClass(classNode: ClassNode): ByteArray {
        val classWriter = ClassWriter(0)
        classNode.accept(classWriter)
        return classWriter.toByteArray()
    }
}
