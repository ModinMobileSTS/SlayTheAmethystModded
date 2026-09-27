package io.stamethyst.backend.mods

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import java.io.IOException

/**
 * Keeps Android's stdio/BootBridge pipeline instead of MTS's unbounded Swing console tee.
 * Patch before the game loads Log4j: ConsoleAppender can retain the stream it first sees.
 */
internal object MtsConsoleLogPatcher {
    const val MESSAGE_CONSOLE_CLASS_ENTRY =
        "com/evacipated/cardcrawl/modthespire/ui/MessageConsole.class"

    private const val REDIRECT_DESC = "(Ljava/awt/Color;Ljava/io/PrintStream;)V"
    private const val PRINT_STREAM_DESC = "(Ljava/io/PrintStream;)V"
    private val REDIRECT_METHOD_NAMES = listOf("redirectOut", "redirectErr")

    @Throws(IOException::class)
    fun patchMessageConsoleBytes(classBytes: ByteArray): ByteArray {
        val classNode = readClassNode(classBytes)
        if (classNode.name != MESSAGE_CONSOLE_CLASS_ENTRY.removeSuffix(".class")) {
            throw IOException("Unsupported ModTheSpire.jar: unexpected MessageConsole class")
        }
        val methods = redirectMethods(classNode)
            ?: throw IOException("Unsupported ModTheSpire.jar: MessageConsole redirect methods not found")
        if (methods.all(::isPassthrough)) {
            return classBytes
        }
        methods.forEach { method ->
            method.instructions.clear()
            method.tryCatchBlocks.clear()
            method.localVariables?.clear()
            method.visibleLocalVariableAnnotations?.clear()
            method.invisibleLocalVariableAnnotations?.clear()
            if (method.desc == REDIRECT_DESC) {
                // Preserve an explicitly supplied destination without wrapping it. The no-arg
                // overloads must leave the current stream intact, not silence stdout/stderr.
                val end = LabelNode()
                method.instructions.add(VarInsnNode(Opcodes.ALOAD, 2))
                method.instructions.add(JumpInsnNode(Opcodes.IFNULL, end))
                method.instructions.add(VarInsnNode(Opcodes.ALOAD, 2))
                method.instructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/System",
                        systemSetter(method.name),
                        PRINT_STREAM_DESC,
                        false
                    )
                )
                method.instructions.add(end)
                method.instructions.add(FrameNode(Opcodes.F_SAME, 0, null, 0, null))
            }
            method.instructions.add(InsnNode(Opcodes.RETURN))
            method.maxLocals = if (method.desc == REDIRECT_DESC) 3 else 1
            method.maxStack = if (method.desc == REDIRECT_DESC) 1 else 0
        }
        val writer = ClassWriter(0)
        classNode.accept(writer)
        return writer.toByteArray()
    }

    fun isPatchedMessageConsoleClass(classBytes: ByteArray): Boolean {
        val classNode = readClassNode(classBytes)
        return classNode.name == MESSAGE_CONSOLE_CLASS_ENTRY.removeSuffix(".class") &&
            redirectMethods(classNode)?.all(::isPassthrough) == true
    }

    private fun redirectMethods(classNode: ClassNode): List<MethodNode>? {
        val methods = ArrayList<MethodNode>(4)
        for (name in REDIRECT_METHOD_NAMES) {
            for (desc in listOf("()V", REDIRECT_DESC)) {
                val method = classNode.methods.firstOrNull { it.name == name && it.desc == desc }
                    ?: return null
                if (method.access and (Opcodes.ACC_STATIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0) {
                    return null
                }
                methods.add(method)
            }
        }
        return methods
    }

    private fun isPassthrough(method: MethodNode): Boolean {
        if (method.tryCatchBlocks.isNotEmpty()) {
            return false
        }
        val instructions = method.instructions.iterator().asSequence()
            .filter { it.opcode >= 0 }.toList()
        if (method.desc == "()V") {
            return instructions.size == 1 && instructions[0].opcode == Opcodes.RETURN
        }
        if (instructions.size != 5) {
            return false
        }
        val argument = instructions[0] as? VarInsnNode ?: return false
        val guard = instructions[1] as? JumpInsnNode ?: return false
        val destination = instructions[2] as? VarInsnNode ?: return false
        val setter = instructions[3] as? MethodInsnNode ?: return false
        val guardTarget = generateSequence(guard.label.next) { it.next }
            .firstOrNull { it.opcode >= 0 }
        return argument.opcode == Opcodes.ALOAD && argument.`var` == 2 &&
            guard.opcode == Opcodes.IFNULL && guardTarget === instructions[4] &&
            destination.opcode == Opcodes.ALOAD && destination.`var` == 2 &&
            setter.opcode == Opcodes.INVOKESTATIC && setter.owner == "java/lang/System" &&
            setter.name == systemSetter(method.name) && setter.desc == PRINT_STREAM_DESC &&
            instructions[4].opcode == Opcodes.RETURN
    }

    private fun systemSetter(methodName: String): String =
        if (methodName == "redirectOut") "setOut" else "setErr"

    private fun readClassNode(classBytes: ByteArray): ClassNode {
        return ClassNode().also { ClassReader(classBytes).accept(it, 0) }
    }
}
