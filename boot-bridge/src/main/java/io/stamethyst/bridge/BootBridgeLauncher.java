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
        reporter.phase(26, BootBridgeStartupMessage.key("boot_bridge_started"));
        installUncaughtExceptionBridge(reporter);
        BootBridgeJvmMemoryWatcher.start(reporter);
        BootBridgeJvmHeapSnapshotWriter.startFromSystemProperty();
        BootBridgeGcHistogramDumper.startFromSystemProperties();
        BootBridgeMtsProgressBridge.tryInstall(reporter);
        BootBridgeGameStateWatcher.start(reporter);
        triggerForcedCrashIfRequested(reporter);

        String delegateClass = System.getProperty(PROP_DELEGATE, DEFAULT_DELEGATE);
        reporter.phase(29, mapDelegateLaunchMessage(delegateClass));
        invokeDelegate(delegateClass, args, reporter);
    }

    private static void triggerForcedCrashIfRequested(BootBridgeReporter reporter) {
        boolean forceCrash = Boolean.parseBoolean(System.getProperty(PROP_FORCE_CRASH, "false"));
        if (!forceCrash) {
            return;
        }
        RuntimeException crash = new RuntimeException("Forced JVM crash for diagnostics verification");
        reporter.fail(BootBridgeStartupMessage.key("forced_crash_requested"));
        throw crash;
    }

    private static void installUncaughtExceptionBridge(BootBridgeReporter reporter) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            reporter.fail(
                    BootBridgeStartupMessage.keyWithPayload(
                            "uncaught_exception",
                            reporter.summarizeThrowable(throwable)
                    )
            );
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else if (throwable != null) {
                throwable.printStackTrace(System.err);
            }
        });
    }

    private static String mapDelegateLaunchMessage(String delegateClass) {
        if ("com.evacipated.cardcrawl.modthespire.Loader".equals(delegateClass)) {
            return BootBridgeStartupMessage.key("launching_modthespire");
        }
        return BootBridgeStartupMessage.key("launching_game");
    }

    private static void invokeDelegate(String delegateClass, String[] args, BootBridgeReporter reporter) throws Throwable {
        try {
            Class<?> delegate = Class.forName(delegateClass);
            Method mainMethod = delegate.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            reporter.fail(
                    BootBridgeStartupMessage.keyWithPayload(
                            "delegate_crashed",
                            reporter.summarizeThrowable(cause)
                    )
            );
            throw cause;
        } catch (Throwable error) {
            reporter.fail(
                    BootBridgeStartupMessage.keyWithPayload(
                            "delegate_start_failed",
                            reporter.summarizeThrowable(error)
                    )
            );
            throw error;
        }
    }
}
