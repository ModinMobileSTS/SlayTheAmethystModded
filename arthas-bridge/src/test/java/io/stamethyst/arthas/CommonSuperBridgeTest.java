package io.stamethyst.arthas;

import org.junit.After;
import org.junit.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;

public class CommonSuperBridgeTest {

    @After
    public void tearDown() {
        CommonSuperBridge.setInstrumentation(null);
    }

    @Test
    public void unresolvedTypeFallsBackToObjectWhenBridgeIsActive() {
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { Instrumentation.class },
            (proxy, method, args) -> {
                if ("getAllLoadedClasses".equals(method.getName())) {
                    return new Class<?>[0];
                }
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return false;
                if (type == int.class) return 0;
                if (type == long.class) return 0L;
                return null;
            });
        CommonSuperBridge.setInstrumentation(instrumentation);

        assertEquals("java/lang/Object", CommonSuperBridge.resolveCommonSuper(
            getClass().getClassLoader(), "missing/One", "missing/Two"));
    }
}
