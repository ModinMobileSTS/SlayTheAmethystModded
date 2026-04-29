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
    private const val VERTEX_SHADER_FIELD_NAME = "vertexShaderSource"
    private const val FRAGMENT_SHADER_FIELD_NAME = "fragmentShaderSource"
    private const val STRING_DESC = "Ljava/lang/String;"
    private const val HELPER_INTERNAL_NAME = "io/stamethyst/gdx/FragmentShaderCompat"
    private const val NORMALIZE_VERTEX_METHOD_NAME = "normalizeVertexShader"
    private const val NORMALIZE_FRAGMENT_METHOD_NAME = "normalizeFragmentShader"
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

        val vertexInsertBefore = findStringFieldStoreStart(
            method = shaderCtor,
            fieldName = VERTEX_SHADER_FIELD_NAME,
            sourceVar = 1
        ) ?: throw IOException("Unsupported desktop-1.0.jar: vertexShaderSource assignment not found")
        val fragmentInsertBefore = findStringFieldStoreStart(
            method = shaderCtor,
            fieldName = FRAGMENT_SHADER_FIELD_NAME,
            sourceVar = 2
        ) ?: throw IOException("Unsupported desktop-1.0.jar: fragmentShaderSource assignment not found")

        shaderCtor.instructions.insertBefore(
            vertexInsertBefore,
            buildNormalizeCall(sourceVar = 1, methodName = NORMALIZE_VERTEX_METHOD_NAME)
        )
        shaderCtor.instructions.insertBefore(
            fragmentInsertBefore,
            buildNormalizeCall(sourceVar = 2, methodName = NORMALIZE_FRAGMENT_METHOD_NAME)
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
        return hasNormalizeCall(method, NORMALIZE_VERTEX_METHOD_NAME) &&
            hasNormalizeCall(method, NORMALIZE_FRAGMENT_METHOD_NAME)
    }

    private fun hasNormalizeCall(method: MethodNode, methodName: String): Boolean {
        var current = method.instructions.first
        while (current != null) {
            val call = current as? MethodInsnNode
            if (call != null &&
                call.opcode == Opcodes.INVOKESTATIC &&
                call.owner == HELPER_INTERNAL_NAME &&
                call.name == methodName &&
                call.desc == HELPER_METHOD_DESC
            ) {
                return true
            }
            current = current.next
        }
        return false
    }

    private fun buildNormalizeCall(sourceVar: Int, methodName: String): InsnList {
        return InsnList().apply {
            add(VarInsnNode(Opcodes.ALOAD, sourceVar))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    HELPER_INTERNAL_NAME,
                    methodName,
                    HELPER_METHOD_DESC,
                    false
                )
            )
            add(VarInsnNode(Opcodes.ASTORE, sourceVar))
        }
    }

    private fun findStringFieldStoreStart(
        method: MethodNode,
        fieldName: String,
        sourceVar: Int
    ): VarInsnNode? {
        var current = method.instructions.first
        while (current != null) {
            val putField = current as? FieldInsnNode
            if (putField != null &&
                putField.opcode == Opcodes.PUTFIELD &&
                putField.owner == SHADER_PROGRAM_INTERNAL_NAME &&
                putField.name == fieldName &&
                putField.desc == STRING_DESC
            ) {
                val stringLoad = previousMeaningful(putField.previous) as? VarInsnNode ?: return null
                val thisLoad = previousMeaningful(stringLoad.previous) as? VarInsnNode ?: return null
                if (stringLoad.opcode == Opcodes.ALOAD &&
                    stringLoad.`var` == sourceVar &&
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
