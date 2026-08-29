package io.stamethyst.arthas;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;

public class CommonSuperBridge {

    private static volatile Instrumentation instrumentation;

    public static void setInstrumentation(Instrumentation inst) {
        instrumentation = inst;
    }

    public static String resolveCommonSuper(
            ClassLoader targetLoader, String type1, String type2) {
        if (instrumentation == null) {
            return null;
        }
        Class<?> c1 = findClass(targetLoader, type1.replace('/', '.'));
        Class<?> c2 = findClass(targetLoader, type2.replace('/', '.'));
        if (c1 == null || c2 == null) {
            return "java/lang/Object";
        }
        if (c1.isAssignableFrom(c2)) return type1;
        if (c2.isAssignableFrom(c1)) return type2;
        if (c1.isInterface() || c2.isInterface()) return "java/lang/Object";
        for (Class<?> sup = c1.getSuperclass(); sup != null; sup = sup.getSuperclass()) {
            if (sup.isAssignableFrom(c2)) {
                return sup.getName().replace('.', '/');
            }
        }
        return "java/lang/Object";
    }

    public static byte[] readBytecode(ClassLoader targetLoader, String internalName) {
        if (internalName == null) {
            return null;
        }
        Class<?> target = findClass(targetLoader, internalName.replace('/', '.'));
        if (target == null) {
            return null;
        }

        String resourceName = "/" + internalName + ".class";
        InputStream input = target.getResourceAsStream(resourceName);
        if (input == null && target.getClassLoader() != null) {
            input = target.getClassLoader().getResourceAsStream(
                internalName + ".class");
        }
        if (input == null) {
            input = ClassLoader.getSystemResourceAsStream(internalName + ".class");
        }
        if (input == null) {
            return null;
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException e) {
            return null;
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {}
        }
    }

    private static Class<?> findClass(ClassLoader targetLoader, String name) {
        try {
            return Class.forName(name, false, targetLoader);
        } catch (ClassNotFoundException e) {
            if (instrumentation == null) {
                return null;
            }
            for (Class<?> c : instrumentation.getAllLoadedClasses()) {
                if (c.getName().equals(name) && c.getClassLoader() == targetLoader) {
                    return c;
                }
            }
            return null;
        }
    }
}
