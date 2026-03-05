package io.stamethyst.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

final class BootBridgeMtsProgressBridge {
    private BootBridgeMtsProgressBridge() {
    }

    static void tryInstall(BootBridgeReporter reporter) {
        try {
            Class<?> loaderClass = Class.forName("com.evacipated.cardcrawl.modthespire.Loader");
            Class<?> listenerClass = Class.forName("com.evacipated.cardcrawl.modthespire.ex.ProgressPublish$Listener");
            Field progressField = loaderClass.getDeclaredField("progress");
            progressField.setAccessible(true);
            Object progressPublisher = progressField.get(null);
            Method setListener = progressPublisher.getClass().getMethod("setListener", listenerClass);

            InvocationHandler handler = (proxy, method, args) -> {
                if ("onStep".equals(method.getName()) && args != null && args.length >= 2) {
                    int mtsPercent = readPercent(args[1]);
                    String stepMessage = readStepMessage(args[0]);
                    int mappedPercent = mapProgress(mtsPercent);
                    String message = stepMessage.isEmpty()
                            ? "MTS phase " + mtsPercent + "%"
                            : "MTS: " + stepMessage;
                    reporter.phase(mappedPercent, message);
                }
                return null;
            };

            Object listenerProxy = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    handler
            );
            setListener.invoke(progressPublisher, listenerProxy);
            reporter.phase(32, "Attached MTS progress bridge");
        } catch (Throwable error) {
            reporter.phase(32, "MTS progress bridge unavailable: " + error.getClass().getSimpleName());
        }
    }

    private static int readPercent(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String readStepMessage(Object step) {
        if (step == null) {
            return "";
        }
        try {
            Method method = step.getClass().getMethod("message");
            Object value = method.invoke(step);
            return BootBridgeReporter.sanitizeMessage(value == null ? "" : String.valueOf(value));
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int mapProgress(int mtsPercent) {
        int bounded = clamp(mtsPercent, 0, 100);
        return 33 + Math.round(bounded * 0.62f);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
