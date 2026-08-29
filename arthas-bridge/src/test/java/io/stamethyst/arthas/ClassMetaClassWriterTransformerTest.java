package io.stamethyst.arthas;

import org.junit.Test;
import org.objectweb.asm.*;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;

import static org.junit.Assert.*;

public class ClassMetaClassWriterTransformerTest {

    @Test
    public void transform_addsInstrumentationAwareFallback() throws Exception {
        byte[] original = buildMinimalClassMetaClassWriter();
        ClassMetaClassWriterTransformer t = new ClassMetaClassWriterTransformer();

        byte[] transformed = t.transform(
            null, "com/alibaba/bytekit/asm/ClassMetaClassWriter",
            null, null, original);

        assertNotNull("transformer must return bytes", transformed);
        assertFalse("output differs from input — patch applied",
            java.util.Arrays.equals(original, transformed));
    }

    @Test
    public void transformedCommonSuperCallUsesTargetLoader() throws Exception {
        byte[] transformed = new ClassMetaClassWriterTransformer().transform(
            null, "com/alibaba/bytekit/asm/ClassMetaClassWriter",
            null, null, buildMinimalClassMetaClassWriter());
        final java.util.List<String> calls = new java.util.ArrayList<String>();

        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name,
                    String descriptor, String signature, String[] exceptions) {
                if (!"getCommonSuperClass".equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner,
                            String method, String desc, boolean isInterface) {
                        calls.add(owner + "." + method + desc);
                    }
                };
            }
        }, 0);

        assertTrue(calls.contains(
            "io/stamethyst/arthas/CommonSuperBridge.resolveCommonSuper"
                + "(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"));
    }

    @Test
    public void transform_skipsOtherClasses() {
        byte[] original = new byte[]{(byte)0xCA, (byte)0xFE, (byte)0xBA, (byte)0xBE, 0, 0, 0, 52};
        ClassMetaClassWriterTransformer t = new ClassMetaClassWriterTransformer();

        byte[] result = t.transform(
            null, "some/other/Class", null, null, original);
        assertNull("non-target class returns null", result);
    }

    @Test
    public void transformedClass_isValidBytecode() throws Exception {
        byte[] original = buildMinimalClassMetaClassWriter();
        ClassMetaClassWriterTransformer t = new ClassMetaClassWriterTransformer();
        byte[] transformed = t.transform(
            null, "com/alibaba/bytekit/asm/ClassMetaClassWriter",
            null, null, original);

        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int version, int access, String name,
                String sig, String superName, String[] ifaces) {
                assertEquals("com/alibaba/bytekit/asm/ClassMetaClassWriter", name);
                assertEquals("org/objectweb/asm/ClassWriter", superName);
            }
        }, 0);
    }

    /**
     * Builds a minimal ClassMetaClassWriter .class to stand in for
     * the real one during transformation tests. Includes the key
     * structures the transformer targets.
     */
    private static byte[] buildMinimalClassMetaClassWriter() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
            "com/alibaba/bytekit/asm/ClassMetaClassWriter",
            null, "org/objectweb/asm/ClassWriter", null);

        // field: private ClassLoader classLoader
        cw.visitField(Opcodes.ACC_PRIVATE, "classLoader",
            "Ljava/lang/ClassLoader;", null, null);

        // constructor (ClassReader, int, ClassLoader)
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
            "(Lorg/objectweb/asm/ClassReader;ILjava/lang/ClassLoader;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
            "org/objectweb/asm/ClassWriter", "<init>",
            "(Lorg/objectweb/asm/ClassReader;I)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.PUTFIELD,
            "com/alibaba/bytekit/asm/ClassMetaClassWriter",
            "classLoader", "Ljava/lang/ClassLoader;");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 4);
        mv.visitEnd();

        // getCommonSuperClass(String, String) — the real one delegates to
        // readBytecodeByName then falls back to super.getCommonSuperClass()
        MethodVisitor gcs = cw.visitMethod(Opcodes.ACC_PROTECTED, "getCommonSuperClass",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            null, new String[]{"java/lang/RuntimeException"});
        gcs.visitCode();
        // try readBytecodeByName for type1
        gcs.visitVarInsn(Opcodes.ALOAD, 0);
        gcs.visitFieldInsn(Opcodes.GETFIELD,
            "com/alibaba/bytekit/asm/ClassMetaClassWriter",
            "classLoader", "Ljava/lang/ClassLoader;");
        gcs.visitVarInsn(Opcodes.ALOAD, 1);
        gcs.visitMethodInsn(Opcodes.INVOKESTATIC,
            "com/alibaba/bytekit/utils/ClassLoaderUtils", "readBytecodeByName",
            "(Ljava/lang/ClassLoader;Ljava/lang/String;)[B", false);
        gcs.visitVarInsn(Opcodes.ASTORE, 3);
        gcs.visitVarInsn(Opcodes.ALOAD, 3);
        Label notNull1 = new Label();
        gcs.visitJumpInsn(Opcodes.IFNONNULL, notNull1);
        // fallback to super
        gcs.visitVarInsn(Opcodes.ALOAD, 0);
        gcs.visitVarInsn(Opcodes.ALOAD, 1);
        gcs.visitVarInsn(Opcodes.ALOAD, 2);
        gcs.visitMethodInsn(Opcodes.INVOKESPECIAL,
            "org/objectweb/asm/ClassWriter", "getCommonSuperClass",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        gcs.visitInsn(Opcodes.ARETURN);
        gcs.visitLabel(notNull1);
        // simplified: just return type1 for test purposes
        gcs.visitVarInsn(Opcodes.ALOAD, 1);
        gcs.visitInsn(Opcodes.ARETURN);
        gcs.visitMaxs(4, 4);
        gcs.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
