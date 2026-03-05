package io.stamethyst.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class BootBridgeConsoleBridge {
    private static final String[] READY_HINT_FRAGMENTS = new String[]{
            "basemod.basemod> publishaddcustommodemods",
            "stats.statsscreen> loading character stats.",
            "core.displayconfig> displayconfig successfully read.",
            "characters.charactermanager> successfully recreated"
    };

    private static final PhaseRule[] PHASE_RULES = new PhaseRule[]{
            PhaseRule.startsWith("Searching for workshop items", 30),
            PhaseRule.startsWith("Begin patching", 36),
            PhaseRule.startsWith("Patching enums", 44),
            PhaseRule.startsWith("Finding core patches", 52),
            PhaseRule.startsWith("Finding patches", 60),
            PhaseRule.startsWith("Busting enums", 72),
            PhaseRule.startsWith("Setting isModded = true", 80),
            PhaseRule.startsWith("Adding ModTheSpire to version", 84),
            PhaseRule.startsWith("Initializing mods", 90),
            PhaseRule.startsWith("Starting game", 93),
            PhaseRule.contains("DesktopLauncher> Launching application", 95),
            PhaseRule.contains("CardCrawlGame> No migration", 96)
    };

    private BootBridgeConsoleBridge() {
    }

    static void install(BootBridgeReporter reporter) {
        try {
            PrintStream out = System.out;
            if (out != null) {
                System.setOut(new PrintStream(new BridgeLineOutputStream(out, false, reporter), true, "UTF-8"));
            }
        } catch (Throwable ignored) {
        }
        try {
            PrintStream err = System.err;
            if (err != null) {
                System.setErr(new PrintStream(new BridgeLineOutputStream(err, true, reporter), true, "UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void onConsoleLine(String rawLine, boolean isErrorStream, BootBridgeReporter reporter) {
        String line = normalizeConsoleLine(rawLine);
        if (line.isEmpty()) {
            return;
        }

        if (isErrorStream && line.startsWith("ERROR:")) {
            reporter.fail("MTS: " + line);
            return;
        }

        int mappedPercent = mapConsolePhase(line);
        if (mappedPercent >= 0) {
            reporter.phase(mappedPercent, line);
        }
        if (isReadyConsoleLine(line)) {
            reporter.markConsoleReadyHint();
            reporter.phase(97, "Console ready hint");
        }
    }

    private static int mapConsolePhase(String line) {
        String value = line.trim();
        for (PhaseRule rule : PHASE_RULES) {
            if (rule.matches(value)) {
                return rule.progress;
            }
        }
        return -1;
    }

    private static boolean isReadyConsoleLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String fragment : READY_HINT_FRAGMENTS) {
            if (lower.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeConsoleLine(String line) {
        if (line == null) {
            return "";
        }
        return line.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .trim();
    }

    private static final class PhaseRule {
        private final String text;
        private final int progress;
        private final boolean contains;

        private PhaseRule(String text, int progress, boolean contains) {
            this.text = text;
            this.progress = progress;
            this.contains = contains;
        }

        private static PhaseRule startsWith(String text, int progress) {
            return new PhaseRule(text, progress, false);
        }

        private static PhaseRule contains(String text, int progress) {
            return new PhaseRule(text, progress, true);
        }

        private boolean matches(String line) {
            if (contains) {
                return line.contains(text);
            }
            return line.startsWith(text);
        }
    }

    private static final class BridgeLineOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final boolean isErrorStream;
        private final BootBridgeReporter reporter;
        private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);

        private BridgeLineOutputStream(OutputStream delegate, boolean isErrorStream, BootBridgeReporter reporter) {
            this.delegate = delegate;
            this.isErrorStream = isErrorStream;
            this.reporter = reporter;
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
            onConsoleLine(line, isErrorStream, reporter);
        }
    }
}
