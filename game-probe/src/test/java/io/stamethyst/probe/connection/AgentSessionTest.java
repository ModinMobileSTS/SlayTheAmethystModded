package io.stamethyst.probe.connection;

import io.stamethyst.probe.channel.AgentDataChannel;
import io.stamethyst.probe.GameProbe;
import io.stamethyst.probe.monitors.Monitor;
import io.stamethyst.probe.monitors.MonitorCapability;
import io.stamethyst.probe.monitors.MonitorRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.*;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

import static org.junit.Assert.*;

public class AgentSessionTest {

    private MonitorRegistry registry;
    private ServerSocket server;
    private Socket serverSide;
    private Socket clientSide;
    private BufferedReader reader;
    private PrintWriter writer;
    private Thread sessionThread;
    private AgentSession currentSession;
    private ClassLoader previousGameClassLoader;

    @Before
    public void setUp() throws Exception {
        previousGameClassLoader = GameProbe.GAME_CLASSLOADER;
        registry = new MonitorRegistry();
        registry.register("mock", new MonitorRegistry.MonitorFactory() {
            @Override
            public Monitor create(Instrumentation inst, String argsJson, AgentDataChannel channel) {
                return new MockMonitor("mock-instance-" + argsJson, channel);
            }
        });
        registry.register("tracing", new MonitorRegistry.MonitorFactory() {
            @Override
            public Monitor create(Instrumentation inst, String argsJson, AgentDataChannel channel) {
                return new MockMonitor("tracing-instance", channel);
            }
        });

        server = new ServerSocket(0);
        Thread acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSide = server.accept();
                } catch (IOException ignored) {}
            }
        });
        acceptThread.start();

        clientSide = new Socket("127.0.0.1", server.getLocalPort());
        acceptThread.join(1000);
        assertNotNull("server failed to accept", serverSide);

        writer = new PrintWriter(clientSide.getOutputStream(), true);
    }

    @After
    public void tearDown() throws Exception {
        GameProbe.GAME_CLASSLOADER = previousGameClassLoader;
        try { clientSide.close(); } catch (Exception ignored) {}
        try { serverSide.close(); } catch (Exception ignored) {}
        try { server.close(); } catch (Exception ignored) {}
    }

    private BufferedReader startSession() throws Exception {
        return startSession(null);
    }

    private BufferedReader startSession(Instrumentation instrumentation) throws Exception {
        currentSession = new AgentSession(serverSide, registry, instrumentation);
        sessionThread = new Thread(currentSession);
        sessionThread.start();
        return new BufferedReader(new InputStreamReader(clientSide.getInputStream()));
    }

    @Test
    public void attachReturnsAgentId() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("ATTACH mock {}");
        String response = serverReader.readLine();
        assertTrue(response, response.startsWith("OK mock-"));
    }

    @Test
    public void attachReturnsDifferentIds() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("ATTACH mock {}");
        String id1 = serverReader.readLine().substring(3);

        writer.println("ATTACH mock {}");
        String id2 = serverReader.readLine().substring(3);

        assertNotEquals(id1, id2);
    }

    @Test
    public void listShowsAttachedAgents() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("ATTACH mock {}");
        String ok1 = serverReader.readLine();
        String id1 = ok1.substring(3);

        writer.println("LIST");
        String list = serverReader.readLine();
        assertTrue(list, list.startsWith("MONITORS"));
        assertTrue(list.contains(id1));
    }

    @Test
    public void detachRemovesAgent() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("ATTACH mock {}");
        String id1 = serverReader.readLine().substring(3);

        writer.println("DETACH " + id1);
        String ok = serverReader.readLine();
        assertEquals("OK", ok);

        writer.println("LIST");
        String list = serverReader.readLine();
        assertEquals("MONITORS", list.trim());
    }

    @Test
    public void statusReportsAgentState() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("ATTACH mock {}");
        String id1 = serverReader.readLine().substring(3);

        writer.println("STATUS " + id1);
        String status = serverReader.readLine();
        assertTrue(status, status.startsWith("STATUS " + id1 + " "));
    }

    @Test
    public void subscribeDeliversData() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("ATTACH mock {}");
        String id1 = serverReader.readLine().substring(3);

        writer.println("SUBSCRIBE " + id1);
        String ok = serverReader.readLine();
        assertEquals("OK", ok);

        String testJson = "{\"type\":\"test_event\",\"value\":42}";
        currentSession.sendToSubscriber(id1, testJson);

        String dataLine = serverReader.readLine();
        assertEquals("DATA " + id1 + " " + testJson, dataLine);
    }

    @Test
    public void unsubscribeStopsDataDelivery() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("ATTACH mock {}");
        String id1 = serverReader.readLine().substring(3);

        writer.println("SUBSCRIBE " + id1);
        serverReader.readLine();

        writer.println("UNSUBSCRIBE " + id1);
        assertEquals("OK", serverReader.readLine());

        currentSession.sendToSubscriber(id1, "{\"type\":\"should_not_arrive\"}");

        writer.println("QUIT");
        assertEquals("BYE", serverReader.readLine());
    }

    @Test
    public void quitReturnsBye() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("QUIT");
        assertEquals("BYE", serverReader.readLine());
        sessionThread.join(1000);
        assertFalse(sessionThread.isAlive());
    }

    @Test
    public void unknownAgentStatus() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("STATUS nonexistent-1");
        String response = serverReader.readLine();
        assertTrue(response, response.startsWith("ERROR"));
    }

    @Test
    public void detachUnknownAgent() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("DETACH nonexistent-1");
        String response = serverReader.readLine();
        assertTrue(response, response.startsWith("ERROR"));
    }

    @Test
    public void malformedCommandReturnsError() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("GARBAGE");
        String response = serverReader.readLine();
        assertTrue(response, response.startsWith("ERROR"));
    }

    @Test
    public void consoleReturnsErrorWhenBaseModNotLoaded() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("CONSOLE gold 999");
        String response = serverReader.readLine();
        assertTrue(response, response.startsWith("RESULT "));
        assertTrue(response, response.contains("\"executed\":false"));
        assertTrue(response, response.contains("DevConsole not loaded"));
    }

    @Test
    public void readyReturnsErrorWhenBaseModNotLoaded() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("READY");
        String response = serverReader.readLine();
        assertTrue(response, response.startsWith("ERROR "));
        assertTrue(response, response.contains("DevConsole not loaded"));
    }

    @Test
    public void consoleUsesCapturedGameClassLoader() throws Exception {
        GameProbe.GAME_CLASSLOADER = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!"basemod.DevConsole".equals(name)) return super.findClass(name);
                byte[] bytes = devConsoleClassBytes();
                return defineClass(name, bytes, 0, bytes.length);
            }
        };

        BufferedReader serverReader = startSession();
        writer.println("CONSOLE crossspire status");
        String response = serverReader.readLine();

        assertTrue(response, response.startsWith("RESULT "));
        assertTrue(response, response.contains("\"executed\":true"));
        assertTrue(response, response.contains("crossspire status"));
    }

    @Test
    public void releasedConsoleStaysNotReadyUntilAllConstructorMarkersPublished() throws Exception {
        GameProbe.GAME_CLASSLOADER = releasedConsoleLoader();
        Class<?> console = Class.forName("basemod.DevConsole", true, GameProbe.GAME_CLASSLOADER);
        assertEquals("initial", console.getField("currentText").get(null));
        assertNull(console.getField("priorCommands").get(null));
        assertNull(console.getField("log").get(null));
        assertNull(console.getField("prompted").get(null));

        BufferedReader serverReader = startSession();
        writer.println("READY");
        String beforeReady = serverReader.readLine();
        assertTrue(beforeReady, beforeReady.startsWith("ERROR "));
        assertTrue(beforeReady, beforeReady.contains("not ready"));

        writer.println("CONSOLE art status");
        String beforeConsole = serverReader.readLine();
        assertTrue(beforeConsole, beforeConsole.contains("\"executed\":false"));
        assertTrue(beforeConsole, beforeConsole.contains("not ready"));
        assertEquals("initial", console.getField("currentText").get(null));
        assertEquals(0, console.getField("executions").getInt(null));

        // DevConsole's constructor assigns priorCommands, then commandPos, log, prompted.
        // Simulate the half-constructed window after priorCommands is set but before the
        // remaining markers are published: execute() would NPE, so the probe must refuse
        // and must not overwrite currentText.
        console.getField("priorCommands").set(null, new ArrayList<String>());
        writer.println("READY");
        String partialReady = serverReader.readLine();
        assertTrue(partialReady, partialReady.startsWith("ERROR "));
        assertTrue(partialReady, partialReady.contains("not ready"));

        writer.println("CONSOLE art status");
        String partialConsole = serverReader.readLine();
        assertTrue(partialConsole, partialConsole.contains("\"executed\":false"));
        assertTrue(partialConsole, partialConsole.contains("not ready"));
        assertEquals("initial", console.getField("currentText").get(null));
        assertEquals(0, console.getField("executions").getInt(null));

        // log published, prompted still missing -> still not ready.
        console.getField("log").set(null, new ArrayList<String>());
        writer.println("READY");
        assertTrue(serverReader.readLine().startsWith("ERROR "));

        // All three constructor markers published -> ready and executable.
        console.getField("prompted").set(null, Boolean.TRUE);
        writer.println("READY");
        assertEquals("READY", serverReader.readLine());
        writer.println("CONSOLE art status");
        String afterConsole = serverReader.readLine();
        assertTrue(afterConsole, afterConsole.contains("\"executed\":true"));
        assertEquals("art status", console.getField("currentText").get(null));
        assertEquals(1, console.getField("executions").getInt(null));
    }

    @Test
    public void failedDevConsoleInitializationDoesNotEndSessionOrReportExecution() throws Exception {
        GameProbe.GAME_CLASSLOADER = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!"basemod.DevConsole".equals(name)) return super.findClass(name);
                byte[] bytes = releasedDevConsoleClassBytes(true);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        clientSide.setSoTimeout(2000);
        BufferedReader serverReader = startSession();
        writer.println("READY");
        String firstReady = serverReader.readLine();
        assertTrue(firstReady, firstReady.startsWith("ERROR "));
        assertTrue(firstReady, firstReady.contains("ExceptionInInitializerError"));

        writer.println("CONSOLE art status");
        String console = serverReader.readLine();
        assertTrue(console, console.startsWith("RESULT "));
        assertTrue(console, console.contains("\"executed\":false"));
        assertTrue(console, console.contains("NoClassDefFoundError"));

        writer.println("READY");
        String secondReady = serverReader.readLine();
        assertTrue(secondReady, secondReady.startsWith("ERROR "));
        assertTrue(secondReady, secondReady.contains("NoClassDefFoundError"));
        writer.println("QUIT");
        assertEquals("BYE", serverReader.readLine());
    }

    private static ClassLoader releasedConsoleLoader() {
        return releasedConsoleLoader("art");
    }

    private static ClassLoader releasedConsoleLoader(final String rootCommand) {
        return new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes;
                if ("basemod.DevConsole".equals(name)) {
                    bytes = releasedDevConsoleClassBytes();
                } else if ("basemod.devcommands.ConsoleCommand".equals(name)) {
                    bytes = consoleCommandClassBytes(rootCommand);
                } else {
                    return super.findClass(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    @Test
    public void readyAllowsOldShimWithoutPriorCommands() throws Exception {
        GameProbe.GAME_CLASSLOADER = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes;
                if ("basemod.DevConsole".equals(name)) {
                    bytes = noArgDevConsoleClassBytes();
                } else if ("basemod.devcommands.ConsoleCommand".equals(name)) {
                    bytes = consoleCommandClassBytes("art");
                } else {
                    return super.findClass(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        BufferedReader serverReader = startSession();
        writer.println("READY");
        assertEquals("READY", serverReader.readLine());
        writer.println("CONSOLE art status");
        String response = serverReader.readLine();
        assertTrue(response, response.contains("\"executed\":true"));
    }

    @Test
    public void consoleUsesBaseModConsoleCommandApi() throws Exception {
        GameProbe.GAME_CLASSLOADER = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes;
                if ("basemod.DevConsole".equals(name)) {
                    bytes = emptyClassBytes("basemod/DevConsole");
                } else if ("basemod.devcommands.ConsoleCommand".equals(name)) {
                    bytes = consoleCommandClassBytes("crossspire");
                } else {
                    return super.findClass(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };

        BufferedReader serverReader = startSession();
        writer.println("CONSOLE crossspire status");
        String response = serverReader.readLine();

        assertTrue(response, response.startsWith("RESULT "));
        assertTrue(response, response.contains("\"executed\":true"));
    }

    @Test
    public void consoleFindsCommandInDuplicateLoadedClass() throws Exception {
        ClassLoader wrongLoader = consoleClassLoader(null);
        GameProbe.GAME_CLASSLOADER = wrongLoader;
        wrongLoader.loadClass("basemod.devcommands.ConsoleCommand");

        ClassLoader rightLoader = consoleClassLoader("art");
        Class<?> rightConsoleCommand = rightLoader.loadClass("basemod.devcommands.ConsoleCommand");

        BufferedReader serverReader = startSession(new FakeInstrumentation(
            wrongLoader.loadClass("basemod.devcommands.ConsoleCommand"), rightConsoleCommand));
        writer.println("CONSOLE art status");
        String response = serverReader.readLine();

        assertTrue(response, response.startsWith("RESULT "));
        assertTrue(response, response.contains("\"executed\":true"));
    }

    @Test
    public void consoleRejectsUnknownCommandAcrossDuplicateLoadedClasses() throws Exception {
        ClassLoader wrongLoader = consoleClassLoader(null);
        GameProbe.GAME_CLASSLOADER = wrongLoader;
        Class<?> wrongConsoleCommand = wrongLoader.loadClass("basemod.devcommands.ConsoleCommand");

        ClassLoader otherLoader = consoleClassLoader("art");
        Class<?> otherConsoleCommand = otherLoader.loadClass("basemod.devcommands.ConsoleCommand");

        BufferedReader serverReader = startSession(
            new FakeInstrumentation(wrongConsoleCommand, otherConsoleCommand));
        writer.println("CONSOLE missing status");
        String response = serverReader.readLine();

        assertTrue(response, response.startsWith("RESULT "));
        assertTrue(response, response.contains("\"executed\":false"));
        assertTrue(response, response.contains("unknown console command: missing"));
    }

    @Test
    public void readyAndConsolePreferConstructedDevConsoleOverUninitializedDuplicate() throws Exception {
        // B: the copy the game actually constructed -- all three constructor markers published.
        ClassLoader initializedLoader = releasedConsoleLoader("art");
        Class<?> consoleB = Class.forName("basemod.DevConsole", true, initializedLoader);
        consoleB.getDeclaredConstructor().newInstance();
        assertNotNull(consoleB.getField("priorCommands").get(null));
        assertNotNull(consoleB.getField("log").get(null));
        assertNotNull(consoleB.getField("prompted").get(null));
        Class<?> commandB = initializedLoader.loadClass("basemod.devcommands.ConsoleCommand");

        // A: the stale duplicate -- never constructed, so its markers stay null. Its loader
        // is captured as GAME_CLASSLOADER, so ReflectionUtil.forName keeps returning it.
        ClassLoader staleLoader = releasedConsoleLoader(null);
        Class<?> consoleA = Class.forName("basemod.DevConsole", true, staleLoader);
        assertNull(consoleA.getField("priorCommands").get(null));
        assertNull(consoleA.getField("log").get(null));
        assertNull(consoleA.getField("prompted").get(null));
        GameProbe.GAME_CLASSLOADER = staleLoader;

        BufferedReader serverReader = startSession(new FakeInstrumentation(consoleA, consoleB, commandB));

        writer.println("READY");
        assertEquals("READY", serverReader.readLine());

        writer.println("CONSOLE art status");
        String response = serverReader.readLine();
        assertTrue(response, response.startsWith("RESULT "));
        assertTrue(response, response.contains("\"executed\":true"));

        // execute() ran against B, not the unconstructed A.
        assertEquals("art status", consoleB.getField("currentText").get(null));
        assertEquals(1, consoleB.getField("executions").getInt(null));
        assertEquals(0, consoleA.getField("executions").getInt(null));
    }

    @Test
    public void linkageErrorDuplicateDoesNotEndSession() throws Exception {
        // Broken copy: touching its static fields runs a <clinit> that throws, so every
        // later access raises NoClassDefFoundError. Both are LinkageErrors.
        ClassLoader brokenLoader = brokenConsoleLoader();
        Class<?> brokenConsole = Class.forName("basemod.DevConsole", false, brokenLoader);
        brokenLoader.loadClass("basemod.devcommands.ConsoleCommand");

        // Good copy: constructed, and its loader owns the art console command.
        ClassLoader goodLoader = releasedConsoleLoader("art");
        Class<?> goodConsole = Class.forName("basemod.DevConsole", true, goodLoader);
        goodConsole.getDeclaredConstructor().newInstance();
        Class<?> goodCommand = goodLoader.loadClass("basemod.devcommands.ConsoleCommand");

        // ReflectionUtil resolves to the broken copy, so only the scan can find the good one.
        GameProbe.GAME_CLASSLOADER = brokenLoader;
        clientSide.setSoTimeout(2000);

        BufferedReader serverReader = startSession(
            new FakeInstrumentation(brokenConsole, goodConsole, goodCommand));

        writer.println("READY");
        assertEquals("READY", serverReader.readLine());

        writer.println("CONSOLE art status");
        String response = serverReader.readLine();
        assertTrue(response, response.startsWith("RESULT "));
        assertTrue(response, response.contains("\"executed\":true"));

        // The constructed copy ran; the broken copy was never selected/driven.
        assertEquals("art status", goodConsole.getField("currentText").get(null));
        assertEquals(1, goodConsole.getField("executions").getInt(null));

        // The session survived touching the broken copy and still serves commands.
        writer.println("READY");
        assertEquals("READY", serverReader.readLine());
        writer.println("QUIT");
        assertEquals("BYE", serverReader.readLine());
    }

    @Test
    public void linkageErrorDuplicateConsoleCommandIsSkippedInScan() throws Exception {
        // Resolved console + command come from a loader whose command registry lacks art,
        // so resolution must fall through to the loaded-class scan.
        ClassLoader resolvedLoader = releasedConsoleLoader(null);
        resolvedLoader.loadClass("basemod.devcommands.ConsoleCommand");
        GameProbe.GAME_CLASSLOADER = resolvedLoader;

        // Broken duplicate: reading ConsoleCommand.root runs a <clinit> that throws, so the
        // access raises ExceptionInInitializerError / NoClassDefFoundError (both LinkageError).
        ClassLoader brokenLoader = brokenConsoleCommandLoader();
        Class<?> brokenCommand = brokenLoader.loadClass("basemod.devcommands.ConsoleCommand");

        // Good duplicate: its loader owns the constructed console and the art command.
        ClassLoader goodLoader = releasedConsoleLoader("art");
        Class<?> goodConsole = Class.forName("basemod.DevConsole", true, goodLoader);
        goodConsole.getDeclaredConstructor().newInstance();
        Class<?> goodCommand = goodLoader.loadClass("basemod.devcommands.ConsoleCommand");

        clientSide.setSoTimeout(2000);

        // The broken candidate is listed first: the scan must skip it, not abort.
        BufferedReader serverReader = startSession(
            new FakeInstrumentation(brokenCommand, goodConsole, goodCommand));

        writer.println("READY");
        assertEquals("READY", serverReader.readLine());

        writer.println("CONSOLE art status");
        String response = serverReader.readLine();
        assertTrue(response, response.startsWith("RESULT "));
        assertTrue(response, response.contains("\"executed\":true"));

        // The constructed art-loader console ran; the broken duplicate was never driven.
        assertEquals("art status", goodConsole.getField("currentText").get(null));
        assertEquals(1, goodConsole.getField("executions").getInt(null));

        writer.println("QUIT");
        assertEquals("BYE", serverReader.readLine());
    }

    @Test
    public void resolveFallsBackToReflectionWhenInstrumentationSeesNoLoadedClasses() throws Exception {
        ClassLoader consoleLoader = releasedConsoleLoader("art");
        Class<?> console = Class.forName("basemod.DevConsole", true, consoleLoader);
        console.getDeclaredConstructor().newInstance();
        consoleLoader.loadClass("basemod.devcommands.ConsoleCommand");
        GameProbe.GAME_CLASSLOADER = consoleLoader;

        BufferedReader serverReader = startSession(new FakeInstrumentation());
        writer.println("READY");
        assertEquals("READY", serverReader.readLine());

        writer.println("CONSOLE art status");
        String response = serverReader.readLine();
        assertTrue(response, response.startsWith("RESULT "));
        assertTrue(response, response.contains("\"executed\":true"));
        assertEquals(1, console.getField("executions").getInt(null));
    }

    @Test
    public void resolvePrefersInitializedCopySharingArtCommandLoader() throws Exception {
        // Both copies are constructed; only the classloader owning the art command
        // distinguishes them.
        ClassLoader otherLoader = releasedConsoleLoader(null);
        Class<?> otherConsole = Class.forName("basemod.DevConsole", true, otherLoader);
        otherConsole.getDeclaredConstructor().newInstance();
        Class<?> otherCommand = otherLoader.loadClass("basemod.devcommands.ConsoleCommand");

        ClassLoader artLoader = releasedConsoleLoader("art");
        Class<?> artConsole = Class.forName("basemod.DevConsole", true, artLoader);
        artConsole.getDeclaredConstructor().newInstance();
        Class<?> artCommand = artLoader.loadClass("basemod.devcommands.ConsoleCommand");

        // ReflectionUtil resolves to the non-art copy; selection must come from the scan.
        GameProbe.GAME_CLASSLOADER = otherLoader;

        // The non-art copy is listed first, so only the loader-match priority branch
        // makes the probe drive artConsole.
        BufferedReader serverReader = startSession(
            new FakeInstrumentation(otherConsole, otherCommand, artConsole, artCommand));

        writer.println("READY");
        assertEquals("READY", serverReader.readLine());

        writer.println("CONSOLE art status");
        String response = serverReader.readLine();
        assertTrue(response, response.startsWith("RESULT "));
        assertTrue(response, response.contains("\"executed\":true"));

        assertEquals(1, artConsole.getField("executions").getInt(null));
        assertEquals(0, otherConsole.getField("executions").getInt(null));
    }

    @Test
    public void consoleMainBranchRefusesUnconstructedReleasedConsole() throws Exception {
        // This released-style copy also exposes execute(String), so invokeDevConsole takes
        // its main branch. Without the initialization gate it would run on an
        // unconstructed console.
        ClassLoader loader = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes;
                if ("basemod.DevConsole".equals(name)) {
                    bytes = releasedDevConsoleWithArgExecuteClassBytes();
                } else if ("basemod.devcommands.ConsoleCommand".equals(name)) {
                    bytes = consoleCommandClassBytes("art");
                } else {
                    return super.findClass(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> console = Class.forName("basemod.DevConsole", true, loader);
        loader.loadClass("basemod.devcommands.ConsoleCommand");
        GameProbe.GAME_CLASSLOADER = loader;

        BufferedReader serverReader = startSession();
        writer.println("CONSOLE art status");
        String response = serverReader.readLine();
        assertTrue(response, response.startsWith("RESULT "));
        assertTrue(response, response.contains("\"executed\":false"));
        assertTrue(response, response.contains("not ready"));
        assertEquals(0, console.getField("executions").getInt(null));
    }

    private static ClassLoader brokenConsoleLoader() {
        return new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes;
                if ("basemod.DevConsole".equals(name)) {
                    bytes = releasedDevConsoleClassBytes(true);
                } else if ("basemod.devcommands.ConsoleCommand".equals(name)) {
                    bytes = consoleCommandClassBytes(null);
                } else {
                    return super.findClass(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    private static ClassLoader brokenConsoleCommandLoader() {
        return new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!"basemod.devcommands.ConsoleCommand".equals(name)) return super.findClass(name);
                byte[] bytes = consoleCommandClassBytes(null, true);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    private static ClassLoader consoleClassLoader(final String rootCommand) {
        return new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes;
                if ("basemod.DevConsole".equals(name)) {
                    bytes = emptyClassBytes("basemod/DevConsole");
                } else if ("basemod.devcommands.ConsoleCommand".equals(name)) {
                    bytes = consoleCommandClassBytes(rootCommand);
                } else {
                    return super.findClass(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    private static byte[] devConsoleClassBytes() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "basemod/DevConsole", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "execute",
            "(Ljava/lang/String;)Ljava/lang/String;",
            null,
            null
        );
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] releasedDevConsoleClassBytes() {
        return releasedDevConsoleClassBytes(false);
    }

    private static byte[] releasedDevConsoleClassBytes(boolean failInitialization) {
        String owner = "basemod/DevConsole";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "currentText", "Ljava/lang/String;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "priorCommands", "Ljava/lang/Object;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "log", "Ljava/lang/Object;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "prompted", "Ljava/lang/Object;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "executions", "I", null, null).visitEnd();

        MethodVisitor initializer = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        initializer.visitCode();
        if (failInitialization) {
            initializer.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
            initializer.visitInsn(Opcodes.DUP);
            initializer.visitLdcInsn("broken console initialization");
            initializer.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
                "(Ljava/lang/String;)V", false);
            initializer.visitInsn(Opcodes.ATHROW);
        } else {
            initializer.visitLdcInsn("initial");
            initializer.visitFieldInsn(Opcodes.PUTSTATIC, owner, "currentText", "Ljava/lang/String;");
            initializer.visitInsn(Opcodes.RETURN);
        }
        initializer.visitMaxs(3, 0);
        initializer.visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        constructor.visitInsn(Opcodes.DUP);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        constructor.visitFieldInsn(Opcodes.PUTSTATIC, owner, "priorCommands", "Ljava/lang/Object;");
        constructor.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        constructor.visitInsn(Opcodes.DUP);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        constructor.visitFieldInsn(Opcodes.PUTSTATIC, owner, "log", "Ljava/lang/Object;");
        constructor.visitInsn(Opcodes.ICONST_1);
        constructor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        constructor.visitFieldInsn(Opcodes.PUTSTATIC, owner, "prompted", "Ljava/lang/Object;");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(2, 1);
        constructor.visitEnd();

        MethodVisitor execute = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "execute", "()V", null, null);
        execute.visitCode();
        execute.visitFieldInsn(Opcodes.GETSTATIC, owner, "executions", "I");
        execute.visitInsn(Opcodes.ICONST_1);
        execute.visitInsn(Opcodes.IADD);
        execute.visitFieldInsn(Opcodes.PUTSTATIC, owner, "executions", "I");
        execute.visitFieldInsn(Opcodes.GETSTATIC, owner, "priorCommands", "Ljava/lang/Object;");
        execute.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
        execute.visitInsn(Opcodes.POP);
        execute.visitFieldInsn(Opcodes.GETSTATIC, owner, "log", "Ljava/lang/Object;");
        execute.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
        execute.visitInsn(Opcodes.POP);
        execute.visitFieldInsn(Opcodes.GETSTATIC, owner, "prompted", "Ljava/lang/Object;");
        execute.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
        execute.visitInsn(Opcodes.POP);
        execute.visitInsn(Opcodes.RETURN);
        execute.visitMaxs(2, 0);
        execute.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] releasedDevConsoleWithArgExecuteClassBytes() {
        String owner = "basemod/DevConsole";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "currentText", "Ljava/lang/String;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "priorCommands", "Ljava/lang/Object;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "log", "Ljava/lang/Object;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "prompted", "Ljava/lang/Object;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "executions", "I", null, null).visitEnd();

        MethodVisitor initializer = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        initializer.visitCode();
        initializer.visitLdcInsn("initial");
        initializer.visitFieldInsn(Opcodes.PUTSTATIC, owner, "currentText", "Ljava/lang/String;");
        initializer.visitInsn(Opcodes.RETURN);
        initializer.visitMaxs(1, 0);
        initializer.visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        constructor.visitInsn(Opcodes.DUP);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        constructor.visitFieldInsn(Opcodes.PUTSTATIC, owner, "priorCommands", "Ljava/lang/Object;");
        constructor.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        constructor.visitInsn(Opcodes.DUP);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        constructor.visitFieldInsn(Opcodes.PUTSTATIC, owner, "log", "Ljava/lang/Object;");
        constructor.visitInsn(Opcodes.ICONST_1);
        constructor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        constructor.visitFieldInsn(Opcodes.PUTSTATIC, owner, "prompted", "Ljava/lang/Object;");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(2, 1);
        constructor.visitEnd();

        // execute(String) records that it ran but never touches the constructor-published
        // markers, modelling a released console that would silently run before construction.
        MethodVisitor argExecute = writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "execute",
            "(Ljava/lang/String;)Ljava/lang/String;",
            null,
            null);
        argExecute.visitCode();
        argExecute.visitFieldInsn(Opcodes.GETSTATIC, owner, "executions", "I");
        argExecute.visitInsn(Opcodes.ICONST_1);
        argExecute.visitInsn(Opcodes.IADD);
        argExecute.visitFieldInsn(Opcodes.PUTSTATIC, owner, "executions", "I");
        argExecute.visitLdcInsn("ok");
        argExecute.visitInsn(Opcodes.ARETURN);
        argExecute.visitMaxs(2, 1);
        argExecute.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] noArgDevConsoleClassBytes() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "basemod/DevConsole", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "currentText", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor execute = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "execute", "()V", null, null);
        execute.visitCode();
        execute.visitInsn(Opcodes.RETURN);
        execute.visitMaxs(0, 0);
        execute.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClassBytes(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] consoleCommandClassBytes(String rootCommand) {
        return consoleCommandClassBytes(rootCommand, false);
    }

    private static byte[] consoleCommandClassBytes(String rootCommand, boolean failInitialization) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "basemod/devcommands/ConsoleCommand",
            null,
            "java/lang/Object",
            null
        );
        writer.visitField(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
            "root",
            "Ljava/util/Map;",
            null,
            null
        ).visitEnd();
        MethodVisitor initializer = writer.visitMethod(
            Opcodes.ACC_STATIC,
            "<clinit>",
            "()V",
            null,
            null
        );
        initializer.visitCode();
        if (failInitialization) {
            // Modelled after a duplicate copy whose <clinit> failed: the first reflective
            // read raises ExceptionInInitializerError, later reads NoClassDefFoundError.
            initializer.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
            initializer.visitInsn(Opcodes.DUP);
            initializer.visitLdcInsn("broken console command initialization");
            initializer.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
                "(Ljava/lang/String;)V", false);
            initializer.visitInsn(Opcodes.ATHROW);
        } else {
            initializer.visitTypeInsn(Opcodes.NEW, "java/util/HashMap");
            initializer.visitInsn(Opcodes.DUP);
            initializer.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
            initializer.visitFieldInsn(
                Opcodes.PUTSTATIC,
                "basemod/devcommands/ConsoleCommand",
                "root",
                "Ljava/util/Map;"
            );
            if (rootCommand != null) {
                initializer.visitFieldInsn(
                    Opcodes.GETSTATIC,
                    "basemod/devcommands/ConsoleCommand",
                    "root",
                    "Ljava/util/Map;"
                );
                initializer.visitLdcInsn(rootCommand);
                initializer.visitInsn(Opcodes.ACONST_NULL);
                initializer.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/Map",
                    "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    true
                );
                initializer.visitInsn(Opcodes.POP);
            }
            initializer.visitInsn(Opcodes.RETURN);
        }
        initializer.visitMaxs(3, 0);
        initializer.visitEnd();
        MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "execute",
            "([Ljava/lang/String;)V",
            null,
            null
        );
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static class FakeInstrumentation implements Instrumentation {
        private final Class<?>[] loadedClasses;

        FakeInstrumentation(Class<?>... loadedClasses) {
            this.loadedClasses = loadedClasses;
        }

        @Override public void addTransformer(ClassFileTransformer transformer, boolean canRetransform) {}
        @Override public void addTransformer(ClassFileTransformer transformer) {}
        @Override public boolean removeTransformer(ClassFileTransformer transformer) { return true; }
        @Override public boolean isRetransformClassesSupported() { return false; }
        @Override public void retransformClasses(Class<?>... classes) {}
        @Override public boolean isRedefineClassesSupported() { return false; }
        @Override public void redefineClasses(ClassDefinition... definitions) {}
        @Override public boolean isModifiableClass(Class<?> theClass) { return true; }
        @Override public Class<?>[] getAllLoadedClasses() { return loadedClasses; }
        @Override public Class<?>[] getInitiatedClasses(ClassLoader loader) { return new Class<?>[0]; }
        @Override public long getObjectSize(Object objectToSize) { return 0; }
        @Override public void appendToBootstrapClassLoaderSearch(JarFile jarfile) {}
        @Override public void appendToSystemClassLoaderSearch(JarFile jarfile) {}
        @Override public boolean isNativeMethodPrefixSupported() { return false; }
        @Override public void setNativeMethodPrefix(ClassFileTransformer transformer, String prefix) {}
    }

    @Test
    public void multipleAgentsSimultaneously() throws Exception {
        BufferedReader serverReader = startSession();

        writer.println("ATTACH mock {}");
        String id1 = serverReader.readLine().substring(3);
        writer.println("ATTACH tracing {}");
        String id2 = serverReader.readLine().substring(3);
        writer.println("ATTACH mock {}");
        String id3 = serverReader.readLine().substring(3);

        writer.println("LIST");
        String list = serverReader.readLine();
        assertTrue(list.contains(id1));
        assertTrue(list.contains(id2));
        assertTrue(list.contains(id3));
        assertNotEquals(id1, id2);
        assertNotEquals(id1, id3);
    }

    private static class MockMonitor implements Monitor {
        private final String status;
        private final AgentDataChannel channel;
        private volatile boolean attached = true;
        MockMonitor(String status, AgentDataChannel channel) {
            this.status = status;
            this.channel = channel;
        }
        @Override public void attach(Instrumentation inst, String agentArgs, AgentDataChannel channel) {}
        @Override public void detach() { attached = false; }
        @Override public String status() { return attached ? status : "detached"; }
        @Override public Set<MonitorCapability> capabilities() { return EnumSet.of(MonitorCapability.TRACING); }
    }
}
