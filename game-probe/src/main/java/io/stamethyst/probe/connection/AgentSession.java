package io.stamethyst.probe.connection;

import io.stamethyst.probe.channel.AgentDataChannel;
import io.stamethyst.probe.channel.TcpDataChannel;
import io.stamethyst.probe.monitors.Monitor;
import io.stamethyst.probe.monitors.impl.PlayMonitor;
import io.stamethyst.probe.monitors.impl.TracingMonitor;
import io.stamethyst.probe.monitors.MonitorRegistry;
import io.stamethyst.probe.GameProbe;
import io.stamethyst.probe.protocol.AgentCommand;
import io.stamethyst.probe.protocol.AgentRequest;
import io.stamethyst.probe.protocol.AgentResponse;
import io.stamethyst.probe.util.ReflectionUtil;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.jar.JarFile;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AgentSession implements Runnable {

    private final Socket socket;
    private final MonitorRegistry registry;
    private final Instrumentation instrumentation;
    private final Map<String, Monitor> attachedAgents = new ConcurrentHashMap<String, Monitor>();
    private final Map<String, TcpDataChannel> agentChannels = new ConcurrentHashMap<String, TcpDataChannel>();
    private final Set<String> subscribedIds = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Map<String, Long> agentStartTimes = new ConcurrentHashMap<String, Long>();
    private final Map<String, AtomicInteger> agentEventCounts = new ConcurrentHashMap<String, AtomicInteger>();
    private final AtomicInteger counter = new AtomicInteger(1);
    private PrintWriter writer;
    private volatile boolean running = true;

    public AgentSession(Socket socket, MonitorRegistry registry, Instrumentation instrumentation) {
        this.socket = socket;
        this.registry = registry;
        this.instrumentation = instrumentation;
    }

    @Override
    public void run() {
        try {
            writer = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            String line;
            while (running && (line = reader.readLine()) != null) {
                try {
                    handleLine(line.trim());
                } catch (Exception e) {
                    writer.println(AgentResponse.error(e.getMessage()));
                }
            }
        } catch (IOException e) {
            writer.println(AgentResponse.error("IO error: " + e.getMessage()));
        } finally {
            cleanup();
        }
    }

    private void handleLine(String line) {
        if (line.isEmpty()) return;

        AgentRequest req = AgentRequest.parse(line);
        switch (req.getCommand()) {
            case ATTACH:
                handleAttach(req);
                break;
            case DETACH:
                handleDetach(req.getTarget());
                break;
            case LIST:
                handleList();
                break;
            case STATUS:
                handleStatus(req.getTarget());
                break;
            case SUBSCRIBE:
                handleSubscribe(req.getTarget());
                break;
            case UNSUBSCRIBE:
                handleUnsubscribe(req.getTarget());
                break;
            case OBSERVE:
                handleObserve();
                break;
            case READY:
                handleReady();
                break;
            case EXEC:
                handleExec(req);
                break;
            case PERF_START:
                handlePerfStart(req.getSpec());
                break;
            case PERF_STOP:
                handlePerfStop(req.getSpec());
                break;
            case DUMP_CLASS:
                handleDumpClass(req.getSpec());
                break;
            case REDEFINE_CLASS:
                handleRedefineClass(req.getSpec());
                break;
            case LOAD_AGENT:
                handleLoadAgent(req.getSpec(), req.getArgsJson());
                break;
            case CONSOLE:
                handleConsole(req);
                break;
            case QUIT:
                handleQuit();
                break;
        }
    }

    private void handleAttach(AgentRequest req) {
        String spec = req.getSpec();
        String id = generateId(spec);

        MonitorRegistry.ParsedSpec parsed = registry.parseSpec(spec);
        Monitor agent = registry.create(spec, instrumentation, null);
        TcpDataChannel channel = new TcpDataChannel(writer, id, agent.capabilities());
        agent.attach(instrumentation, parsed.argsJson, channel);
        attachedAgents.put(id, agent);
        agentChannels.put(id, channel);
        agentStartTimes.put(id, System.currentTimeMillis());
        agentEventCounts.put(id, new AtomicInteger(0));

        writer.println(AgentResponse.ok(id));
    }

    private String generateId(String spec) {
        String prefix = spec;
        int atIndex = spec.indexOf('@');
        if (atIndex >= 0) {
            prefix = spec.substring(0, atIndex);
        }
        return prefix + "-" + counter.getAndIncrement();
    }

    private void handleDetach(String id) {
        Monitor agent = attachedAgents.remove(id);
        if (agent == null) {
            writer.println(AgentResponse.error("agent not found: " + id));
            return;
        }
        agent.detach();
        subscribedIds.remove(id);
        agentChannels.remove(id);
        agentStartTimes.remove(id);
        agentEventCounts.remove(id);
        writer.println(AgentResponse.ok());
    }

    private void handleList() {
        String[] ids = attachedAgents.keySet().toArray(new String[0]);
        String[] states = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            Monitor agent = attachedAgents.get(ids[i]);
            states[i] = agent.status();
        }
        writer.println(AgentResponse.agents(ids, ids, states));
    }

    private void handleStatus(String id) {
        Monitor agent = attachedAgents.get(id);
        if (agent == null) {
            writer.println(AgentResponse.error("agent not found: " + id));
            return;
        }
        Long startTime = agentStartTimes.get(id);
        AtomicInteger count = agentEventCounts.get(id);
        long uptime = startTime != null ? System.currentTimeMillis() - startTime : 0;
        int events = count != null ? count.get() : 0;
        writer.println(AgentResponse.status(id, agent.status(), uptime, events));
    }

    private void handleSubscribe(String id) {
        if (!attachedAgents.containsKey(id)) {
            writer.println(AgentResponse.error("agent not found: " + id));
            return;
        }
        subscribedIds.add(id);
        TcpDataChannel ch = agentChannels.get(id);
        if (ch != null) {
            ch.setSubscribed(true);
        }
        writer.println(AgentResponse.ok());
    }

    private void handleUnsubscribe(String id) {
        subscribedIds.remove(id);
        TcpDataChannel ch = agentChannels.get(id);
        if (ch != null) {
            ch.setSubscribed(false);
        }
        writer.println(AgentResponse.ok());
    }

    private void handleQuit() {
        writer.println(AgentResponse.bye());
        running = false;
        writer.flush();
    }

    // ── Extended protocol handlers (Stages 2-5) ─────────────────────

    private void handleObserve() {
        PlayMonitor play = PlayMonitor.INSTANCE;
        if (play == null) {
            writer.println(AgentResponse.state("{\"available\":false}"));
            return;
        }
        String state = play.observe();
        // Inject a diagnostic marker if the game ClassLoader hasn't been captured yet
        if (GameProbe.GAME_CLASSLOADER == null && state.startsWith("{")) {
            state = "{\"_cl_diag\":\"game_classloader_not_yet_captured\"," + state.substring(1);
        }
        writer.println(AgentResponse.state(state));
    }

    private void handleReady() {
        Class<?> devConsole = ReflectionUtil.forName("basemod.DevConsole");
        if (devConsole == null) {
            writer.println(AgentResponse.error("BaseMod DevConsole not loaded"));
            return;
        }
        try {
            devConsole.getMethod("execute");
            Class<?> consoleCommand = findConsoleCommandClass("art");
            if (consoleCommand == null) {
                writer.println(AgentResponse.error("ArtFramework console command not registered"));
                return;
            }
            writer.println("READY");
        } catch (NoSuchMethodException e) {
            writer.println(AgentResponse.error("BaseMod DevConsole execute() unavailable"));
        }
    }

    private void handleExec(AgentRequest req) {
        PlayMonitor play = PlayMonitor.INSTANCE;
        if (play == null) {
            writer.println(AgentResponse.result("{\"executed\":false,\"error\":\"play monitor not attached\"}"));
            return;
        }
        writer.println(AgentResponse.result(play.execute(req.getSpec(), req.getArgsJson())));
    }

    private void handleConsole(AgentRequest req) {
        String commandText = req.getSpec();
        if (commandText == null || commandText.trim().isEmpty()) {
            writer.println(AgentResponse.result("{\"executed\":false,\"error\":\"empty console command\"}"));
            return;
        }
        try {
            String result = invokeDevConsole(commandText.trim());
            writer.println(AgentResponse.result("{\"executed\":true,\"command\":\"" + escapeJson(commandText.trim()) +
                "\",\"output\":\"" + escapeJson(result) + "\"}"));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            writer.println(AgentResponse.result("{\"executed\":false,\"error\":\"" + escapeJson(msg) + "\"}"));
        }
    }

    private String invokeDevConsole(String commandText) throws Exception {
        Class<?> devConsoleClass = ReflectionUtil.forName("basemod.DevConsole");
        if (devConsoleClass == null) {
            throw new RuntimeException("BaseMod DevConsole not loaded");
        }

        try {
            Method executeMethod = devConsoleClass.getMethod("execute", String.class);
            Object result = executeMethod.invoke(null, commandText);
            return result != null ? result.toString() : "ok";
        } catch (NoSuchMethodException e) {
            // BaseMod's released DevConsole exposes execute() and reads currentText. Use
            // that native entry point first so the command and ConsoleCommand registry stay
            // in the same game classloader. Older/probe test shims may still expose the
            // commands-map API below.
            try {
                Field currentText = devConsoleClass.getField("currentText");
                Method executeMethod = devConsoleClass.getMethod("execute");
                currentText.set(null, commandText);
                try {
                    executeMethod.invoke(null);
                } catch (InvocationTargetException target) {
                    Throwable cause = target.getCause();
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    throw target;
                }
                return "ok";
            } catch (NoSuchFieldException ignored) {
                // fall through to commands-map approach
            }
        }

        String[] tokens = commandText.split("\\s+");
        String commandName = tokens[0];
        Class<?> consoleCommandClass = findConsoleCommandClass(commandName);
        if (consoleCommandClass != null) {
            try {
                Method executeMethod = consoleCommandClass.getMethod("execute", String[].class);
                executeMethod.invoke(null, (Object) tokens);
                return "ok";
            } catch (NoSuchMethodException e) {
                // fall through to legacy commands-map approach
            }
        }

        if (ReflectionUtil.forName("basemod.devcommands.ConsoleCommand") != null) {
            throw new RuntimeException("unknown console command: " + commandName);
        }

        Field commandsField = devConsoleClass.getDeclaredField("commands");
        commandsField.setAccessible(true);
        Object commandsObj = commandsField.get(null);
        if (!(commandsObj instanceof java.util.Map)) {
            throw new RuntimeException("DevConsole.commands is not a Map");
        }
        java.util.Map<?, ?> commands = (java.util.Map<?, ?>) commandsObj;

        String[] args = new String[tokens.length - 1];
        System.arraycopy(tokens, 1, args, 0, args.length);

        Object consoleCommand = commands.get(commandName);
        if (consoleCommand == null) {
            throw new RuntimeException("unknown console command: " + commandName);
        }

        Method execMethod = consoleCommand.getClass().getMethod("execute", String[].class);
        execMethod.invoke(consoleCommand, (Object) args);
        return "ok";
    }

    private Class<?> findConsoleCommandClass(String commandName) {
        String className = "basemod.devcommands.ConsoleCommand";
        Class<?> resolved = ReflectionUtil.forName(className);
        if (registryContains(resolved, commandName)) {
            return resolved;
        }

        Instrumentation inst = instrumentation != null ? instrumentation : GameProbe.getInstrumentation();
        if (inst != null) {
            for (Class<?> loaded : inst.getAllLoadedClasses()) {
                if (className.equals(loaded.getName()) && loaded != resolved
                    && registryContains(loaded, commandName)) {
                    return loaded;
                }
            }
        }
        return null;
    }

    private static boolean registryContains(Class<?> consoleCommandClass, String commandName) {
        if (consoleCommandClass == null) return false;
        try {
            Field rootField = consoleCommandClass.getDeclaredField("root");
            rootField.setAccessible(true);
            Object root = rootField.get(null);
            return root instanceof Map && ((Map<?, ?>) root).containsKey(commandName);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private void handlePerfStart(String agentId) {
        Monitor agent = attachedAgents.get(agentId);
        if (agent instanceof TracingMonitor) {
            ((TracingMonitor) agent).perfStart();
            writer.println(AgentResponse.ok());
        } else {
            writer.println(AgentResponse.error("not a tracing agent: " + agentId));
        }
    }

    private void handlePerfStop(String agentId) {
        Monitor agent = attachedAgents.get(agentId);
        if (agent instanceof TracingMonitor) {
            writer.println(AgentResponse.perf(((TracingMonitor) agent).perfStop()));
        } else {
            writer.println(AgentResponse.error("not a tracing agent: " + agentId));
        }
    }

    private void handleDumpClass(String className) {
        if (className == null || className.trim().isEmpty()) {
            writer.println(AgentResponse.error("DUMP_CLASS requires a class name"));
            return;
        }
        try {
            String fqcn = className.trim();
            Class<?> cls = resolveClass(fqcn);
            if (cls == null) {
                writer.println(AgentResponse.error("class not found: " + fqcn));
                return;
            }
            byte[] bytes = readClassBytes(cls);
            if (bytes == null) {
                writer.println(AgentResponse.error("class resource not found: " + fqcn));
                return;
            }
            String b64 = java.util.Base64.getEncoder().encodeToString(bytes);
            writer.println(AgentResponse.bytecodeHeader() + b64);
        } catch (Exception e) {
            writer.println(AgentResponse.error("dump failed: " + e.getMessage()));
        }
    }

    private byte[] readClassBytes(Class<?> cls) throws java.io.IOException {
        String relativePath = cls.getName().replace('.', '/') + ".class";
        // 1) Class.getResourceAsStream — delegates to the class's own loader,
        //    handles both jar-resource and file-system cases correctly.
        java.io.InputStream is = cls.getResourceAsStream("/" + relativePath);
        if (is != null) {
            try { return readAllBytes(is); }
            finally { try { is.close(); } catch (Exception ignored) {} }
        }
        // 2) ClassLoader without leading /
        ClassLoader cl = cls.getClassLoader();
        if (cl != null) {
            is = cl.getResourceAsStream(relativePath);
            if (is != null) {
                try { return readAllBytes(is); }
                finally { try { is.close(); } catch (Exception ignored) {} }
            }
        }
        // 3) System classloader
        is = ClassLoader.getSystemResourceAsStream(relativePath);
        if (is != null) {
            try { return readAllBytes(is); }
            finally { try { is.close(); } catch (Exception ignored) {} }
        }
        return null;
    }

    private byte[] readAllBytes(java.io.InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) >= 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private Class<?> resolveClass(String fqcn) {
        return io.stamethyst.probe.util.ReflectionUtil.forName(fqcn);
    }

    private void handleRedefineClass(String b64) {
        if (b64 == null || b64.trim().isEmpty()) {
            writer.println(AgentResponse.error("REDEFINE_CLASS requires base64-encoded class bytes"));
            return;
        }
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(b64.trim());
            // Extract the internal class name from the bytecode
            org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(bytes);
            String internalName = cr.getClassName();
            String className = internalName.replace('/', '.');
            Class<?> cls = findLoadedClass(className);
            if (cls == null) {
                writer.println(AgentResponse.error("class not loaded: " + className));
                return;
            }
            instrumentation.redefineClasses(
                new java.lang.instrument.ClassDefinition(cls, bytes));
            writer.println(AgentResponse.ok());
        } catch (Exception e) {
            writer.println(AgentResponse.error("redefine failed: " + e.getMessage()));
        }
    }

    private void handleLoadAgent(String jarPath, String agentArgs) {
        if (jarPath == null || jarPath.trim().isEmpty()) {
            writer.println(AgentResponse.error("LOAD_AGENT requires a jar path"));
            return;
        }
        try {
            jarPath = jarPath.trim();
            File jarFile = new File(jarPath);
            if (!jarFile.isFile()) {
                writer.println(AgentResponse.error("JAR not found: " + jarPath));
                return;
            }
            JarFile jar = new JarFile(jarPath);
            String agentClass = jar.getManifest().getMainAttributes().getValue("Agent-Class");
            if (agentClass == null || agentClass.isEmpty()) {
                agentClass = jar.getManifest().getMainAttributes().getValue("Premain-Class");
            }
            jar.close();

            if (agentClass == null || agentClass.isEmpty()) {
                jar = new JarFile(jarPath);
                instrumentation.appendToSystemClassLoaderSearch(jar);
                writer.println(AgentResponse.ok());
                return;
            }

            URL[] urls = {jarFile.toURI().toURL()};
            ClassLoader isolated = new URLClassLoader(urls, ClassLoader.getSystemClassLoader());
            Class<?> cls = isolated.loadClass(agentClass);
            Method m = cls.getMethod("agentmain", String.class, Instrumentation.class);
            String args = agentArgs != null ? agentArgs : "";
            m.invoke(null, args, instrumentation);

            jar = new JarFile(jarPath);
            instrumentation.appendToSystemClassLoaderSearch(jar);

            writer.println(AgentResponse.ok());
        } catch (Throwable e) {
            String msg = e.getMessage();
            if (msg == null) msg = e.getClass().getName();
            writer.println(AgentResponse.error("load agent failed: " + msg));
        }
    }

    private Class<?> findLoadedClass(String className) {
        for (Class<?> cls : instrumentation.getAllLoadedClasses()) {
            if (cls.getName().equals(className)) {
                return cls;
            }
        }
        return io.stamethyst.probe.util.ReflectionUtil.forName(className);
    }

    public void sendToSubscriber(String agentId, String jsonPayload) {
        if (subscribedIds.contains(agentId)) {
            AtomicInteger count = agentEventCounts.get(agentId);
            if (count != null) {
                count.incrementAndGet();
            }
            writer.println(AgentResponse.data(agentId, jsonPayload));
            writer.flush();
        }
    }

    private void cleanup() {
        for (Map.Entry<String, Monitor> entry : attachedAgents.entrySet()) {
            try {
                entry.getValue().detach();
            } catch (Exception ignored) {}
        }
        attachedAgents.clear();
        agentChannels.clear();
        subscribedIds.clear();
        try { writer.close(); } catch (Exception ignored) {}
        try { socket.close(); } catch (Exception ignored) {}
    }
}
