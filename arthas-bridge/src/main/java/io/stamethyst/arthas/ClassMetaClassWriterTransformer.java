package io.stamethyst.arthas;

import com.alibaba.deps.org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class ClassMetaClassWriterTransformer implements ClassFileTransformer {

    private static final String TARGET = "com/alibaba/bytekit/asm/ClassMetaClassWriter";

    @Override
    public byte[] transform(ClassLoader loader, String internalName,
                            Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        String name = internalName;
        if (name == null && classBeingRedefined != null) {
            name = classBeingRedefined.getName();
        }
        if (name == null || !TARGET.equals(name.replace('.', '/'))) {
            return null;
        }
        ArthasCommandBridge.log("transforming " + TARGET + " loader=" + loader);
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new PatchVisitor(cw), 0);
        return cw.toByteArray();
    }

    private static class PatchVisitor extends ClassVisitor {
        PatchVisitor(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name,
                String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor,
                signature, exceptions);
            if ("getCommonSuperClass".equals(name)) {
                return new GetCommonSuperClassPatcher(mv);
            }
            return mv;
        }
    }

    private static class GetCommonSuperClassPatcher extends MethodVisitor {

        GetCommonSuperClassPatcher(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESPECIAL
                    && owner.contains("ClassWriter")
                    && "getCommonSuperClass".equals(name)) {

                Label keepResult = new Label();

                mv.visitInsn(Opcodes.POP);
                mv.visitInsn(Opcodes.POP);
                mv.visitInsn(Opcodes.POP);

                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD,
                    "com/alibaba/bytekit/asm/ClassMetaClassWriter",
                    "classLoader", "Ljava/lang/ClassLoader;");
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "io/stamethyst/arthas/CommonSuperBridge",
                    "resolveCommonSuper",
                    "(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                    false);
                mv.visitInsn(Opcodes.DUP);
                mv.visitJumpInsn(Opcodes.IFNONNULL, keepResult);
                mv.visitInsn(Opcodes.POP);

                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                mv.visitLabel(keepResult);
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(maxStack + 2, maxLocals);
        }
    }
}
