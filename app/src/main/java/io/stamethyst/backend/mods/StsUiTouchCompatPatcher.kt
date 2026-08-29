package io.stamethyst.backend.mods

import java.io.IOException
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode

internal object StsUiTouchCompatPatcher {
    private const val TIP_HELPER_INTERNAL_NAME = "com/megacrit/cardcrawl/helpers/TipHelper"
    private const val TIP_HELPER_RENDER_METHOD_NAME = "render"
    private const val TIP_HELPER_RENDER_METHOD_DESC =
        "(Lcom/badlogic/gdx/graphics/g2d/SpriteBatch;)V"
    private const val ABSTRACT_PLAYER_INTERNAL_NAME =
        "com/megacrit/cardcrawl/characters/AbstractPlayer"
    private const val INPUT_HELPER_INTERNAL_NAME =
        "com/megacrit/cardcrawl/helpers/input/InputHelper"
    private const val HITBOX_INTERNAL_NAME =
        "com/megacrit/cardcrawl/helpers/Hitbox"
    private const val INPUT_HELPER_UPDATE_FIRST_METHOD_NAME = "updateFirst"
    private const val INPUT_HELPER_UPDATE_FIRST_METHOD_DESC = "()V"
    private const val HITBOX_BEGIN_PRE_CLICK_HOVER_FRAME_METHOD_NAME =
        "beginPreClickHoverRefreshFrame"
    private const val HITBOX_BEGIN_PRE_CLICK_HOVER_FRAME_METHOD_DESC = "()V"
    private const val HITBOX_DISPATCH_DEFERRED_PRE_CLICK_HOVER_INPUT_METHOD_NAME =
        "dispatchDeferredPreClickHoverInput"
    private const val HITBOX_DISPATCH_DEFERRED_PRE_CLICK_HOVER_INPUT_METHOD_DESC = "()V"
    private const val HITBOX_DEFER_FRESH_PRE_CLICK_HOVER_CLICK_METHOD_NAME =
        "deferFreshPreClickHoverClick"
    private const val HITBOX_DEFER_FRESH_PRE_CLICK_HOVER_CLICK_METHOD_DESC = "()V"
    private const val HITBOX_DEFER_PRE_CLICK_HOVER_RELEASE_METHOD_NAME =
        "deferPreClickHoverReleaseIfNeeded"
    private const val HITBOX_DEFER_PRE_CLICK_HOVER_RELEASE_METHOD_DESC = "()V"
    private const val HITBOX_REFRESH_HOVER_METHOD_NAME = "refreshAllHoveredForFreshClick"
    private const val HITBOX_REFRESH_HOVER_METHOD_DESC = "()V"
    private const val HITBOX_REGISTER_METHOD_NAME = "registerForPreClickHoverRefresh"
    private const val HITBOX_REGISTER_METHOD_DESC = "(Lcom/megacrit/cardcrawl/helpers/Hitbox;)V"
    private const val HITBOX_MARK_UPDATED_METHOD_NAME = "markUpdatedForPreClickHoverRefresh"
    private const val HITBOX_MARK_UPDATED_METHOD_DESC = "(Lcom/megacrit/cardcrawl/helpers/Hitbox;)V"
    private const val HITBOX_CONSTRUCTOR_WITH_POSITION_DESC = "(FFFF)V"
    private const val HITBOX_UPDATE_WITH_POSITION_DESC = "(FF)V"
    private const val JUST_CLICKED_LEFT_FIELD_NAME = "justClickedLeft"
    private const val JUST_RELEASED_CLICK_LEFT_FIELD_NAME = "justReleasedClickLeft"
    private const val DROP_ZONE_HOVER_FIELD_NAME = "isHoveringDropZone"
    private const val TOUCH_MOUSE_DOWN_FIELD_NAME = "isMouseDown"
    private const val BOOLEAN_FIELD_DESC = "Z"
    private val LEGACY_TIP_HELPER_LINE_MAP = linkedMapOf(
        43 to 58,
        44 to 59,
        47 to 64,
        48 to 65,
        49 to 66,
        50 to 67,
        51 to 68,
        53 to 71,
        57 to 73,
        58 to 74,
        59 to 75,
        60 to 76,
        61 to 77,
        63 to 80,
        64 to 81,
        65 to 82,
        72 to 88,
        79 to 94,
        80 to 95,
        81 to 98,
        82 to 104,
        83 to 105,
        85 to 109,
        87 to 112,
        89 to 115
    )

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
        STS_PATCH_HITBOX_CLASS to MergeSpec(
            fields = listOf(
                MemberRef("PRE_CLICK_HOVER_REFRESH_ENABLED_PROP", "Ljava/lang/String;"),
                MemberRef("NATIVE_TOUCHSCREEN_ENABLED_PROP", "Ljava/lang/String;"),
                MemberRef("preClickHoverRefreshEnabled", "Ljava/lang/Boolean;"),
                MemberRef("nativeTouchscreenEnabled", "Ljava/lang/Boolean;"),
                MemberRef("registeredHitboxes", "Ljava/util/ArrayList;"),
                MemberRef("preClickHoverRefreshFrame", "J"),
                MemberRef("deferredPreClickHoverPress", "Z"),
                MemberRef("deferredPreClickHoverRelease", "Z"),
                MemberRef("deferredPreClickHoverPressDeliveredThisFrame", "Z"),
                MemberRef("preClickHoverRefreshLastUpdatedFrame", "J")
            ),
            methods = listOf(
                MemberRef("refreshAllHoveredForFreshClick", "()V"),
                MemberRef("beginPreClickHoverRefreshFrame", "()V"),
                MemberRef("dispatchDeferredPreClickHoverInput", "()V"),
                MemberRef("deferFreshPreClickHoverClick", "()V"),
                MemberRef("deferPreClickHoverReleaseIfNeeded", "()V"),
                MemberRef("registerForPreClickHoverRefresh", "(Lcom/megacrit/cardcrawl/helpers/Hitbox;)V"),
                MemberRef("markUpdatedForPreClickHoverRefresh", "(Lcom/megacrit/cardcrawl/helpers/Hitbox;)V"),
                MemberRef("wasUpdatedInPreviousPreClickHoverFrame", "(Lcom/megacrit/cardcrawl/helpers/Hitbox;)Z"),
                MemberRef("isPreClickHoverRefreshEnabled", "()Z"),
                MemberRef("isPreClickHoverInputDeferralEnabled", "()Z"),
                MemberRef("isNativeTouchscreenEnabled", "()Z"),
                MemberRef("parseBooleanLike", "(Ljava/lang/String;)Ljava/lang/Boolean;"),
                MemberRef("refreshHoveredForFreshClick", "(Lcom/megacrit/cardcrawl/helpers/Hitbox;)V"),
                MemberRef("isPointerInside", "(Lcom/megacrit/cardcrawl/helpers/Hitbox;)Z")
            )
        ),
        STS_PATCH_INPUT_HELPER_CLASS to MergeSpec(
            fields = emptyList(),
            methods = emptyList()
        ),
        STS_PATCH_SINGLE_CARD_VIEW_POPUP_CLASS to MergeSpec(
            fields = emptyList(),
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
                MemberRef("updateUpgradePreview", "()V")
            )
        ),
        STS_PATCH_COLOR_TAB_BAR_CLASS to MergeSpec(
            fields = emptyList(),
            methods = listOf(
                MemberRef("<init>", "(Lcom/megacrit/cardcrawl/screens/mainMenu/TabBarListener;)V"),
                MemberRef("update", "(F)V"),
                MemberRef("toggleViewUpgrade", "()V")
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
        if (entryName == STS_PATCH_INPUT_HELPER_CLASS) {
            return patchInputHelperClass(targetClassBytes)
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

        if (entryName == STS_PATCH_HITBOX_CLASS) {
            patchHitboxConstructors(targetClass)
            patchHitboxUpdateActivity(targetClass)
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

        var changed = false
        if (isLegacyTipHelperRender(renderMethod)) {
            rewriteLegacyTipHelperLineNumbers(renderMethod)
            changed = true
        }

        if (!isTipHelperTouchGuardPatched(renderMethod)) {
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
            changed = true
        }

        if (!changed) {
            return targetClassBytes
        }

        return writeClass(targetClass)
    }

    @Throws(IOException::class)
    private fun patchInputHelperClass(targetClassBytes: ByteArray): ByteArray {
        val targetClass = readClassNode(targetClassBytes)
        if (targetClass.name != INPUT_HELPER_INTERNAL_NAME) {
            throw IOException("Unexpected target class for $STS_PATCH_INPUT_HELPER_CLASS: ${targetClass.name}")
        }

        val updateFirstMethod = targetClass.methods.firstOrNull { method ->
            method.name == INPUT_HELPER_UPDATE_FIRST_METHOD_NAME &&
                method.desc == INPUT_HELPER_UPDATE_FIRST_METHOD_DESC
        } ?: throw IOException("Missing updateFirst method for $STS_PATCH_INPUT_HELPER_CLASS")

        var changed = false
        if (!isInputHelperPreClickHoverFramePatched(updateFirstMethod)) {
            updateFirstMethod.instructions.insert(
                InsnList().apply {
                    add(
                        MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            HITBOX_INTERNAL_NAME,
                            HITBOX_BEGIN_PRE_CLICK_HOVER_FRAME_METHOD_NAME,
                            HITBOX_BEGIN_PRE_CLICK_HOVER_FRAME_METHOD_DESC,
                            false
                        )
                    )
                    add(newDeferredPreClickHoverDispatchInvocation())
                }
            )
            changed = true
        } else if (!isInputHelperDeferredPreClickHoverDispatchPatched(updateFirstMethod)) {
            val frameInvocation = findInputHelperPreClickHoverFrameInvocation(updateFirstMethod)
                ?: throw IOException("Missing InputHelper pre-click hover frame invocation")
            updateFirstMethod.instructions.insert(frameInvocation, newDeferredPreClickHoverDispatchInvocation())
            changed = true
        }

        var freshClickDeferralCount = 0
        var releaseDeferralCount = 0
        var current = updateFirstMethod.instructions.first
        while (current != null) {
            val fieldWrite = current as? FieldInsnNode
            if (fieldWrite != null &&
                isJustClickedLeftWrite(fieldWrite) &&
                isTrueConstant(previousMeaningful(current.previous))
            ) {
                if (replaceOrInsertInputHelperCall(
                        updateFirstMethod,
                        fieldWrite,
                        HITBOX_DEFER_FRESH_PRE_CLICK_HOVER_CLICK_METHOD_NAME,
                        HITBOX_DEFER_FRESH_PRE_CLICK_HOVER_CLICK_METHOD_DESC
                    )
                ) {
                    changed = true
                }
                freshClickDeferralCount++
            } else if (fieldWrite != null &&
                isJustReleasedClickLeftWrite(fieldWrite) &&
                isTrueConstant(previousMeaningful(current.previous))
            ) {
                if (replaceOrInsertInputHelperCall(
                        updateFirstMethod,
                        fieldWrite,
                        HITBOX_DEFER_PRE_CLICK_HOVER_RELEASE_METHOD_NAME,
                        HITBOX_DEFER_PRE_CLICK_HOVER_RELEASE_METHOD_DESC
                    )
                ) {
                    changed = true
                }
                releaseDeferralCount++
            }
            current = current.next
        }

        if (freshClickDeferralCount == 0) {
            throw IOException(
                "Unsupported InputHelper.updateFirst bytecode: justClickedLeft=true write not found"
            )
        }
        if (releaseDeferralCount == 0) {
            throw IOException(
                "Unsupported InputHelper.updateFirst bytecode: justReleasedClickLeft=true write not found"
            )
        }

        return if (changed) writeClass(targetClass) else targetClassBytes
    }

    @Throws(IOException::class)
    private fun patchHitboxConstructors(targetClass: ClassNode) {
        if (targetClass.name != HITBOX_INTERNAL_NAME) {
            throw IOException("Unexpected target class for $STS_PATCH_HITBOX_CLASS: ${targetClass.name}")
        }

        val constructor = targetClass.methods.firstOrNull { method ->
            method.name == "<init>" && method.desc == HITBOX_CONSTRUCTOR_WITH_POSITION_DESC
        } ?: throw IOException("Missing Hitbox constructor $HITBOX_CONSTRUCTOR_WITH_POSITION_DESC")

        if (isHitboxRegistrationPatched(constructor)) {
            return
        }

        val returnInsn = firstReturnInsn(constructor)
            ?: throw IOException("Unsupported Hitbox constructor bytecode: return not found")
        constructor.instructions.insertBefore(
            returnInsn,
            InsnList().apply {
                add(VarInsnNode(Opcodes.ALOAD, 0))
                add(
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        HITBOX_INTERNAL_NAME,
                        HITBOX_REGISTER_METHOD_NAME,
                        HITBOX_REGISTER_METHOD_DESC,
                        false
                    )
                )
            }
        )
    }

    @Throws(IOException::class)
    private fun patchHitboxUpdateActivity(targetClass: ClassNode) {
        val updateMethod = targetClass.methods.firstOrNull { method ->
            method.name == "update" && method.desc == HITBOX_UPDATE_WITH_POSITION_DESC
        } ?: throw IOException("Missing Hitbox update method $HITBOX_UPDATE_WITH_POSITION_DESC")

        if (isHitboxUpdateActivityPatched(updateMethod)) {
            return
        }

        val firstInstruction = updateMethod.instructions.first
            ?: throw IOException("Unsupported Hitbox update bytecode: method is empty")
        updateMethod.instructions.insertBefore(
            firstInstruction,
            InsnList().apply {
                add(VarInsnNode(Opcodes.ALOAD, 0))
                add(
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        HITBOX_INTERNAL_NAME,
                        HITBOX_MARK_UPDATED_METHOD_NAME,
                        HITBOX_MARK_UPDATED_METHOD_DESC,
                        false
                    )
                )
            }
        )
    }

    private fun isLegacyTipHelperRender(renderMethod: MethodNode): Boolean {
        var hasLegacyStartLine = false
        var hasExpectedInsertLine = false
        var current = renderMethod.instructions.first
        while (current != null) {
            val lineNode = current as? LineNumberNode
            if (lineNode != null) {
                if (lineNode.line == 43) {
                    hasLegacyStartLine = true
                } else if (lineNode.line == 104) {
                    hasExpectedInsertLine = true
                }
            }
            current = current.next
        }
        return hasLegacyStartLine || !hasExpectedInsertLine
    }

    private fun rewriteLegacyTipHelperLineNumbers(renderMethod: MethodNode) {
        var current = renderMethod.instructions.first
        while (current != null) {
            val lineNode = current as? LineNumberNode
            if (lineNode != null) {
                val remappedLine = LEGACY_TIP_HELPER_LINE_MAP[lineNode.line]
                if (remappedLine != null) {
                    lineNode.line = remappedLine
                }
            }
            current = current.next
        }
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

    private fun isInputHelperPreClickHoverFramePatched(updateFirstMethod: MethodNode): Boolean {
        return findInputHelperPreClickHoverFrameInvocation(updateFirstMethod) != null
    }

    private fun findInputHelperPreClickHoverFrameInvocation(
        updateFirstMethod: MethodNode
    ): MethodInsnNode? {
        return findHitboxStaticInvocation(
            updateFirstMethod,
            HITBOX_BEGIN_PRE_CLICK_HOVER_FRAME_METHOD_NAME,
            HITBOX_BEGIN_PRE_CLICK_HOVER_FRAME_METHOD_DESC
        )
    }

    private fun isInputHelperDeferredPreClickHoverDispatchPatched(
        updateFirstMethod: MethodNode
    ): Boolean {
        return findHitboxStaticInvocation(
            updateFirstMethod,
            HITBOX_DISPATCH_DEFERRED_PRE_CLICK_HOVER_INPUT_METHOD_NAME,
            HITBOX_DISPATCH_DEFERRED_PRE_CLICK_HOVER_INPUT_METHOD_DESC
        ) != null
    }

    private fun replaceOrInsertInputHelperCall(
        updateFirstMethod: MethodNode,
        fieldWrite: FieldInsnNode,
        methodName: String,
        methodDesc: String
    ): Boolean {
        val nextInvocation = nextMeaningful(fieldWrite.next) as? MethodInsnNode
        if (nextInvocation != null &&
            nextInvocation.opcode == Opcodes.INVOKESTATIC &&
            nextInvocation.owner == HITBOX_INTERNAL_NAME
        ) {
            if (nextInvocation.name == methodName && nextInvocation.desc == methodDesc) {
                return false
            }
            if (nextInvocation.name == HITBOX_REFRESH_HOVER_METHOD_NAME &&
                nextInvocation.desc == HITBOX_REFRESH_HOVER_METHOD_DESC
            ) {
                updateFirstMethod.instructions.set(
                    nextInvocation,
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        HITBOX_INTERNAL_NAME,
                        methodName,
                        methodDesc,
                        false
                    )
                )
                return true
            }
        }
        updateFirstMethod.instructions.insert(
            fieldWrite,
            MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HITBOX_INTERNAL_NAME,
                methodName,
                methodDesc,
                false
            )
        )
        return true
    }

    private fun newDeferredPreClickHoverDispatchInvocation(): MethodInsnNode {
        return MethodInsnNode(
            Opcodes.INVOKESTATIC,
            HITBOX_INTERNAL_NAME,
            HITBOX_DISPATCH_DEFERRED_PRE_CLICK_HOVER_INPUT_METHOD_NAME,
            HITBOX_DISPATCH_DEFERRED_PRE_CLICK_HOVER_INPUT_METHOD_DESC,
            false
        )
    }

    private fun findHitboxStaticInvocation(
        method: MethodNode,
        methodName: String,
        methodDesc: String
    ): MethodInsnNode? {
        var current = method.instructions.first
        while (current != null) {
            val invoke = current as? MethodInsnNode
            if (invoke != null &&
                invoke.opcode == Opcodes.INVOKESTATIC &&
                invoke.owner == HITBOX_INTERNAL_NAME &&
                invoke.name == methodName &&
                invoke.desc == methodDesc
            ) {
                return invoke
            }
            current = current.next
        }
        return null
    }

    private fun isHitboxRegistrationPatched(constructor: MethodNode): Boolean {
        var current = constructor.instructions.first
        while (current != null) {
            val invoke = current as? MethodInsnNode
            if (invoke != null &&
                invoke.opcode == Opcodes.INVOKESTATIC &&
                invoke.owner == HITBOX_INTERNAL_NAME &&
                invoke.name == HITBOX_REGISTER_METHOD_NAME &&
                invoke.desc == HITBOX_REGISTER_METHOD_DESC
            ) {
                return true
            }
            current = current.next
        }
        return false
    }

    private fun isHitboxUpdateActivityPatched(updateMethod: MethodNode): Boolean {
        var current = updateMethod.instructions.first
        while (current != null) {
            val invoke = current as? MethodInsnNode
            if (invoke != null &&
                invoke.opcode == Opcodes.INVOKESTATIC &&
                invoke.owner == HITBOX_INTERNAL_NAME &&
                invoke.name == HITBOX_MARK_UPDATED_METHOD_NAME &&
                invoke.desc == HITBOX_MARK_UPDATED_METHOD_DESC
            ) {
                return true
            }
            current = current.next
        }
        return false
    }

    private fun isJustClickedLeftWrite(fieldInsn: FieldInsnNode?): Boolean {
        return fieldInsn != null &&
            fieldInsn.opcode == Opcodes.PUTSTATIC &&
            fieldInsn.owner == INPUT_HELPER_INTERNAL_NAME &&
            fieldInsn.name == JUST_CLICKED_LEFT_FIELD_NAME &&
            fieldInsn.desc == BOOLEAN_FIELD_DESC
    }

    private fun isJustReleasedClickLeftWrite(fieldInsn: FieldInsnNode?): Boolean {
        return fieldInsn != null &&
            fieldInsn.opcode == Opcodes.PUTSTATIC &&
            fieldInsn.owner == INPUT_HELPER_INTERNAL_NAME &&
            fieldInsn.name == JUST_RELEASED_CLICK_LEFT_FIELD_NAME &&
            fieldInsn.desc == BOOLEAN_FIELD_DESC
    }

    private fun isTrueConstant(node: org.objectweb.asm.tree.AbstractInsnNode?): Boolean {
        return node != null && node.opcode == Opcodes.ICONST_1
    }

    private fun firstReturnInsn(method: MethodNode): org.objectweb.asm.tree.AbstractInsnNode? {
        var current = method.instructions.first
        while (current != null) {
            if (current.opcode == Opcodes.RETURN) {
                return current
            }
            current = current.next
        }
        return null
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
