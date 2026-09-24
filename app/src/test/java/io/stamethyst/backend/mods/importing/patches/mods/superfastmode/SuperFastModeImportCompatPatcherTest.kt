package io.stamethyst.backend.mods.importing.patches.mods.superfastmode

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.JumpInsnNode

class SuperFastModeImportCompatPatcherTest {
    private val entry = "skrelpoid/superfastmode/patches/MathUtilsPatches\$LerpPatch.class"

    @Test fun `original lerp class is unchanged and second import is a no-op`() {
        val original = sequenceOf(File("../agent-tmp/SuperFastMode.jar"), File("agent-tmp/SuperFastMode.jar"))
            .firstOrNull { it.isFile } ?: error("Place the original SuperFastMode.jar in agent-tmp")
        val target = Files.createTempFile("superfastmode-patch-test", ".jar").toFile()
        try {
            original.copyTo(target, overwrite = true)
            assertTrue(SuperFastModeImportCompatPatcher.patchInPlace(target))
            assertFalse(SuperFastModeImportCompatPatcher.patchInPlace(target))
            ZipFile(original).use { source ->
                ZipFile(target).use { patched ->
                    assertNotNull(patched.getEntry("skrelpoid/superfastmode/patches/BossRewardLerpGuard.class"))
                    val entries = source.entries()
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement().name
                        assertNotNull(patched.getEntry(name))
                        if (!source.getEntry(name).isDirectory) {
                            assertArrayEquals(
                                source.getInputStream(source.getEntry(name)).use { it.readBytes() },
                                patched.getInputStream(patched.getEntry(name)).use { it.readBytes() }
                            )
                        }
                    }
                }
            }
        } finally {
            target.delete()
        }
    }

    @Test fun `legacy v2 inline field and references are removed during migration`() {
        val legacy = legacyV2Fixture()
        val repaired = SuperFastModeImportCompatPatcher.inspectClassBytes(legacy)?.replacement
        assertNotNull(repaired)
        val node = ClassNode()
        ClassReader(repaired).accept(node, 0)
        assertFalse(node.fields.any { it.name == "bossRewardUpdating" })
        val accesses = node.methods.single { it.name == "Replace" }.instructions.toArray()
            .filterIsInstance<FieldInsnNode>()
        assertEquals(listOf("isInstantLerp"), accesses.map { it.name })
        assertEquals("skrelpoid/superfastmode/SuperFastMode", accesses.single().owner)
        assertNull(SuperFastModeImportCompatPatcher.inspectClassBytes(legacyV2Fixture("other/Mod")))
    }

    @Test fun `boss guard saves and restores the existing switch and never reads dungeon state`() {
        val node = ClassNode()
        ClassReader(SuperFastModeImportCompatPatcher.createGuardClass()).accept(node, 0)
        assertEquals(setOf("Prefix", "Postfix"), node.methods.map { it.name }.toSet())
        val prefix = node.methods.single { it.name == "Prefix" }.instructions.toArray().filterIsInstance<FieldInsnNode>()
        val postfix = node.methods.single { it.name == "Postfix" }.instructions.toArray().filterIsInstance<FieldInsnNode>()
        assertTrue(node.fields.isEmpty())
        assertEquals(listOf("isInstantLerp", "isInstantLerp"), prefix.map { it.name })
        assertEquals(listOf("isInstantLerp"), postfix.map { it.name })
        assertTrue((prefix + postfix).none { it.owner.contains("AbstractDungeon") })
    }

    private fun legacyV2Fixture(
        name: String = "skrelpoid/superfastmode/patches/MathUtilsPatches\$LerpPatch"
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "bossRewardUpdating", "Z", null, null).visitEnd()
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "Replace", "(FFF)F", null, null)
        val skip = org.objectweb.asm.Label()
        method.visitCode()
        method.visitVarInsn(Opcodes.FLOAD, 0)
        method.visitVarInsn(Opcodes.FLOAD, 1)
        method.visitVarInsn(Opcodes.FLOAD, 2)
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "skrelpoid/superfastmode/patches/MathUtilsPatches", "lerp", "(FFF)F", false)
        method.visitVarInsn(Opcodes.FSTORE, 3)
        method.visitFieldInsn(Opcodes.GETSTATIC, name, "bossRewardUpdating", "Z")
        method.visitJumpInsn(Opcodes.IFNE, skip)
        method.visitFieldInsn(Opcodes.GETSTATIC, "skrelpoid/superfastmode/SuperFastMode", "isInstantLerp", "Z")
        method.visitJumpInsn(Opcodes.IFEQ, skip)
        method.visitVarInsn(Opcodes.FLOAD, 1)
        method.visitVarInsn(Opcodes.FSTORE, 3)
        method.visitLabel(skip)
        method.visitVarInsn(Opcodes.FLOAD, 3)
        method.visitInsn(Opcodes.FRETURN)
        method.visitMaxs(3, 4)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }
}
