package io.stamethyst.backend.mods

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode

class VupShionModCompatPatcherTest {
    @Test
    fun patchInPlace_appliesAllKnownCompatibilityFixesAndIsIdempotent() {
        val tempDir = Files.createTempDirectory("vupshion-patcher-test")
        val jarFile = tempDir.resolve("VUPShionMod.jar").toFile()
        createVupShionJar(jarFile)

        val firstPatch = VupShionModCompatPatcher.patchInPlace(jarFile)
        assertTrue(firstPatch.patchedWebButtonConstructor)
        assertTrue(firstPatch.patchedSaveTenkoDataMethod)
        assertTrue(firstPatch.patchedSaveSleyntaPanelMethod)
        assertTrue(firstPatch.hasAnyPatch)
        assertFalse(hasWorkshopSubscriptionGuard(jarFile))
        assertTrue(hasUrlAssignment(jarFile))
        assertTrue(hasSaveTenkoPanelGuard(jarFile))
        assertTrue(hasSaveSleyntaPanelGuard(jarFile))

        val secondPatch = VupShionModCompatPatcher.patchInPlace(jarFile)
        assertFalse(secondPatch.patchedWebButtonConstructor)
        assertFalse(secondPatch.patchedSaveTenkoDataMethod)
        assertFalse(secondPatch.patchedSaveSleyntaPanelMethod)
        assertFalse(secondPatch.hasAnyPatch)
        assertFalse(hasWorkshopSubscriptionGuard(jarFile))
        assertTrue(hasUrlAssignment(jarFile))
        assertTrue(hasSaveTenkoPanelGuard(jarFile))
        assertTrue(hasSaveSleyntaPanelGuard(jarFile))
    }

    @Test
    fun patchInPlace_returnsFalseWhenTargetClassIsMissing() {
        val tempDir = Files.createTempDirectory("vupshion-patcher-empty")
        val jarFile = tempDir.resolve("OtherMod.jar").toFile()
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("example/Placeholder.class"))
            zipOut.write(byteArrayOf(0x00))
            zipOut.closeEntry()
        }

        val patchResult = VupShionModCompatPatcher.patchInPlace(jarFile)
        assertFalse(patchResult.patchedWebButtonConstructor)
        assertFalse(patchResult.patchedSaveTenkoDataMethod)
        assertFalse(patchResult.patchedSaveSleyntaPanelMethod)
        assertFalse(patchResult.hasAnyPatch)
    }

    private fun createVupShionJar(jarFile: File) {
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("VUPShionMod/ui/WebButton.class"))
            zipOut.write(buildWebButtonClassBytes())
            zipOut.closeEntry()
            zipOut.putNextEntry(ZipEntry("VUPShionMod/util/SaveHelper.class"))
            zipOut.write(buildSaveHelperClassBytes())
            zipOut.closeEntry()
        }
    }

    private fun buildWebButtonClassBytes(): ByteArray {
        val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        classWriter.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "VUPShionMod/ui/WebButton",
            null,
            "java/lang/Object",
            null
        )
        classWriter.visitField(Opcodes.ACC_PUBLIC, "url", "Ljava/lang/String;", null, null).visitEnd()

        val constructor = classWriter.visitMethod(
            Opcodes.ACC_PUBLIC,
            "<init>",
            "(Ljava/lang/String;FFFFFLcom/badlogic/gdx/graphics/Color;" +
                "Lcom/badlogic/gdx/graphics/Texture;)V",
            null,
            null
        )
        val afterGuard = org.objectweb.asm.Label()
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        constructor.visitTypeInsn(Opcodes.NEW, "com/codedisaster/steamworks/SteamApps")
        constructor.visitInsn(Opcodes.DUP)
        constructor.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "com/codedisaster/steamworks/SteamApps",
            "<init>",
            "()V",
            false
        )
        constructor.visitVarInsn(Opcodes.ASTORE, 9)
        constructor.visitVarInsn(Opcodes.ALOAD, 9)
        constructor.visitLdcInsn(646570)
        constructor.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "com/codedisaster/steamworks/SteamApps",
            "isSubscribedApp",
            "(I)Z",
            false
        )
        constructor.visitJumpInsn(Opcodes.IFNE, afterGuard)
        constructor.visitTypeInsn(Opcodes.NEW, "VUPShionMod/msic/NullApiException")
        constructor.visitInsn(Opcodes.DUP)
        constructor.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "VUPShionMod/msic/NullApiException",
            "<init>",
            "()V",
            false
        )
        constructor.visitInsn(Opcodes.ATHROW)
        constructor.visitLabel(afterGuard)
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitVarInsn(Opcodes.ALOAD, 1)
        constructor.visitFieldInsn(
            Opcodes.PUTFIELD,
            "VUPShionMod/ui/WebButton",
            "url",
            "Ljava/lang/String;"
        )
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(0, 0)
        constructor.visitEnd()

        classWriter.visitEnd()
        return classWriter.toByteArray()
    }

    private fun buildSaveHelperClassBytes(): ByteArray {
        val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        classWriter.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "VUPShionMod/util/SaveHelper",
            null,
            "java/lang/Object",
            null
        )

        classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        classWriter.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "saveTenkoData", "()V", null, null).apply {
            visitCode()
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "VUPShionMod/ui/TenkoPanel/TenkoPanel",
                "getPanel",
                "()LVUPShionMod/ui/TenkoPanel/TenkoPanel;",
                false
            )
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitFieldInsn(
                Opcodes.GETFIELD,
                "VUPShionMod/ui/TenkoPanel/TenkoPanel",
                "spL",
                "I"
            )
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        classWriter.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "saveSleyntaPanel",
            "()V",
            null,
            null
        ).apply {
            visitCode()
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "VUPShionMod/ui/SleyntaUI/CriticalShotPanel",
                "getPanel",
                "()LVUPShionMod/ui/SleyntaUI/CriticalShotPanel;",
                false
            )
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "VUPShionMod/ui/SleyntaUI/CriticalShotPanel",
                "getBaseCriticalDamageRate",
                "()F",
                false
            )
            visitInsn(Opcodes.POP)
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "VUPShionMod/ui/SleyntaUI/StarDustPanel",
                "getPanel",
                "()LVUPShionMod/ui/SleyntaUI/StarDustPanel;",
                false
            )
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "VUPShionMod/ui/SleyntaUI/StarDustPanel",
                "getAmount",
                "()I",
                false
            )
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        classWriter.visitEnd()
        return classWriter.toByteArray()
    }

    private fun hasWorkshopSubscriptionGuard(jarFile: File): Boolean {
        val constructor = readWebButtonConstructor(jarFile)
        return meaningfulInstructions(constructor).any { node ->
            (node is MethodInsnNode &&
                node.owner == "com/codedisaster/steamworks/SteamApps" &&
                node.name == "isSubscribedApp" &&
                node.desc == "(I)Z") ||
                (node is TypeInsnNode &&
                    node.opcode == Opcodes.NEW &&
                    node.desc == "VUPShionMod/msic/NullApiException")
        }
    }

    private fun hasUrlAssignment(jarFile: File): Boolean {
        val constructor = readWebButtonConstructor(jarFile)
        val instructions = meaningfulInstructions(constructor)
        return instructions.zipWithNext().any { (first, second) ->
            first.opcode == Opcodes.ALOAD &&
                second is org.objectweb.asm.tree.FieldInsnNode &&
                second.opcode == Opcodes.PUTFIELD &&
                second.owner == "VUPShionMod/ui/WebButton" &&
                second.name == "url" &&
                second.desc == "Ljava/lang/String;"
        }
    }

    private fun hasSaveTenkoPanelGuard(jarFile: File): Boolean {
        val method = readSaveHelperMethod(jarFile, "saveTenkoData")
        return hasOverlayEnergyPanelGuard(method) && hasLocalNullGuard(method, 1)
    }

    private fun hasSaveSleyntaPanelGuard(jarFile: File): Boolean {
        val method = readSaveHelperMethod(jarFile, "saveSleyntaPanel")
        return hasOverlayEnergyPanelGuard(method) &&
            hasLocalNullGuard(method, 1) &&
            hasLocalNullGuard(method, 2)
    }

    private fun readWebButtonConstructor(jarFile: File): MethodNode {
        val classBytes = JarFileIoUtils.readJarEntryBytes(jarFile, "VUPShionMod/ui/WebButton.class")
        assertNotNull(classBytes)
        val classNode = ClassNode()
        ClassReader(classBytes!!).accept(classNode, 0)
        val constructor = classNode.methods.firstOrNull { method ->
            method.name == "<init>" &&
                method.desc ==
                "(Ljava/lang/String;FFFFFLcom/badlogic/gdx/graphics/Color;" +
                "Lcom/badlogic/gdx/graphics/Texture;)V"
        }
        assertNotNull(constructor)
        return constructor!!
    }

    private fun readSaveHelperMethod(jarFile: File, methodName: String): MethodNode {
        val classBytes = JarFileIoUtils.readJarEntryBytes(jarFile, "VUPShionMod/util/SaveHelper.class")
        assertNotNull(classBytes)
        val classNode = ClassNode()
        ClassReader(classBytes!!).accept(classNode, 0)
        val method = classNode.methods.firstOrNull { candidate ->
            candidate.name == methodName && candidate.desc == "()V"
        }
        assertNotNull(method)
        return method!!
    }

    private fun hasOverlayEnergyPanelGuard(method: MethodNode): Boolean {
        val instructions = meaningfulInstructions(method)
        if (instructions.size < 6) {
            return false
        }
        val firstGetStatic = instructions[0] as? org.objectweb.asm.tree.FieldInsnNode ?: return false
        val firstJump = instructions[1] as? org.objectweb.asm.tree.JumpInsnNode ?: return false
        val secondGetStatic = instructions[3] as? org.objectweb.asm.tree.FieldInsnNode ?: return false
        val getField = instructions[4] as? org.objectweb.asm.tree.FieldInsnNode ?: return false
        val secondJump = instructions[5] as? org.objectweb.asm.tree.JumpInsnNode ?: return false
        return firstGetStatic.opcode == Opcodes.GETSTATIC &&
            firstGetStatic.owner == "com/megacrit/cardcrawl/dungeons/AbstractDungeon" &&
            firstGetStatic.name == "overlayMenu" &&
            firstJump.opcode == Opcodes.IFNONNULL &&
            secondGetStatic.opcode == Opcodes.GETSTATIC &&
            secondGetStatic.owner == "com/megacrit/cardcrawl/dungeons/AbstractDungeon" &&
            secondGetStatic.name == "overlayMenu" &&
            getField.opcode == Opcodes.GETFIELD &&
            getField.owner == "com/megacrit/cardcrawl/core/OverlayMenu" &&
            getField.name == "energyPanel" &&
            secondJump.opcode == Opcodes.IFNONNULL
    }

    private fun hasLocalNullGuard(method: MethodNode, localIndex: Int): Boolean {
        val instructions = meaningfulInstructions(method)
        return instructions.zipWithNext().any { (first, second) ->
            first is org.objectweb.asm.tree.VarInsnNode &&
                first.opcode == Opcodes.ALOAD &&
                first.`var` == localIndex &&
                second is org.objectweb.asm.tree.JumpInsnNode &&
                second.opcode == Opcodes.IFNONNULL
        }
    }

    private fun meaningfulInstructions(method: MethodNode): List<AbstractInsnNode> {
        val result = ArrayList<AbstractInsnNode>()
        var current: AbstractInsnNode? = method.instructions.first
        while (current != null) {
            if (current !is LabelNode && current !is LineNumberNode && current !is FrameNode) {
                result += current
            }
            current = current.next
        }
        return result
    }
}
