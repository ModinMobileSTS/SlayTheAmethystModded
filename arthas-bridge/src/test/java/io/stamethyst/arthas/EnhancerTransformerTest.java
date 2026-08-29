package io.stamethyst.arthas;

import com.alibaba.deps.org.objectweb.asm.ClassReader;
import com.alibaba.deps.org.objectweb.asm.ClassVisitor;
import com.alibaba.deps.org.objectweb.asm.MethodVisitor;
import com.alibaba.deps.org.objectweb.asm.Opcodes;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class EnhancerTransformerTest {

    @Test
    public void transformRoutesRetransformationThroughBridge() throws Exception {
        byte[] original = readResource(
            "/com/taobao/arthas/core/advisor/Enhancer.class");
        byte[] transformed = new EnhancerTransformer().transform(
            null, "com/taobao/arthas/core/advisor/Enhancer",
            null, null, original);
        assertNotNull(transformed);

        AtomicInteger calls = new AtomicInteger();
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name,
                    String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner,
                            String method, String desc, boolean isInterface) {
                        if ("io/stamethyst/arthas/RetransformBridge".equals(owner)
                                && "retransformClasses".equals(method)) {
                            calls.incrementAndGet();
                        }
                    }
                };
            }
        }, 0);
        assertTrue(calls.get() > 0);
    }

    private static byte[] readResource(String name) throws Exception {
        InputStream input = EnhancerTransformerTest.class.getResourceAsStream(name);
        assertNotNull(input);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }
}
