package io.stamethyst.probe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.Properties;
import java.util.jar.JarFile;

final class OfflineArthasController {
    static final int DEFAULT_PORT = 8099;
    static final int DEFAULT_DELAY_SECONDS = 15;
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int BRIDGE_READY_TIMEOUT_MS = 20_000;
    private static final int TARGET_CLASS_TIMEOUT_MS = 120_000;
    private static final int MAX_OUTPUT_BYTES = 16 * 1024 * 1024;
    private static final String FLUSH_CLASS = "com.badlogic.gdx.graphics.g2d.SpriteBatch";
    private static final String CARD_CLASS = "com.megacrit.cardcrawl.cards.AbstractCard";

    private final Instrumentation instrumentation;
    private final Config config;
    private final File componentDir;
    private final File outputDir;

    static final class Config {
        final boolean enabled;
        final int port;
        final int delaySeconds;
        final String outputDir;
        final String homeDir;

        Config(boolean enabled, int port, int delaySeconds, String outputDir, String homeDir) {
            this.enabled = enabled;
            this.port = port;
            this.delaySeconds = delaySeconds;
            this.outputDir = outputDir;
            this.homeDir = homeDir;
        }

        static Config from(Properties properties) {
            boolean enabled = Boolean.parseBoolean(properties.getProperty("arthas", "false"));
            int port = parseBoundedInt(properties.getProperty("arthasPort"), DEFAULT_PORT, 1, 65535);
            int delay = parseBoundedInt(
                properties.getProperty("arthasDelaySeconds"), DEFAULT_DELAY_SECONDS, 0, 300);
            return new Config(
                enabled,
                port,
                delay,
                properties.getProperty("arthasOutputDir", "").trim(),
                properties.getProperty("arthasHome", "").trim());
        }

        private static int parseBoundedInt(String value, int fallback, int min, int max) {
            try {
                int parsed = Integer.parseInt(value == null ? "" : value.trim());
                return parsed >= min && parsed <= max ? parsed : fallback;
            } catch (Throwable ignored) {
                return fallback;
            }
        }
    }

    static void schedule(Properties properties, Instrumentation instrumentation) {
        Config config = Config.from(properties);
        if (!config.enabled) return;
        File componentDir = config.homeDir.isEmpty() ? resolveComponentDir() : new File(config.homeDir);
        File outputDir = config.outputDir.isEmpty()
            ? new File(System.getProperty("user.dir", "."), "performance/arthas")
            : new File(config.outputDir);
        OfflineArthasController controller =
            new OfflineArthasController(instrumentation, config, componentDir, outputDir);
        Thread thread = new Thread(controller::run, "STS-Offline-Arthas");
        thread.setDaemon(true);
        thread.start();
    }

    OfflineArthasController(
        Instrumentation instrumentation,
        Config config,
        File componentDir,
        File outputDir
    ) {
        this.instrumentation = instrumentation;
        this.config = config;
        this.componentDir = componentDir;
        this.outputDir = outputDir;
    }

    private void run() {
        try {
            ensureOutputDir();
            resetOutputFiles();
            writeStatus("state=scheduled delaySeconds=" + config.delaySeconds);
            if (config.delaySeconds > 0) {
                Thread.sleep(config.delaySeconds * 1_000L);
            }
            loadArthas();
            waitForBridge();
            writeStatus("state=arthas_ready port=" + config.port);
            // agentmain returns before the bridge's ByteKit retransformation worker finishes.
            Thread.sleep(3_000L);
            if (!waitForTargetClasses()) {
                throw new IOException("target classes did not load before timeout");
            }
            writeStatus("state=sampling_started stackLimit=150 traceLimit=50 nativeDiagnostics=false");
            runSamplers();
            writeStatus("state=completed nativeDiagnostics=false");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            writeStatusQuietly("state=cancelled error=interrupted");
        } catch (Throwable error) {
            writeStatusQuietly(
                "state=failed error=" + error.getClass().getName() + ": " + safeMessage(error));
            System.err.println("[game-probe] offline Arthas failed: " + safeMessage(error));
        }
    }

    private void loadArthas() throws Exception {
        if (instrumentation == null) {
            throw new IOException("Instrumentation is unavailable");
        }
        File core = requiredFile("arthas-core.jar");
        requiredFile("arthas-spy.jar");
        File bridge = requiredFile("arthas-bridge.jar");
        instrumentation.appendToSystemClassLoaderSearch(new JarFile(core));
        instrumentation.appendToSystemClassLoaderSearch(new JarFile(bridge));
        Class<?> bridgeClass = Class.forName(
            "io.stamethyst.arthas.ArthasCommandBridge", true, ClassLoader.getSystemClassLoader());
        Method agentMain = bridgeClass.getMethod(
            "agentmain", String.class, Instrumentation.class);
        agentMain.invoke(null, core.getAbsolutePath() + ";port=" + config.port, instrumentation);
    }

    private File requiredFile(String name) throws IOException {
        File file = new File(componentDir, name);
        if (!file.isFile() || file.length() <= 0L) {
            throw new IOException("missing Arthas component: " + file.getAbsolutePath());
        }
        return file;
    }

    private void waitForBridge() throws Exception {
        long deadline = System.nanoTime() + BRIDGE_READY_TIMEOUT_MS * 1_000_000L;
        IOException lastError = null;
        while (System.nanoTime() < deadline) {
            try (RawShell shell = RawShell.connect(config.port)) {
                return;
            } catch (IOException error) {
                lastError = error;
                Thread.sleep(250L);
            }
        }
        throw new IOException("Arthas bridge did not become ready", lastError);
    }

    private boolean waitForTargetClasses() throws InterruptedException {
        long deadline = System.nanoTime() + TARGET_CLASS_TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            boolean flushLoaded = false;
            boolean cardLoaded = false;
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                String name = loaded.getName();
                if (FLUSH_CLASS.equals(name)) flushLoaded = true;
                if (CARD_CLASS.equals(name)) cardLoaded = true;
                if (flushLoaded && cardLoaded) return true;
            }
            Thread.sleep(500L);
        }
        return false;
    }

    private void runSamplers() throws InterruptedException {
        try (RawShell shell = RawShell.connect(config.port)) {
            shell.run("options disable-sub-class true", null, 10_000L);
        } catch (IOException error) {
            writeStatusQuietly("state=sampler_setup_failed error=" + safeMessage(error));
            return;
        }
        Thread stack = samplerThread(
            "STS-Arthas-Stack",
            "stack " + FLUSH_CLASS + " flush -n 150",
            new File(outputDir, "arthas-stack-flush.txt"),
            60_000L);
        Thread trace = samplerThread(
            "STS-Arthas-Trace",
            "trace " + CARD_CLASS + " render -n 50 '#cost > 5'",
            new File(outputDir, "arthas-trace-render.txt"),
            30_000L);
        stack.start();
        // Arthas 3.6.9 mutates shared CLI metadata during parse; stagger parse calls.
        Thread.sleep(2_000L);
        trace.start();
        stack.join(75_000L);
        trace.join(45_000L);
        if (stack.isAlive()) stack.interrupt();
        if (trace.isAlive()) trace.interrupt();
    }

    private Thread samplerThread(String name, String command, File output, long durationMs) {
        Thread thread = new Thread(() -> {
            try (RawShell shell = RawShell.connect(config.port)) {
                shell.run(command, output, durationMs);
            } catch (Throwable error) {
                writeStatusQuietly(
                    "state=sampler_failed command=" + command.split(" ")[0]
                        + " error=" + error.getClass().getName() + ": " + safeMessage(error));
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private void ensureOutputDir() throws IOException {
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("failed to create output directory: " + outputDir.getAbsolutePath());
        }
    }

    private void resetOutputFiles() {
        String[] names = {
            "arthas-offline-status.txt",
            "arthas-stack-flush.txt",
            "arthas-trace-render.txt"
        };
        for (String name : names) {
            File file = new File(outputDir, name);
            if (file.exists() && !file.delete()) {
                writeStatusQuietly("state=warning error=failed_to_clear_" + name);
            }
        }
    }

    private synchronized void writeStatus(String line) throws IOException {
        ensureOutputDir();
        try (FileOutputStream output = new FileOutputStream(
            new File(outputDir, "arthas-offline-status.txt"), true)) {
            output.write((System.currentTimeMillis() + " " + line + "\n")
                .getBytes(StandardCharsets.UTF_8));
        }
    }

    private void writeStatusQuietly(String line) {
        try {
            writeStatus(line);
        } catch (Throwable ignored) {}
    }

    private static File resolveComponentDir() {
        try {
            CodeSource source = GameProbe.class.getProtectionDomain().getCodeSource();
            File location = new File(source.getLocation().toURI());
            return location.isDirectory() ? location : location.getParentFile();
        } catch (Throwable ignored) {
            return new File(".").getAbsoluteFile();
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message.replace('\n', ' ');
    }

    static final class RawShell implements AutoCloseable {
        private final Socket socket;
        private final InputStream input;
        private final OutputStream output;
        private final byte[] prompt;

        static RawShell connect(int port) throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(250);
            return new RawShell(socket);
        }

        private RawShell(Socket socket) throws IOException {
            this.socket = socket;
            this.input = socket.getInputStream();
            this.output = socket.getOutputStream();
            this.prompt = readInitialPrompt();
        }

        void run(String command, File outputFile, long durationMs) throws IOException {
            if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0 || command.length() > 4096) {
                throw new IOException("invalid Arthas command");
            }
            OutputStream raw = outputFile == null ? null : new FileOutputStream(outputFile, false);
            try {
                output.write(command.getBytes(StandardCharsets.UTF_8));
                output.write('\n');
                output.flush();
                boolean completed = readUntilPrompt(raw, durationMs, MAX_OUTPUT_BYTES);
                if (!completed) {
                    output.write(3);
                    output.flush();
                    if (!readUntilPrompt(raw, 5_000L, MAX_OUTPUT_BYTES)) {
                        throw new IOException("Arthas command did not return to prompt: " + command);
                    }
                }
            } finally {
                if (raw != null) raw.close();
            }
        }

        private byte[] readInitialPrompt() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline && bytes.size() < 64 * 1024) {
                try {
                    int value = input.read();
                    if (value < 0) throw new IOException("Arthas shell closed before prompt");
                    bytes.write(value);
                    byte[] prompt = findPrompt(bytes.toByteArray());
                    if (prompt != null) return prompt;
                } catch (SocketTimeoutException ignored) {}
            }
            throw new IOException("Arthas initial prompt timed out");
        }

        private boolean readUntilPrompt(OutputStream raw, long timeoutMs, int maxBytes) throws IOException {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            byte[] tail = new byte[prompt.length];
            int tailSize = 0;
            int written = 0;
            byte[] buffer = new byte[8192];
            while (System.nanoTime() < deadline) {
                try {
                    int read = input.read(buffer);
                    if (read < 0) throw new IOException("Arthas shell closed during command");
                    if (raw != null && written < maxBytes) {
                        int allowed = Math.min(read, maxBytes - written);
                        raw.write(buffer, 0, allowed);
                        written += allowed;
                    }
                    for (int i = 0; i < read; i++) {
                        if (tailSize < tail.length) {
                            tail[tailSize++] = buffer[i];
                        } else {
                            System.arraycopy(tail, 1, tail, 0, tail.length - 1);
                            tail[tail.length - 1] = buffer[i];
                        }
                        if (tailSize == tail.length && matches(tail, prompt)) return true;
                    }
                } catch (SocketTimeoutException ignored) {}
            }
            return false;
        }

        static byte[] findPrompt(byte[] bytes) {
            byte[] prefix = "[arthas@".getBytes(StandardCharsets.US_ASCII);
            for (int start = Math.max(0, bytes.length - 128); start <= bytes.length - prefix.length; start++) {
                boolean prefixMatches = true;
                for (int i = 0; i < prefix.length; i++) {
                    if (bytes[start + i] != prefix[i]) {
                        prefixMatches = false;
                        break;
                    }
                }
                if (!prefixMatches) continue;
                for (int end = start + prefix.length; end + 2 < bytes.length; end++) {
                    if (bytes[end] == ']' && bytes[end + 1] == '$' && bytes[end + 2] == ' ') {
                        byte[] prompt = new byte[end + 3 - start];
                        System.arraycopy(bytes, start, prompt, 0, prompt.length);
                        return prompt;
                    }
                }
            }
            return null;
        }

        private static boolean matches(byte[] first, byte[] second) {
            if (first.length != second.length) return false;
            for (int i = 0; i < first.length; i++) {
                if (first[i] != second[i]) return false;
            }
            return true;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
