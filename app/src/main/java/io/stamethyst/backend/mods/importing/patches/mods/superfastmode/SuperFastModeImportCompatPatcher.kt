package io.stamethyst.backend.mods.importing.patches.mods.superfastmode

import io.stamethyst.backend.mods.JarFileIoUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * Guard only the boss-selection update. Never add references to new fields or game state to
 * MathUtils.lerp: it is called by other mods while their initializers run, before a dungeon exists.
 */
internal object SuperFastModeImportCompatPatcher {
    private const val ENTRY = "skrelpoid/superfastmode/patches/MathUtilsPatches\$LerpPatch.class"
    private const val OWNER = "skrelpoid/superfastmode/patches/MathUtilsPatches\$LerpPatch"
    private const val GUARD_ENTRY = "skrelpoid/superfastmode/patches/BossRewardLerpGuard.class"
    private const val GUARD_OWNER = "skrelpoid/superfastmode/patches/BossRewardLerpGuard"
    private const val MOD = "skrelpoid/superfastmode/SuperFastMode"
    private const val FIELD = "previousInstantLerp"

    @Throws(IOException::class)
    fun patchInPlace(jar: File): Boolean {
        val entryName: String
        val replacement: ByteArray?
        val replaceExistingGuard: Boolean
        ZipFile(jar).use { zip ->
            val entry = JarFileIoUtils.findEntryIgnoreCase(zip, ENTRY) ?: return false
            entryName = entry.name
            val result = inspectClassBytes(JarFileIoUtils.readEntryBytes(zip, entry)) ?: return false
            replacement = result.replacement
            replaceExistingGuard = zip.getEntry(GUARD_ENTRY) != null
            if (replaceExistingGuard && replacement == null) return false
        }
        val guard = createGuardClass()
        val temporary = File(jar.absolutePath + ".superfastmode.tmp")
        try {
            ZipFile(jar).use { zip ->
                ZipOutputStream(FileOutputStream(temporary)).use { output ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val copy = ZipEntry(entry.name)
                        if (entry.time > 0) copy.time = entry.time
                        output.putNextEntry(copy)
                        if (!entry.isDirectory) {
                            if (entry.name == entryName && replacement != null) output.write(replacement)
                            else if (entry.name == GUARD_ENTRY) output.write(guard)
                            else zip.getInputStream(entry).use { JarFileIoUtils.copyStream(it, output) }
                        }
                        output.closeEntry()
                    }
                    if (!replaceExistingGuard) {
                        output.putNextEntry(ZipEntry(GUARD_ENTRY))
                        output.write(guard)
                        output.closeEntry()
                    }
                }
            }
            JarFileIoUtils.moveFileReplacing(temporary, jar)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        return true
    }

    internal data class Inspection(val replacement: ByteArray?)

    internal fun inspectClassBytes(bytes: ByteArray): Inspection? {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        if (node.name != OWNER) return null
        val replace = node.methods.firstOrNull { it.name == "Replace" && it.desc == "(FFF)F" }
            ?: return null
        val instructions = replace.instructions.toArray()
        val flag = instructions.filterIsInstance<FieldInsnNode>().singleOrNull {
            it.opcode == Opcodes.GETSTATIC && it.owner == MOD && it.name == "isInstantLerp" && it.desc == "Z"
        } ?: return null
        val next = flag.next as? JumpInsnNode ?: return null
        if (next.opcode != Opcodes.IFEQ) return null
        val legacyV1 = instructions.any {
            it is FieldInsnNode && it.owner == "com/megacrit/cardcrawl/dungeons/AbstractDungeon" && it.name == "screen"
        }
        val legacyV2 = node.fields.any { it.name == "bossRewardUpdating" }
        if (!legacyV1 && !legacyV2) {
            // Only accept the original branch. A different mod version must not be silently rewritten.
            val unexpected = instructions.filterIsInstance<FieldInsnNode>().any {
                it.owner != MOD || it.name != "isInstantLerp"
            }
            return if (!unexpected && instructions.filterIsInstance<JumpInsnNode>().size == 1) {
                Inspection(null)
            } else null
        }
        // Migration from v1/v2: remove the old, unsafe inline guard from the imported JAR.
        // Don't replace the mod's regular lerp calculation or any unrelated methods.
        if (legacyV1 == legacyV2 ||
            instructions.filterIsInstance<MethodInsnNode>().singleOrNull()?.let {
                it.owner == "skrelpoid/superfastmode/patches/MathUtilsPatches" && it.name == "lerp"
            } != true
        ) return null
        node.fields.removeAll { it.name == "bossRewardUpdating" }
        replace.instructions.clear()
        val done = org.objectweb.asm.tree.LabelNode()
        replace.instructions.add(org.objectweb.asm.tree.VarInsnNode(Opcodes.FLOAD, 0))
        replace.instructions.add(org.objectweb.asm.tree.VarInsnNode(Opcodes.FLOAD, 1))
        replace.instructions.add(org.objectweb.asm.tree.VarInsnNode(Opcodes.FLOAD, 2))
        replace.instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC,
            "skrelpoid/superfastmode/patches/MathUtilsPatches", "lerp", "(FFF)F", false))
        replace.instructions.add(org.objectweb.asm.tree.VarInsnNode(Opcodes.FSTORE, 3))
        replace.instructions.add(FieldInsnNode(Opcodes.GETSTATIC, MOD, "isInstantLerp", "Z"))
        replace.instructions.add(JumpInsnNode(Opcodes.IFEQ, done))
        replace.instructions.add(org.objectweb.asm.tree.VarInsnNode(Opcodes.FLOAD, 1))
        replace.instructions.add(org.objectweb.asm.tree.VarInsnNode(Opcodes.FSTORE, 3))
        replace.instructions.add(done)
        replace.instructions.add(org.objectweb.asm.tree.VarInsnNode(Opcodes.FLOAD, 3))
        replace.instructions.add(org.objectweb.asm.tree.InsnNode(Opcodes.FRETURN))
        replace.tryCatchBlocks.clear()
        replace.localVariables?.clear()
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        return Inspection(writer.toByteArray())
    }

    internal fun createGuardClass(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, GUARD_OWNER, null, "java/lang/Object", null)
        val annotation = writer.visitAnnotation("Lcom/evacipated/cardcrawl/modthespire/lib/SpirePatch;", true)
        annotation.visit("clz", Type.getObjectType("com/megacrit/cardcrawl/screens/select/BossRelicSelectScreen"))
        annotation.visit("method", "update")
        annotation.visitEnd()
        writer.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, FIELD, "Z", null, null).visitEnd()
        val prefix = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "Prefix", "()V", null, null)
        prefix.visitCode()
        prefix.visitFieldInsn(Opcodes.GETSTATIC, MOD, "isInstantLerp", "Z")
        prefix.visitFieldInsn(Opcodes.PUTSTATIC, GUARD_OWNER, FIELD, "Z")
        prefix.visitInsn(Opcodes.ICONST_0)
        prefix.visitFieldInsn(Opcodes.PUTSTATIC, MOD, "isInstantLerp", "Z")
        prefix.visitInsn(Opcodes.RETURN)
        prefix.visitMaxs(1, 0)
        prefix.visitEnd()
        val postfix = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "Postfix", "()V", null, null)
        postfix.visitCode()
        postfix.visitFieldInsn(Opcodes.GETSTATIC, GUARD_OWNER, FIELD, "Z")
        postfix.visitFieldInsn(Opcodes.PUTSTATIC, MOD, "isInstantLerp", "Z")
        postfix.visitInsn(Opcodes.RETURN)
        postfix.visitMaxs(1, 0)
        postfix.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }
}
