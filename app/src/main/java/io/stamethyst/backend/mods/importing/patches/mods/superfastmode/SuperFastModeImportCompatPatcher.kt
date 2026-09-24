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
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode

/** Keeps boss-reward lerps normal only during the boss selection update, without loading the dungeon at startup. */
internal object SuperFastModeImportCompatPatcher {
    private const val ENTRY = "skrelpoid/superfastmode/patches/MathUtilsPatches\$LerpPatch.class"
    private const val OWNER = "skrelpoid/superfastmode/patches/MathUtilsPatches\$LerpPatch"
    private const val GUARD_ENTRY = "skrelpoid/superfastmode/patches/BossRewardLerpGuard.class"
    private const val GUARD_OWNER = "skrelpoid/superfastmode/patches/BossRewardLerpGuard"
    private const val MOD = "skrelpoid/superfastmode/SuperFastMode"
    private const val FIELD = "bossRewardUpdating"

    @Throws(IOException::class)
    fun patchInPlace(jar: File): Boolean {
        val entryName: String
        val patched: ByteArray
        ZipFile(jar).use { zip ->
            if (zip.getEntry(GUARD_ENTRY) != null) return false
            val entry = JarFileIoUtils.findEntryIgnoreCase(zip, ENTRY) ?: return false
            entryName = entry.name
            patched = patchClassBytes(JarFileIoUtils.readEntryBytes(zip, entry)) ?: return false
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
                            if (entry.name == entryName) output.write(patched)
                            else zip.getInputStream(entry).use { JarFileIoUtils.copyStream(it, output) }
                        }
                        output.closeEntry()
                    }
                    output.putNextEntry(ZipEntry(GUARD_ENTRY))
                    output.write(guard)
                    output.closeEntry()
                }
            }
            JarFileIoUtils.moveFileReplacing(temporary, jar)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        return true
    }

    internal fun patchClassBytes(bytes: ByteArray): ByteArray? {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        if (node.name != OWNER || node.fields.any { it.name == FIELD }) return null
        val replace = node.methods.firstOrNull { it.name == "Replace" && it.desc == "(FFF)F" }
            ?: return null
        val instructions = replace.instructions.toArray()
        val flag = instructions.filterIsInstance<FieldInsnNode>().singleOrNull {
            it.opcode == Opcodes.GETSTATIC && it.owner == MOD && it.name == "isInstantLerp" && it.desc == "Z"
        } ?: return null
        val next = flag.next as? JumpInsnNode ?: return null
        if (next.opcode != Opcodes.IFEQ) return null
        node.fields.add(FieldNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, FIELD, "Z", null, null))
        val skip = LabelNode()
        replace.instructions.insertBefore(flag, FieldInsnNode(Opcodes.GETSTATIC, OWNER, FIELD, "Z"))
        replace.instructions.insertBefore(flag, JumpInsnNode(Opcodes.IFNE, skip))
        replace.instructions.insertBefore(next.label, skip)
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        return writer.toByteArray()
    }

    internal fun createGuardClass(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, GUARD_OWNER, null, "java/lang/Object", null)
        val annotation = writer.visitAnnotation("Lcom/evacipated/cardcrawl/modthespire/lib/SpirePatch;", true)
        annotation.visit("clz", Type.getObjectType("com/megacrit/cardcrawl/screens/select/BossRelicSelectScreen"))
        annotation.visit("method", "update")
        annotation.visitEnd()
        for ((name, enabled) in listOf("Prefix" to true, "Postfix" to false)) {
            val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, "()V", null, null)
            method.visitCode()
            method.visitInsn(if (enabled) Opcodes.ICONST_1 else Opcodes.ICONST_0)
            method.visitFieldInsn(Opcodes.PUTSTATIC, OWNER, FIELD, "Z")
            method.visitInsn(Opcodes.RETURN)
            method.visitMaxs(1, 0)
            method.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
