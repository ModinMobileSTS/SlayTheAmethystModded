package io.stamethyst.backend.mods

import java.io.IOException
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode

/** Normalize typed resource handles before java.io.File sees Windows separators. */
internal object StsResourceFileHandlePatcher {
    private const val FILE_HANDLE = "com/badlogic/gdx/files/FileHandle"
    private const val FILE_TYPE = "Lcom/badlogic/gdx/Files\$FileType;"
    private const val HELPER = "io/stamethyst/gdx/ResourceFileHandleCompat"
    private val pathTypes = listOf("Ljava/lang/String;", "Ljava/io/File;")

    fun patchFileHandleClass(classBytes: ByteArray): ByteArray {
        val node = readClass(classBytes)
        if (node.name != FILE_HANDLE) throw IOException("Unexpected file handle class: ${node.name}")
        var changed = false
        for (pathType in pathTypes) {
            val constructor = findConstructor(node, pathType)
                ?: throw IOException("Unsupported desktop-1.0.jar: FileHandle($pathType, FileType) not found")
            if (hasNormalizeCall(constructor, pathType)) continue
            // Static argument-only code is valid before super(); no uninitialized 'this' escapes.
            constructor.instructions.insert(InsnList().apply {
                add(VarInsnNode(Opcodes.ALOAD, 1))
                add(VarInsnNode(Opcodes.ALOAD, 2))
                add(MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "normalize",
                    "($pathType$FILE_TYPE)$pathType", false))
                add(VarInsnNode(Opcodes.ASTORE, 1))
            })
            constructor.maxStack = maxOf(constructor.maxStack, 2)
            changed = true
        }
        if (!changed) return classBytes
        return ClassWriter(0).also(node::accept).toByteArray()
    }

    fun isPatchedFileHandleClass(classBytes: ByteArray): Boolean {
        val node = readClass(classBytes)
        return node.name == FILE_HANDLE && pathTypes.all { pathType ->
            findConstructor(node, pathType)?.let { hasNormalizeCall(it, pathType) } == true
        }
    }

    private fun findConstructor(node: ClassNode, pathType: String): MethodNode? =
        node.methods.firstOrNull { it.name == "<init>" && it.desc == "($pathType$FILE_TYPE)V" }

    private fun hasNormalizeCall(method: MethodNode, pathType: String): Boolean =
        method.instructions.any { instruction ->
            instruction is MethodInsnNode && instruction.opcode == Opcodes.INVOKESTATIC &&
                instruction.owner == HELPER && instruction.name == "normalize" &&
                instruction.desc == "($pathType$FILE_TYPE)$pathType"
        }

    private fun readClass(bytes: ByteArray): ClassNode =
        ClassNode().also { ClassReader(bytes).accept(it, 0) }
}
