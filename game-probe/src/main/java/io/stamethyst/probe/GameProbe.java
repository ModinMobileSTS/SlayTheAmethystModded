package io.stamethyst.probe;

import io.stamethyst.probe.connection.AgentConnectionManager;
import io.stamethyst.probe.monitors.impl.PlayMonitor;
import io.stamethyst.probe.monitors.impl.TracingMonitor;
import io.stamethyst.probe.monitors.MonitorRegistry;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Properties;

public final class GameProbe {

    private static final int DEFAULT_PORT = 9099;

    private static MonitorRegistry registry;
    private static Instrumentation instrumentation;
    private static AgentConnectionManager connectionManager;

    /**
     * Captured from the first non-delegating ClassLoader seen during premain
     * ClassFileTransformer callbacks.  This is the MTS URLClassLoader that
     * loads game and mod classes, inaccessible to the agent's own system
     * ClassLoader.
     */
    public static volatile ClassLoader GAME_CLASSLOADER;

    private GameProbe() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;

        Properties props = parseArgs(agentArgs);
        int port = Integer.parseInt(props.getProperty("port", String.valueOf(DEFAULT_PORT)));

        registry = new MonitorRegistry();
        registerBuiltinMonitors();

        // Capture the first non-agent ClassLoader on any class-load event.
        // The tracing transformer also does this but only for matching classes.
        // This transformer fires on every load, ensuring we capture early.
        if (inst != null) {
            inst.addTransformer(new ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String internalName,
                                        Class<?> classBeingRedefined,
                                        ProtectionDomain protectionDomain,
                                        byte[] classfileBuffer) {
                    if (GAME_CLASSLOADER == null && isCandidateGameClassLoader(loader, internalName)) {
                        GAME_CLASSLOADER = loader;
                        System.out.println("[game-probe] captured game ClassLoader: "
                            + loader.getClass().getName());
                    }
                    return null; // no transformation
                }
            }, false);
        }

        String premainSpec = props.getProperty("spec");
        if (premainSpec != null && !premainSpec.isEmpty()) {
            try {
                MonitorRegistry.ParsedSpec parsed = registry.parseSpec(premainSpec);
                io.stamethyst.probe.monitors.Monitor monitor = registry.create(premainSpec, inst, null);
                if (monitor != null) {
                    String agentId = parsed.prefix + "-premain";
                    io.stamethyst.probe.channel.AgentDataChannel channel =
                        new io.stamethyst.probe.channel.PremainDataChannel(agentId);
                    monitor.attach(inst, parsed.argsJson, channel);
                    System.out.println("[game-probe] premain auto-attach: " + agentId + " spec=" + premainSpec);
                }
            } catch (Throwable e) {
                System.err.println("[game-probe] premain attach failed: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }

        connectionManager = new AgentConnectionManager(registry, instrumentation, port);
        try {
            connectionManager.start();
        } catch (Exception e) {
            System.err.println("[game-probe] failed to start TCP server: " + e.getMessage());
        }
        OfflineArthasController.schedule(props, inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    public static MonitorRegistry getRegistry() {
        return registry;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public static AgentConnectionManager getConnectionManager() {
        return connectionManager;
    }

    private static void registerBuiltinMonitors() {
        registry.register("tracing", new MonitorRegistry.MonitorFactory() {
            @Override
            public io.stamethyst.probe.monitors.Monitor create(Instrumentation inst, String argsJson, io.stamethyst.probe.channel.AgentDataChannel channel) {
                return new TracingMonitor();
            }
        });

        registry.register("play", new MonitorRegistry.MonitorFactory() {
            @Override
            public io.stamethyst.probe.monitors.Monitor create(Instrumentation inst, String argsJson, io.stamethyst.probe.channel.AgentDataChannel channel) {
                return new PlayMonitor();
            }
        });
    }

    private static Properties parseArgs(String agentArgs) {
        Properties props = new Properties();
        if (agentArgs == null || agentArgs.isEmpty()) {
            return props;
        }
        String[] pairs = agentArgs.split(",");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                props.setProperty(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            } else if (eq < 0 && !pair.trim().isEmpty()) {
                props.setProperty(pair.trim(), "");
            }
        }
        return props;
    }

    static boolean isCandidateGameClassLoader(ClassLoader loader, String internalName) {
        if (loader == null || loader == ClassLoader.getSystemClassLoader() || internalName == null) {
            return false;
        }
        String loaderName = loader.getClass().getName();
        if (loaderName.contains("ExtClassLoader")) return false;
        return internalName.startsWith("com/megacrit/cardcrawl/")
            || internalName.startsWith("basemod/")
            || internalName.startsWith("com/evacipated/cardcrawl/modthespire/");
    }
}
