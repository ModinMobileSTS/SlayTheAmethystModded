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
    fun patchInPlace_removesWorkshopSubscriptionGuardAndIsIdempotent() {
        val tempDir = Files.createTempDirectory("vupshion-patcher-test")
        val jarFile = tempDir.resolve("VUPShionMod.jar").toFile()
        createVupShionJar(jarFile)

        val firstPatch = VupShionModCompatPatcher.patchInPlace(jarFile)
        assertTrue(firstPatch.patchedWebButtonConstructor)
        assertFalse(hasWorkshopSubscriptionGuard(jarFile))
        assertTrue(hasUrlAssignment(jarFile))

        val secondPatch = VupShionModCompatPatcher.patchInPlace(jarFile)
        assertFalse(secondPatch.patchedWebButtonConstructor)
        assertFalse(hasWorkshopSubscriptionGuard(jarFile))
        assertTrue(hasUrlAssignment(jarFile))
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
    }

    private fun createVupShionJar(jarFile: File) {
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("VUPShionMod/ui/WebButton.class"))
            zipOut.write(buildWebButtonClassBytes())
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
