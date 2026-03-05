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
        BootBridgeReporter reporter = new BootBridgeReporter(BootBridgeEventSink.fromSystemProperty());
        BootBridgeConsoleBridge.install(reporter);
        reporter.phase(26, "Boot bridge started");
        installUncaughtExceptionBridge(reporter);
        BootBridgeMtsProgressBridge.tryInstall(reporter);
        BootBridgeGameStateWatcher.start(reporter);
        triggerForcedCrashIfRequested(reporter);

        String delegateClass = System.getProperty(PROP_DELEGATE, DEFAULT_DELEGATE);
        reporter.phase(29, "Starting " + delegateClass);
        invokeDelegate(delegateClass, args, reporter);
    }

    private static void triggerForcedCrashIfRequested(BootBridgeReporter reporter) {
        boolean forceCrash = Boolean.parseBoolean(System.getProperty(PROP_FORCE_CRASH, "false"));
        if (!forceCrash) {
            return;
        }
        RuntimeException crash = new RuntimeException("Forced JVM crash for diagnostics verification");
        reporter.fail("Forced crash requested via " + PROP_FORCE_CRASH);
        throw crash;
    }

    private static void installUncaughtExceptionBridge(BootBridgeReporter reporter) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            reporter.fail("Uncaught exception on " + thread.getName() + ": " + reporter.summarizeThrowable(throwable));
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else if (throwable != null) {
                throwable.printStackTrace(System.err);
            }
        });
    }

    private static void invokeDelegate(String delegateClass, String[] args, BootBridgeReporter reporter) throws Throwable {
        try {
            Class<?> delegate = Class.forName(delegateClass);
            Method mainMethod = delegate.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            reporter.fail("Delegate crashed: " + reporter.summarizeThrowable(cause));
            throw cause;
        } catch (Throwable error) {
            reporter.fail("Delegate start failed: " + reporter.summarizeThrowable(error));
            throw error;
        }
    }
}
