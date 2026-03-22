package io.stamethyst.backend.mods

import java.io.File
import java.util.IdentityHashMap
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

class StsUiTouchCompatPatcherTest {
    @Test
    fun mergePatchedClass_tipHelper_keepsOriginalLineNumbersAndAddsMouseDownGuard() {
        val originalJar = resolveFixtureFile(
            "tools/desktop-1.0.jar",
            "../tools/desktop-1.0.jar"
        )
        val patchJar = resolveFixtureFile(
            "patches/gdx-patch/build/libs/gdx-patch.jar",
            "../patches/gdx-patch/build/libs/gdx-patch.jar"
        )
        assumeTrue(originalJar.isFile)
        assumeTrue(patchJar.isFile)

        val originalBytes = readJarEntry(originalJar, STS_PATCH_TIP_HELPER_CLASS)
        val donorBytes = readJarEntry(patchJar, STS_PATCH_TIP_HELPER_CLASS)

        val patchedBytes = StsUiTouchCompatPatcher.mergePatchedClass(
            entryName = STS_PATCH_TIP_HELPER_CLASS,
            targetClassBytes = originalBytes,
            donorClassBytes = donorBytes
        )

        assertFalse("new patch should not be a whole-class donor copy", patchedBytes.contentEquals(donorBytes))
        val originalRender = findMethod(originalBytes, "render", "(Lcom/badlogic/gdx/graphics/g2d/SpriteBatch;)V")
        val patchedRender = findMethod(patchedBytes, "render", "(Lcom/badlogic/gdx/graphics/g2d/SpriteBatch;)V")

        assertEquals(lineNumbers(originalRender), lineNumbers(patchedRender))
        assertFalse(
            containsFieldAccess(
                originalRender,
                Opcodes.GETSTATIC,
                "com/megacrit/cardcrawl/helpers/input/InputHelper",
                "isMouseDown",
                "Z"
            )
        )
        assertTrue(
            containsFieldAccess(
                patchedRender,
                Opcodes.GETSTATIC,
                "com/megacrit/cardcrawl/helpers/input/InputHelper",
                "isMouseDown",
                "Z"
            )
        )

        val patchedAgain = StsUiTouchCompatPatcher.mergePatchedClass(
            entryName = STS_PATCH_TIP_HELPER_CLASS,
            targetClassBytes = patchedBytes,
            donorClassBytes = donorBytes
        )
        assertArrayEquals(patchedBytes, patchedAgain)
    }

    @Test
    fun mergePatchedClass_singleCardViewPopup_keepsDescriptionMethodOriginal() {
        val originalJar = resolveFixtureFile(
            "tools/desktop-1.0.jar",
            "../tools/desktop-1.0.jar"
        )
        val patchJar = resolveFixtureFile(
            "patches/gdx-patch/build/libs/gdx-patch.jar",
            "../patches/gdx-patch/build/libs/gdx-patch.jar"
        )
        assumeTrue(originalJar.isFile)
        assumeTrue(patchJar.isFile)

        val originalBytes = readJarEntry(originalJar, STS_PATCH_SINGLE_CARD_VIEW_POPUP_CLASS)
        val donorBytes = readJarEntry(patchJar, STS_PATCH_SINGLE_CARD_VIEW_POPUP_CLASS)

        val patchedBytes = StsUiTouchCompatPatcher.mergePatchedClass(
            entryName = STS_PATCH_SINGLE_CARD_VIEW_POPUP_CLASS,
            targetClassBytes = originalBytes,
            donorClassBytes = donorBytes
        )

        assertFalse("new patch should not be a whole-class donor copy", patchedBytes.contentEquals(donorBytes))
        assertEquals(
            methodFingerprint(findMethod(originalBytes, "renderDescriptionCN", "(Lcom/badlogic/gdx/graphics/g2d/SpriteBatch;)V")),
            methodFingerprint(findMethod(patchedBytes, "renderDescriptionCN", "(Lcom/badlogic/gdx/graphics/g2d/SpriteBatch;)V"))
        )
        assertEquals(
            methodFingerprint(findMethod(donorBytes, "updateUpgradePreview", "()V")),
            methodFingerprint(findMethod(patchedBytes, "updateUpgradePreview", "()V"))
        )

        val patchedAgain = StsUiTouchCompatPatcher.mergePatchedClass(
            entryName = STS_PATCH_SINGLE_CARD_VIEW_POPUP_CLASS,
            targetClassBytes = patchedBytes,
            donorClassBytes = donorBytes
        )
        assertArrayEquals(patchedBytes, patchedAgain)
    }

    @Test
    fun mergePatchedClass_colorTabBar_keepsRenderMethodOriginalAndIsIdempotent() {
        val originalJar = resolveFixtureFile(
            "tools/desktop-1.0.jar",
            "../tools/desktop-1.0.jar"
        )
        val patchJar = resolveFixtureFile(
            "patches/gdx-patch/build/libs/gdx-patch.jar",
            "../patches/gdx-patch/build/libs/gdx-patch.jar"
        )
        assumeTrue(originalJar.isFile)
        assumeTrue(patchJar.isFile)

        val originalBytes = readJarEntry(originalJar, STS_PATCH_COLOR_TAB_BAR_CLASS)
        val donorBytes = readJarEntry(patchJar, STS_PATCH_COLOR_TAB_BAR_CLASS)

        val patchedBytes = StsUiTouchCompatPatcher.mergePatchedClass(
            entryName = STS_PATCH_COLOR_TAB_BAR_CLASS,
            targetClassBytes = originalBytes,
            donorClassBytes = donorBytes
        )

        assertFalse("new patch should not be a whole-class donor copy", patchedBytes.contentEquals(donorBytes))
        assertEquals(
            methodFingerprint(findMethod(originalBytes, "renderViewUpgrade", "(Lcom/badlogic/gdx/graphics/g2d/SpriteBatch;F)V")),
            methodFingerprint(findMethod(patchedBytes, "renderViewUpgrade", "(Lcom/badlogic/gdx/graphics/g2d/SpriteBatch;F)V"))
        )
        assertEquals(
            methodFingerprint(findMethod(donorBytes, "update", "(F)V")),
            methodFingerprint(findMethod(patchedBytes, "update", "(F)V"))
        )

        val patchedAgain = StsUiTouchCompatPatcher.mergePatchedClass(
            entryName = STS_PATCH_COLOR_TAB_BAR_CLASS,
            targetClassBytes = patchedBytes,
            donorClassBytes = donorBytes
        )
        assertArrayEquals(patchedBytes, patchedAgain)
    }

    private fun resolveFixtureFile(vararg candidates: String): File {
        return candidates
            .asSequence()
            .map(::File)
            .firstOrNull { it.isFile }
            ?: File(candidates.first())
    }

    private fun readJarEntry(jarFile: File, entryName: String): ByteArray {
        ZipFile(jarFile).use { zipFile ->
            val entry = zipFile.getEntry(entryName)
            requireNotNull(entry) { "Missing entry $entryName in ${jarFile.absolutePath}" }
            return JarFileIoUtils.readEntryBytes(zipFile, entry)
        }
    }

    private fun findMethod(classBytes: ByteArray, name: String, desc: String): MethodNode {
        val classNode = ClassNode()
        ClassReader(classBytes).accept(classNode, ClassReader.SKIP_FRAMES)
        return requireNotNull(
            classNode.methods.firstOrNull { method -> method.name == name && method.desc == desc }
        ) {
            "Missing method $name$desc"
        }
    }

    private fun lineNumbers(method: MethodNode): List<Int> {
        val lineNumbers = ArrayList<Int>()
        var current: AbstractInsnNode? = method.instructions.first
        while (current != null) {
            if (current is LineNumberNode) {
                lineNumbers.add(current.line)
            }
            current = current.next
        }
        return lineNumbers
    }

    private fun containsFieldAccess(
        method: MethodNode,
        opcode: Int,
        owner: String,
        name: String,
        desc: String
    ): Boolean {
        var current: AbstractInsnNode? = method.instructions.first
        while (current != null) {
            val fieldInsn = current as? FieldInsnNode
            if (fieldInsn != null &&
                fieldInsn.opcode == opcode &&
                fieldInsn.owner == owner &&
                fieldInsn.name == name &&
                fieldInsn.desc == desc
            ) {
                return true
            }
            current = current.next
        }
        return false
    }

    private fun methodFingerprint(method: MethodNode): String {
        val labelIds = IdentityHashMap<LabelNode, Int>()
        var nextLabelId = 0
        fun labelId(label: LabelNode): Int {
            val existing = labelIds[label]
            if (existing != null) {
                return existing
            }
            val assigned = nextLabelId++
            labelIds[label] = assigned
            return assigned
        }

        val builder = StringBuilder()
        var current: AbstractInsnNode? = method.instructions.first
        while (current != null) {
            when (current) {
                is LabelNode -> builder.append("label ").append(labelId(current))
                is LineNumberNode -> {
                    builder.append("line ")
                        .append(current.line)
                        .append('@')
                        .append(labelId(current.start))
                }
                is FieldInsnNode -> {
                    builder.append("field ")
                        .append(current.opcode)
                        .append(' ')
                        .append(current.owner)
                        .append(' ')
                        .append(current.name)
                        .append(' ')
                        .append(current.desc)
                }
                is IincInsnNode -> {
                    builder.append("iinc ")
                        .append(current.`var`)
                        .append(' ')
                        .append(current.incr)
                }
                is InsnNode -> builder.append("insn ").append(current.opcode)
                is IntInsnNode -> {
                    builder.append("int ")
                        .append(current.opcode)
                        .append(' ')
                        .append(current.operand)
                }
                is JumpInsnNode -> {
                    builder.append("jump ")
                        .append(current.opcode)
                        .append(' ')
                        .append(labelId(current.label))
                }
                is LdcInsnNode -> builder.append("ldc ").append(current.cst)
                is LookupSwitchInsnNode -> {
                    builder.append("lookupswitch ")
                        .append(current.keys.joinToString(","))
                        .append(" default=")
                        .append(labelId(current.dflt))
                        .append(" labels=")
                        .append(current.labels.joinToString(",") { label -> labelId(label as LabelNode).toString() })
                }
                is MethodInsnNode -> {
                    builder.append("invoke ")
                        .append(current.opcode)
                        .append(' ')
                        .append(current.owner)
                        .append(' ')
                        .append(current.name)
                        .append(' ')
                        .append(current.desc)
                        .append(' ')
                        .append(current.itf)
                }
                is MultiANewArrayInsnNode -> {
                    builder.append("multianewarray ")
                        .append(current.desc)
                        .append(' ')
                        .append(current.dims)
                }
                is TableSwitchInsnNode -> {
                    builder.append("tableswitch ")
                        .append(current.min)
                        .append(' ')
                        .append(current.max)
                        .append(" default=")
                        .append(labelId(current.dflt))
                        .append(" labels=")
                        .append(current.labels.joinToString(",") { label -> labelId(label as LabelNode).toString() })
                }
                is TypeInsnNode -> {
                    builder.append("type ")
                        .append(current.opcode)
                        .append(' ')
                        .append(current.desc)
                }
                is VarInsnNode -> {
                    builder.append("var ")
                        .append(current.opcode)
                        .append(' ')
                        .append(current.`var`)
                }
                else -> {
                    builder.append("node ")
                        .append(current.type)
                        .append(' ')
                        .append(current.opcode)
                }
            }
            builder.append('\n')
            current = current.next
        }

        builder.append("locals\n")
        method.localVariables?.forEach { local ->
            builder.append(local.name)
                .append(' ')
                .append(local.desc)
                .append(' ')
                .append(local.index)
                .append(' ')
                .append(labelId(local.start))
                .append(' ')
                .append(labelId(local.end))
                .append('\n')
        }

        builder.append("trycatch\n")
        method.tryCatchBlocks?.forEach { block ->
            builder.append(labelId(block.start))
                .append(' ')
                .append(labelId(block.end))
                .append(' ')
                .append(labelId(block.handler))
                .append(' ')
                .append(block.type)
                .append('\n')
        }

        builder.append("maxs ")
            .append(method.maxStack)
            .append(' ')
            .append(method.maxLocals)
            .append('\n')
        return builder.toString()
    }
}
