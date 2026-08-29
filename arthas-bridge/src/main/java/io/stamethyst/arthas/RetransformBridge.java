package io.stamethyst.arthas;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;

public final class RetransformBridge {
    private RetransformBridge() {}

    public static void retransformClasses(Instrumentation instrumentation,
            Class<?>[] classes) throws UnmodifiableClassException {
        try {
            instrumentation.retransformClasses(classes);
        } catch (InternalError error) {
            if (classes.length != 1 || !hasDuplicateName(instrumentation, classes[0])) {
                throw error;
            }
            ArthasCommandBridge.log("skipping duplicate-class retransform failure: "
                + classes[0] + " loader=" + classes[0].getClassLoader());
        }
    }

    private static boolean hasDuplicateName(Instrumentation instrumentation,
            Class<?> target) {
        int matches = 0;
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (target.getName().equals(loaded.getName()) && ++matches > 1) {
                return true;
            }
        }
        return false;
    }
}
