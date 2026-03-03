package io.stamethyst.bridge;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BootBridgeLauncher {
    private static final String PROP_EVENTS = "amethyst.bridge.events";
    private static final String PROP_DELEGATE = "amethyst.bridge.delegate";
    private static final String PROP_FORCE_CRASH = "amethyst.debug.force_jvm_crash";
    private static final String DEFAULT_DELEGATE = "com.evacipated.cardcrawl.modthespire.Loader";
    private static final Object EVENT_LOCK = new Object();
    private static final AtomicBoolean READY_SENT = new AtomicBoolean(false);
    private static final AtomicBoolean FAIL_SENT = new AtomicBoolean(false);
    private static final AtomicBoolean CONSOLE_READY_HINT = new AtomicBoolean(false);
    private static final long WATCHER_POLL_MS = 120L;
    private static final int READY_CONFIRM_TICKS = 3;
    private static final int CONSOLE_FALLBACK_FAIL_TICKS = 90;

    private static File eventsFile;
    private static int lastProgress = 0;

    private BootBridgeLauncher() {
    }

    public static void main(String[] args) throws Throwable {
        initEventsFile();
        installConsoleBridge();
        emitPhase(26, "Boot bridge started");
        installUncaughtExceptionBridge();
        tryInstallMtsProgressBridge();
        startMainMenuWatcher();
        triggerForcedCrashIfRequested();

        String delegateClass = System.getProperty(PROP_DELEGATE, DEFAULT_DELEGATE);
        emitPhase(29, "Starting " + delegateClass);
        invokeDelegate(delegateClass, args);
    }

    private static void triggerForcedCrashIfRequested() {
        boolean forceCrash = Boolean.parseBoolean(System.getProperty(PROP_FORCE_CRASH, "false"));
        if (!forceCrash) {
            return;
        }
        RuntimeException crash = new RuntimeException("Forced JVM crash for diagnostics verification");
        emitFail("Forced crash requested via " + PROP_FORCE_CRASH);
        throw crash;
    }

    private static void initEventsFile() {
        String path = System.getProperty(PROP_EVENTS, "").trim();
        if (path.isEmpty()) {
            return;
        }
        eventsFile = new File(path);
        File parent = eventsFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream ignored = new FileOutputStream(eventsFile, false)) {
            // Truncate existing events.
        } catch (Throwable ignored) {
        }
    }

    private static void installUncaughtExceptionBridge() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            emitFail("Uncaught exception on " + thread.getName() + ": " + summarizeThrowable(throwable));
            try {
                if (throwable != null) {
                    throwable.printStackTrace(System.err);
                }
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private static void tryInstallMtsProgressBridge() {
        try {
            Class<?> loaderClass = Class.forName("com.evacipated.cardcrawl.modthespire.Loader");
            Class<?> listenerClass = Class.forName(
                    "com.evacipated.cardcrawl.modthespire.ex.ProgressPublish$Listener"
            );
            Field progressField = loaderClass.getDeclaredField("progress");
            progressField.setAccessible(true);
            Object progressPublisher = progressField.get(null);
            Method setListener = progressPublisher.getClass().getMethod("setListener", listenerClass);

            InvocationHandler handler = (proxy, method, args) -> {
                if ("onStep".equals(method.getName()) && args != null && args.length >= 2) {
                    int mtsPercent = readPercent(args[1]);
                    String stepMessage = readStepMessage(args[0]);
                    int mappedPercent = mapMtsProgress(mtsPercent);
                    String message = stepMessage.isEmpty()
                            ? "MTS phase " + mtsPercent + "%"
                            : "MTS: " + stepMessage;
                    emitPhase(mappedPercent, message);
                }
                return null;
            };

            Object listenerProxy = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    handler
            );
            setListener.invoke(progressPublisher, listenerProxy);
            emitPhase(32, "Attached MTS progress bridge");
        } catch (Throwable error) {
            emitPhase(32, "MTS progress bridge unavailable: " + error.getClass().getSimpleName());
        }
    }

    private static void installConsoleBridge() {
        try {
            PrintStream out = System.out;
            if (out != null) {
                System.setOut(new PrintStream(new BridgeLineOutputStream(out, false), true, "UTF-8"));
            }
        } catch (Throwable ignored) {
        }
        try {
            PrintStream err = System.err;
            if (err != null) {
                System.setErr(new PrintStream(new BridgeLineOutputStream(err, true), true, "UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void onConsoleLine(String rawLine, boolean isErrorStream) {
        String line = sanitizeMessage(rawLine);
        if (line.isEmpty()) {
            return;
        }

        if (isErrorStream && isKnownFatalConsoleLine(line)) {
            emitFail("MTS: " + line);
            return;
        }

        int mappedPercent = mapConsolePhase(line);
        if (mappedPercent >= 0) {
            emitPhase(mappedPercent, line);
        }
        if (isReadyConsoleLine(line)) {
            CONSOLE_READY_HINT.set(true);
            emitPhase(97, "Console ready hint");
        }
    }

    private static boolean isKnownFatalConsoleLine(String line) {
        if (line.startsWith("ERROR:")) {
            return true;
        }
        return false;
    }

    private static int mapConsolePhase(String line) {
        String value = line.trim();
        if (value.startsWith("Searching for workshop items")) {
            return 30;
        }
        if (value.startsWith("Begin patching")) {
            return 36;
        }
        if (value.startsWith("Patching enums")) {
            return 44;
        }
        if (value.startsWith("Finding core patches")) {
            return 52;
        }
        if (value.startsWith("Finding patches")) {
            return 60;
        }
        if (value.startsWith("Busting enums")) {
            return 72;
        }
        if (value.startsWith("Setting isModded = true")) {
            return 80;
        }
        if (value.startsWith("Adding ModTheSpire to version")) {
            return 84;
        }
        if (value.startsWith("Initializing mods")) {
            return 90;
        }
        if (value.startsWith("Starting game")) {
            return 93;
        }
        if (value.contains("DesktopLauncher> Launching application")) {
            return 95;
        }
        if (value.contains("CardCrawlGame> No migration")) {
            return 96;
        }
        return -1;
    }

    private static boolean isReadyConsoleLine(String line) {
        String lower = line.toLowerCase();
        if (lower.contains("basemod.basemod> publishaddcustommodemods")) {
            return true;
        }
        if (lower.contains("stats.statsscreen> loading character stats.")) {
            return true;
        }
        if (lower.contains("core.displayconfig> displayconfig successfully read.")) {
            return true;
        }
        return lower.contains("characters.charactermanager> successfully recreated");
    }

    private static void startMainMenuWatcher() {
        Thread watcher = new Thread(() -> {
            int readyTicks = 0;
            int reflectionFailureTicks = 0;
            while (!READY_SENT.get() && !FAIL_SENT.get()) {
                try {
                    GameSnapshot snapshot = readGameSnapshot();
                    reflectionFailureTicks = 0;
                    if (snapshot == null) {
                        readyTicks = 0;
                        sleepQuietly(WATCHER_POLL_MS);
                        continue;
                    }
                    if (isReadyGameState(snapshot)) {
                        readyTicks += 1;
                        if (readyTicks >= READY_CONFIRM_TICKS) {
                            emitReady("Game state ready: " + describeSnapshot(snapshot));
                            return;
                        }
                    } else {
                        readyTicks = 0;
                    }

                    if ("SPLASH".equals(snapshot.modeName)) {
                        emitPhase(94, "Game splash");
                    }
                    if ("CHAR_SELECT".equals(snapshot.modeName) && snapshot.hasMainMenuScreen) {
                        String screen = snapshot.menuScreenName.isEmpty()
                                ? "unknown"
                                : snapshot.menuScreenName;
                        emitPhase(97, "Main menu scene: " + screen);
                    }
                } catch (Throwable error) {
                    readyTicks = 0;
                    reflectionFailureTicks += 1;
                    if (CONSOLE_READY_HINT.get() && reflectionFailureTicks >= CONSOLE_FALLBACK_FAIL_TICKS) {
                        emitReady("Startup reached interactive phase (console fallback)");
                        return;
                    }
                }
                sleepQuietly(WATCHER_POLL_MS);
            }
        }, "Amethyst-BootBridge-MenuWatcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static GameSnapshot readGameSnapshot() throws Exception {
        Class<?> cardCrawlGameClass = loadCardCrawlGameClass();
        Field modeField = cardCrawlGameClass.getDeclaredField("mode");
        if (!Modifier.isStatic(modeField.getModifiers())) {
            return null;
        }
        modeField.setAccessible(true);
        Object mode = modeField.get(null);
        String modeName = readModeName(mode);

        Field mainMenuField = cardCrawlGameClass.getDeclaredField("mainMenuScreen");
        if (!Modifier.isStatic(mainMenuField.getModifiers())) {
            return new GameSnapshot(modeName, false, "");
        }
        mainMenuField.setAccessible(true);
        Object mainMenuScreen = mainMenuField.get(null);
        if (mainMenuScreen == null) {
            return new GameSnapshot(modeName, false, "");
        }

        String menuScreenName = "";
        try {
            Field screenField = mainMenuScreen.getClass().getDeclaredField("screen");
            screenField.setAccessible(true);
            Object screen = screenField.get(mainMenuScreen);
            if (screen instanceof Enum<?>) {
                menuScreenName = ((Enum<?>) screen).name();
            } else if (screen != null) {
                menuScreenName = String.valueOf(screen);
            }
        } catch (Throwable ignored) {
            // Menu screen introspection is best-effort only.
        }
        return new GameSnapshot(modeName, true, menuScreenName);
    }

    private static Class<?> loadCardCrawlGameClass() throws ClassNotFoundException {
        ClassLoader[] candidates = new ClassLoader[]{
                Thread.currentThread().getContextClassLoader(),
                BootBridgeLauncher.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        ClassNotFoundException last = null;
        for (ClassLoader loader : candidates) {
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName("com.megacrit.cardcrawl.core.CardCrawlGame", false, loader);
            } catch (ClassNotFoundException error) {
                last = error;
            }
        }
        if (last != null) {
            throw last;
        }
        return Class.forName("com.megacrit.cardcrawl.core.CardCrawlGame");
    }

    private static String readModeName(Object mode) {
        if (mode instanceof Enum<?>) {
            return ((Enum<?>) mode).name();
        }
        if (mode == null) {
            return "";
        }
        return String.valueOf(mode);
    }

    private static boolean isReadyGameState(GameSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        if ("GAMEPLAY".equals(snapshot.modeName) || "DUNGEON_TRANSITION".equals(snapshot.modeName)) {
            return true;
        }
        if (!"CHAR_SELECT".equals(snapshot.modeName)) {
            return false;
        }
        if (!snapshot.hasMainMenuScreen) {
            return false;
        }
        if (snapshot.menuScreenName == null || snapshot.menuScreenName.isEmpty()) {
            return false;
        }
        return !"NONE".equals(snapshot.menuScreenName);
    }

    private static String describeSnapshot(GameSnapshot snapshot) {
        if (snapshot == null) {
            return "unknown";
        }
        String mode = snapshot.modeName == null || snapshot.modeName.isEmpty()
                ? "unknown"
                : snapshot.modeName;
        String menu = snapshot.menuScreenName == null || snapshot.menuScreenName.isEmpty()
                ? "n/a"
                : snapshot.menuScreenName;
        return "mode=" + mode + ", mainMenu=" + snapshot.hasMainMenuScreen + ", screen=" + menu;
    }

    private static final class GameSnapshot {
        private final String modeName;
        private final boolean hasMainMenuScreen;
        private final String menuScreenName;

        private GameSnapshot(String modeName, boolean hasMainMenuScreen, String menuScreenName) {
            this.modeName = modeName;
            this.hasMainMenuScreen = hasMainMenuScreen;
            this.menuScreenName = menuScreenName;
        }
    }

    private static void invokeDelegate(String delegateClass, String[] args) throws Throwable {
        try {
            Class<?> delegate = Class.forName(delegateClass);
            Method mainMethod = delegate.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            emitFail("Delegate crashed: " + summarizeThrowable(cause));
            try {
                cause.printStackTrace(System.err);
            } catch (Throwable ignored) {
            }
            throw cause;
        } catch (Throwable error) {
            emitFail("Delegate start failed: " + summarizeThrowable(error));
            try {
                error.printStackTrace(System.err);
            } catch (Throwable ignored) {
            }
            throw error;
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
            return sanitizeMessage(value == null ? "" : String.valueOf(value));
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int mapMtsProgress(int mtsPercent) {
        int bounded = clamp(mtsPercent, 0, 100);
        return 33 + Math.round(bounded * 0.62f);
    }

    private static void emitPhase(int progress, String message) {
        if (FAIL_SENT.get()) {
            return;
        }
        int bounded = clamp(progress, 0, 100);
        if (bounded < lastProgress) {
            bounded = lastProgress;
        }
        lastProgress = bounded;
        emit("PHASE", bounded, message);
    }

    private static void emitReady(String message) {
        if (READY_SENT.getAndSet(true) || FAIL_SENT.get()) {
            return;
        }
        lastProgress = Math.max(lastProgress, 100);
        emit("READY", 100, message);
    }

    private static void emitFail(String message) {
        if (FAIL_SENT.getAndSet(true)) {
            return;
        }
        emit("FAIL", -1, message);
    }

    private static void emit(String type, int progress, String message) {
        if (eventsFile == null) {
            return;
        }
        String safeType = type == null ? "UNKNOWN" : type.trim();
        if (safeType.isEmpty()) {
            safeType = "UNKNOWN";
        }
        String safeMessage = sanitizeMessage(message);
        String line = safeType + "\t" + progress + "\t" + safeMessage + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        synchronized (EVENT_LOCK) {
            try (FileOutputStream output = new FileOutputStream(eventsFile, true)) {
                output.write(bytes);
                output.flush();
            } catch (IOException ignored) {
            }
        }
    }

    private static String sanitizeMessage(String message) {
        if (message == null) {
            return "";
        }
        String value = message.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .trim();
        return value.length() > 300 ? value.substring(0, 300) : value;
    }

    private static String summarizeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        StringBuilder out = new StringBuilder(128);
        out.append(throwable.getClass().getSimpleName());
        String message = throwable.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            out.append(": ").append(message.trim());
        }
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            out.append(" | cause=").append(cause.getClass().getSimpleName());
            String causeMessage = cause.getMessage();
            if (causeMessage != null && !causeMessage.trim().isEmpty()) {
                out.append(": ").append(causeMessage.trim());
            }
        }
        return sanitizeMessage(out.toString());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class BridgeLineOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final boolean isErrorStream;
        private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);

        private BridgeLineOutputStream(OutputStream delegate, boolean isErrorStream) {
            this.delegate = delegate;
            this.isErrorStream = isErrorStream;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            delegate.write(b);
            accept((byte) b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            int end = off + len;
            for (int i = off; i < end; i++) {
                accept(b[i]);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            flushBufferedLine();
            delegate.close();
        }

        private void accept(byte value) {
            if (value == '\n') {
                flushBufferedLine();
                return;
            }
            if (value != '\r') {
                lineBuffer.write(value);
            }
        }

        private void flushBufferedLine() {
            if (lineBuffer.size() <= 0) {
                return;
            }
            String line = new String(lineBuffer.toByteArray(), StandardCharsets.UTF_8);
            lineBuffer.reset();
            onConsoleLine(line, isErrorStream);
        }
    }
}
