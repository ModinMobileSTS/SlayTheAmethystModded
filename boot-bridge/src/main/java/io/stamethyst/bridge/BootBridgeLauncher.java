package io.stamethyst.bridge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class BootBridgeLauncher {
    private static final String PROP_DELEGATE = "amethyst.bridge.delegate";
    private static final String PROP_FORCE_CRASH = "amethyst.debug.force_jvm_crash";
    private static final String DEFAULT_DELEGATE = "com.evacipated.cardcrawl.modthespire.Loader";

    private BootBridgeLauncher() {
    }

    public static void main(String[] args) throws Throwable {
        triggerForcedCrashIfRequested();
        String delegateClass = System.getProperty(PROP_DELEGATE, DEFAULT_DELEGATE);
        invokeDelegate(delegateClass, args);
    }

    private static void triggerForcedCrashIfRequested() {
        boolean forceCrash = Boolean.parseBoolean(System.getProperty(PROP_FORCE_CRASH, "false"));
        if (!forceCrash) {
            return;
        }
        throw new RuntimeException("Forced JVM crash for diagnostics verification");
    }

    private static void invokeDelegate(String delegateClass, String[] args) throws Throwable {
        try {
            Class<?> delegate = Class.forName(delegateClass);
            Method mainMethod = delegate.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw cause;
        }
    }
}
