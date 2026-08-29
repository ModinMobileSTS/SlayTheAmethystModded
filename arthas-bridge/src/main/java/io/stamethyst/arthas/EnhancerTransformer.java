package io.stamethyst.arthas;

import com.alibaba.deps.org.objectweb.asm.ClassReader;
import com.alibaba.deps.org.objectweb.asm.ClassVisitor;
import com.alibaba.deps.org.objectweb.asm.ClassWriter;
import com.alibaba.deps.org.objectweb.asm.MethodVisitor;
import com.alibaba.deps.org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Routes Arthas retransformation through the MTS duplicate-class guard. */
public final class EnhancerTransformer implements ClassFileTransformer {
    private static final String TARGET = "com/taobao/arthas/core/advisor/Enhancer";

    @Override
    public byte[] transform(ClassLoader loader, String internalName,
            Class<?> classBeingRedefined, ProtectionDomain pd,
            byte[] classfileBuffer) {
        String name = internalName;
        if (name == null && classBeingRedefined != null) {
            name = classBeingRedefined.getName();
        }
        if (name == null || !TARGET.equals(name.replace('.', '/'))) {
            return null;
        }

        ArthasCommandBridge.log("transforming " + TARGET + " loader=" + loader);
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName,
                    String descriptor, String signature, String[] exceptions) {
                MethodVisitor visitor = super.visitMethod(
                    access, methodName, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, visitor) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner,
                            String invokedName, String invokedDescriptor,
                            boolean isInterface) {
                        if (opcode == Opcodes.INVOKEINTERFACE
                                && "java/lang/instrument/Instrumentation".equals(owner)
                                && "retransformClasses".equals(invokedName)
                                && "([Ljava/lang/Class;)V".equals(invokedDescriptor)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "io/stamethyst/arthas/RetransformBridge",
                                "retransformClasses",
                                "(Ljava/lang/instrument/Instrumentation;[Ljava/lang/Class;)V",
                                false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, invokedName,
                            invokedDescriptor, isInterface);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
