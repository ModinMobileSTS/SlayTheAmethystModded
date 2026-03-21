package io.stamethyst.backend.mods

import java.io.IOException
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode

internal object StsUiTouchCompatPatcher {
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

    private fun writeClass(classNode: ClassNode): ByteArray {
        val classWriter = ClassWriter(0)
        classNode.accept(classWriter)
        return classWriter.toByteArray()
    }
}
