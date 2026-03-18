package io.stamethyst.backend.mods

import java.io.IOException
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode

internal object StsShaderPrecisionCompatPatcher {
    private const val SHADER_PROGRAM_INTERNAL_NAME =
        "com/badlogic/gdx/graphics/glutils/ShaderProgram"
    private const val STRING_SHADER_CONSTRUCTOR_DESC =
        "(Ljava/lang/String;Ljava/lang/String;)V"
    private const val FRAGMENT_SHADER_FIELD_NAME = "fragmentShaderSource"
    private const val STRING_DESC = "Ljava/lang/String;"
    private const val HELPER_INTERNAL_NAME = "io/stamethyst/gdx/FragmentShaderCompat"
    private const val HELPER_METHOD_NAME = "ensureDefaultPrecision"
    private const val HELPER_METHOD_DESC = "(Ljava/lang/String;)Ljava/lang/String;"

    fun patchShaderProgramClass(classBytes: ByteArray): ByteArray {
        val classNode = readClassNode(classBytes)
        if (classNode.name != SHADER_PROGRAM_INTERNAL_NAME) {
            throw IOException("Unexpected shader class: ${classNode.name}")
        }
        val shaderCtor = classNode.methods.firstOrNull { method ->
            method.name == "<init>" && method.desc == STRING_SHADER_CONSTRUCTOR_DESC
        } ?: throw IOException("Unsupported desktop-1.0.jar: ShaderProgram(String, String) not found")

        if (isPatchedConstructor(shaderCtor)) {
            return classBytes
        }

        val insertBefore = findFragmentShaderStoreStart(shaderCtor)
            ?: throw IOException("Unsupported desktop-1.0.jar: fragmentShaderSource assignment not found")
        shaderCtor.instructions.insertBefore(
            insertBefore,
            InsnList().apply {
                add(VarInsnNode(Opcodes.ALOAD, 2))
                add(
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        HELPER_INTERNAL_NAME,
                        HELPER_METHOD_NAME,
                        HELPER_METHOD_DESC,
                        false
                    )
                )
                add(VarInsnNode(Opcodes.ASTORE, 2))
            }
        )

        val classWriter = ClassWriter(0)
        classNode.accept(classWriter)
        return classWriter.toByteArray()
    }

    fun isPatchedShaderProgramClass(classBytes: ByteArray): Boolean {
        val classNode = readClassNode(classBytes)
        if (classNode.name != SHADER_PROGRAM_INTERNAL_NAME) {
            return false
        }
        val shaderCtor = classNode.methods.firstOrNull { method ->
            method.name == "<init>" && method.desc == STRING_SHADER_CONSTRUCTOR_DESC
        } ?: return false
        return isPatchedConstructor(shaderCtor)
    }

    private fun isPatchedConstructor(method: MethodNode): Boolean {
        var current = method.instructions.first
        while (current != null) {
            val call = current as? MethodInsnNode
            if (call != null &&
                call.opcode == Opcodes.INVOKESTATIC &&
                call.owner == HELPER_INTERNAL_NAME &&
                call.name == HELPER_METHOD_NAME &&
                call.desc == HELPER_METHOD_DESC
            ) {
                return true
            }
            current = current.next
        }
        return false
    }

    private fun findFragmentShaderStoreStart(method: MethodNode): VarInsnNode? {
        var current = method.instructions.first
        while (current != null) {
            val putField = current as? FieldInsnNode
            if (putField != null &&
                putField.opcode == Opcodes.PUTFIELD &&
                putField.owner == SHADER_PROGRAM_INTERNAL_NAME &&
                putField.name == FRAGMENT_SHADER_FIELD_NAME &&
                putField.desc == STRING_DESC
            ) {
                val stringLoad = previousMeaningful(putField.previous) as? VarInsnNode ?: return null
                val thisLoad = previousMeaningful(stringLoad.previous) as? VarInsnNode ?: return null
                if (stringLoad.opcode == Opcodes.ALOAD &&
                    stringLoad.`var` == 2 &&
                    thisLoad.opcode == Opcodes.ALOAD &&
                    thisLoad.`var` == 0
                ) {
                    return thisLoad
                }
                return null
            }
            current = current.next
        }
        return null
    }

    private fun previousMeaningful(node: AbstractInsnNode?): AbstractInsnNode? {
        var current = node
        while (current != null) {
            if (current.opcode >= 0) {
                return current
            }
            current = current.previous
        }
        return null
    }

    private fun readClassNode(classBytes: ByteArray): ClassNode {
        val classNode = ClassNode()
        ClassReader(classBytes).accept(classNode, 0)
        return classNode
    }
}
