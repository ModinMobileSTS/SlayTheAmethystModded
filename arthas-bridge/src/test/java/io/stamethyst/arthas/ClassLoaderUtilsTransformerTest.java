package io.stamethyst.arthas;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ClassLoaderUtilsTransformerTest {

    @Test
    public void transformReplacesBytecodeResourceLookup() {
        byte[] transformed = new ClassLoaderUtilsTransformer().transform(
            null, "com/alibaba/bytekit/utils/ClassLoaderUtils",
            null, null, buildMinimalClassLoaderUtils());
        assertNotNull(transformed);

        final List<String> calls = new ArrayList<String>();
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name,
                    String descriptor, String signature, String[] exceptions) {
                if (!"readBytecodeByName".equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner,
                            String method, String desc, boolean isInterface) {
                        calls.add(owner + "." + method);
                    }
                };
            }
        }, 0);

        assertEquals(Collections.singletonList(
            "io/stamethyst/arthas/CommonSuperBridge.readBytecode"), calls);
    }

    @Test
    public void transformSkipsOtherClasses() {
        assertNull(new ClassLoaderUtilsTransformer().transform(
            null, "some/other/Class", null, null, new byte[0]));
    }

    @Test
    public void bridgeReadsThroughLoadedClass() {
        byte[] bytes = CommonSuperBridge.readBytecode(
            CommonSuperBridge.class.getClassLoader(),
            "io/stamethyst/arthas/CommonSuperBridge");
        assertNotNull(bytes);
        assertArrayEquals(new byte[] {
            (byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe
        }, java.util.Arrays.copyOf(bytes, 4));
    }

    private static byte[] buildMinimalClassLoaderUtils() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
            "com/alibaba/bytekit/utils/ClassLoaderUtils",
            null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "readBytecodeByName",
            "(Ljava/lang/ClassLoader;Ljava/lang/String;)[B",
            null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
