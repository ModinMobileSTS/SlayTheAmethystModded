package io.stamethyst.backend.mods.importing.patches.mods.superfastmode

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.JumpInsnNode

class SuperFastModeImportCompatPatcherTest {
    @Test fun `patches supplied SuperFastMode jar without changing the original`() {
        val original = sequenceOf(
            File("../agent-tmp/SuperFastMode.jar"),
            File("agent-tmp/SuperFastMode.jar")
        ).firstOrNull { it.isFile } ?: return
        val target = Files.createTempFile("superfastmode-patch-test", ".jar").toFile()
        try {
            original.copyTo(target, overwrite = true)
            assertEquals(true, SuperFastModeImportCompatPatcher.patchInPlace(target))
            assertEquals(false, SuperFastModeImportCompatPatcher.patchInPlace(target))
            val entry = "skrelpoid/superfastmode/patches/MathUtilsPatches\$LerpPatch.class"
            ZipFile(target).use { zip ->
                val bytes = zip.getInputStream(zip.getEntry(entry)).use { it.readBytes() }
                assertNull(SuperFastModeImportCompatPatcher.patchClassBytes(bytes))
                assertNotNull(zip.getEntry("skrelpoid/superfastmode/patches/BossRewardLerpGuard.class"))
            }
            ZipFile(original).use { source ->
                ZipFile(target).use { patched ->
                    val entries = source.entries()
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement().name
                        if (name == entry) continue
                        assertEquals(source.getEntry(name) != null, patched.getEntry(name) != null)
                        if (!source.getEntry(name).isDirectory) {
                            assertEquals(
                                source.getInputStream(source.getEntry(name)).use { it.readBytes().toList() },
                                patched.getInputStream(patched.getEntry(name)).use { it.readBytes().toList() }
                            )
                        }
                    }
                }
            }
        } finally {
            target.delete()
        }
    }

    @Test fun `only the expected instant lerp branch is guarded`() {
        val patched = SuperFastModeImportCompatPatcher.patchClassBytes(fixture())
        assertNotNull(patched)
        val node = ClassNode()
        ClassReader(patched).accept(node, 0)
        val instructions = node.methods.single { it.name == "Replace" }.instructions.toArray()
        val flag = instructions.filterIsInstance<FieldInsnNode>().single {
            it.owner == "skrelpoid/superfastmode/patches/MathUtilsPatches\$LerpPatch"
        }
        assertEquals("bossRewardUpdating", flag.name)
        assertEquals(Opcodes.IFNE, (flag.next as JumpInsnNode).opcode)
        assertEquals(false, instructions.filterIsInstance<FieldInsnNode>().any {
            it.owner == "com/megacrit/cardcrawl/dungeons/AbstractDungeon"
        })
        assertNull(SuperFastModeImportCompatPatcher.patchClassBytes(patched!!))
    }

    @Test fun `guard only writes its own flag without reading dungeon state`() {
        val guard = ClassNode()
        ClassReader(SuperFastModeImportCompatPatcher.createGuardClass()).accept(guard, 0)
        assertEquals(setOf("Prefix", "Postfix"), guard.methods.map { it.name }.toSet())
        assertEquals(true, guard.methods.flatMap { it.instructions.toArray().toList() }
            .filterIsInstance<FieldInsnNode>().all {
                it.owner == "skrelpoid/superfastmode/patches/MathUtilsPatches\$LerpPatch" &&
                    it.opcode == Opcodes.PUTSTATIC
            })
    }

    @Test fun `unknown class is not changed`() {
        assertNull(SuperFastModeImportCompatPatcher.patchClassBytes(fixture("other/Mod")))
    }

    private fun fixture(name: String = "skrelpoid/superfastmode/patches/MathUtilsPatches\$LerpPatch"): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "Replace", "(FFF)F", null, null)
        val end = org.objectweb.asm.Label()
        method.visitCode()
        method.visitVarInsn(Opcodes.FLOAD, 1)
        method.visitVarInsn(Opcodes.FSTORE, 3)
        method.visitFieldInsn(Opcodes.GETSTATIC, "skrelpoid/superfastmode/SuperFastMode", "isInstantLerp", "Z")
        method.visitJumpInsn(Opcodes.IFEQ, end)
        method.visitVarInsn(Opcodes.FLOAD, 1)
        method.visitVarInsn(Opcodes.FSTORE, 3)
        method.visitLabel(end)
        method.visitVarInsn(Opcodes.FLOAD, 3)
        method.visitInsn(Opcodes.FRETURN)
        method.visitMaxs(1, 4)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }
}
