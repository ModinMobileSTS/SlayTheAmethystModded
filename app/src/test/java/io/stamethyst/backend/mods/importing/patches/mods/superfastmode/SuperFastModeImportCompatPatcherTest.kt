package io.stamethyst.backend.mods.importing.patches.mods.superfastmode

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
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

    @Test fun `previous imported jar with old guard is migrated on reimport`() {
        val target = Files.createTempFile("superfastmode-legacy-test", ".jar").toFile()
        val guard = "skrelpoid/superfastmode/patches/BossRewardLerpGuard.class"
        try {
            ZipOutputStream(FileOutputStream(target)).use { zip ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(legacyV2Fixture())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(guard))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
            assertTrue(SuperFastModeImportCompatPatcher.patchInPlace(target))
            assertFalse(SuperFastModeImportCompatPatcher.patchInPlace(target))
            ZipFile(target).use { zip ->
                val repaired = zip.getInputStream(zip.getEntry(entry)).use { it.readBytes() }
                assertNull(SuperFastModeImportCompatPatcher.inspectClassBytes(repaired)?.replacement)
                val guardBytes = zip.getInputStream(zip.getEntry(guard)).use { it.readBytes() }
                assertArrayEquals(SuperFastModeImportCompatPatcher.createGuardClass(), guardBytes)
            }
        } finally {
            target.delete()
        }
    }

    @Test fun `legacy v1 dungeon read is removed during migration`() {
        val node = ClassNode()
        ClassReader(legacyV2Fixture()).accept(node, 0)
        node.fields.clear()
        val replace = node.methods.single { it.name == "Replace" }
        val flag = replace.instructions.toArray().filterIsInstance<FieldInsnNode>().single {
            it.name == "bossRewardUpdating"
        }
        val end = (flag.next as JumpInsnNode).label
        replace.instructions.insertBefore(flag, FieldInsnNode(Opcodes.GETSTATIC,
            "com/megacrit/cardcrawl/dungeons/AbstractDungeon", "screen",
            "Lcom/megacrit/cardcrawl/dungeons/AbstractDungeon\$CurrentScreen;"))
        replace.instructions.insertBefore(flag, FieldInsnNode(Opcodes.GETSTATIC,
            "com/megacrit/cardcrawl/dungeons/AbstractDungeon\$CurrentScreen", "BOSS_REWARD",
            "Lcom/megacrit/cardcrawl/dungeons/AbstractDungeon\$CurrentScreen;"))
        replace.instructions.insertBefore(flag, JumpInsnNode(Opcodes.IF_ACMPEQ, end))
        replace.instructions.remove(flag.next)
        replace.instructions.remove(flag)
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val repaired = SuperFastModeImportCompatPatcher.inspectClassBytes(writer.toByteArray())?.replacement
        assertNotNull(repaired)
        val result = ClassNode()
        ClassReader(repaired).accept(result, 0)
        assertEquals(listOf("isInstantLerp"), result.methods.single { it.name == "Replace" }
            .instructions.toArray().filterIsInstance<FieldInsnNode>().map { it.name })
    }

    @Test fun `boss guard saves and restores the existing switch and never reads dungeon state`() {
        val node = ClassNode()
        ClassReader(SuperFastModeImportCompatPatcher.createGuardClass()).accept(node, 0)
        val patch = node.visibleAnnotations.single {
            it.desc == "Lcom/evacipated/cardcrawl/modthespire/lib/SpirePatch;"
        }
        assertTrue(patch.values.contains("update"))
        assertEquals(setOf("Prefix", "Postfix"), node.methods.map { it.name }.toSet())
        val prefix = node.methods.single { it.name == "Prefix" }.instructions.toArray().filterIsInstance<FieldInsnNode>()
        val postfix = node.methods.single { it.name == "Postfix" }.instructions.toArray().filterIsInstance<FieldInsnNode>()
        assertTrue(node.fields.isEmpty())
        assertEquals(listOf("isInstantLerp", "isInstantLerp"), prefix.map { it.name })
        assertEquals(listOf("isInstantLerp"), postfix.map { it.name })
        assertTrue((prefix + postfix).none { it.owner.contains("AbstractDungeon") })
    }

    @Test fun `generated guard runs without loading dungeon classes or new fields`() {
        val guardName = "skrelpoid.superfastmode.patches.BossRewardLerpGuard"
        val modName = "skrelpoid.superfastmode.SuperFastMode"
        val guardBytes = SuperFastModeImportCompatPatcher.createGuardClass()
        val modWriter = ClassWriter(0)
        modWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, modName.replace('.', '/'), null,
            "java/lang/Object", null)
        modWriter.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "isInstantLerp", "Z", null, null).visitEnd()
        modWriter.visitEnd()
        val classLoader = object : ClassLoader(null) {
            override fun findClass(name: String): Class<*> {
                val bytes = when (name) {
                    guardName -> guardBytes
                    modName -> modWriter.toByteArray()
                    else -> throw ClassNotFoundException(name)
                }
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
        val mod = classLoader.loadClass(modName)
        val guard = classLoader.loadClass(guardName)
        val toggle = mod.getField("isInstantLerp")
        try {
            for (before in listOf(true, false)) {
                toggle.setBoolean(null, before)
                guard.getMethod("Prefix").invoke(null)
                assertFalse(toggle.getBoolean(null))
                guard.getMethod("Postfix").invoke(null)
                assertEquals(before, toggle.getBoolean(null))
                assertNull(System.getProperty("amethyst.superfastmode.boss_reward_instant_lerp"))
            }
        } finally {
            System.clearProperty("amethyst.superfastmode.boss_reward_instant_lerp")
        }
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
