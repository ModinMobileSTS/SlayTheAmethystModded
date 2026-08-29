package io.stamethyst.arthas;

import com.alibaba.deps.org.objectweb.asm.ClassReader;
import com.alibaba.deps.org.objectweb.asm.ClassVisitor;
import com.alibaba.deps.org.objectweb.asm.ClassWriter;
import com.alibaba.deps.org.objectweb.asm.MethodVisitor;
import com.alibaba.deps.org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class ClassLoaderUtilsTransformer implements ClassFileTransformer {

    private static final String TARGET = "com/alibaba/bytekit/utils/ClassLoaderUtils";

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
            if ("readBytecodeByName".equals(name)
                    && "(Ljava/lang/ClassLoader;Ljava/lang/String;)[B"
                        .equals(descriptor)) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor,
                    signature, exceptions);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "io/stamethyst/arthas/CommonSuperBridge",
                    "readBytecode",
                    "(Ljava/lang/ClassLoader;Ljava/lang/String;)[B",
                    false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(2, 2);
                mv.visitEnd();
                return null;
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }
    }
}
