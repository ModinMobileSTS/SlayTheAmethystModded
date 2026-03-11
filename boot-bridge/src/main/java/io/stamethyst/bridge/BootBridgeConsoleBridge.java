package io.stamethyst.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

final class BootBridgeConsoleBridge {

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
        String line = BootBridgePhaseMapper.normalize(rawLine);
        if (line.isEmpty()) {
            return;
        }

        if (isErrorStream && line.startsWith("ERROR:")) {
            reporter.fail(BootBridgePhaseMapper.encodeConsoleError(line));
            return;
        }

        BootBridgePhaseMapper.PhaseMatch match = BootBridgePhaseMapper.matchPhase(line);
        if (match != null) {
            reporter.phase(match.progress, match.message);
        }
        if (BootBridgePhaseMapper.isReadyConsoleLine(line)) {
            reporter.markConsoleReadyHint();
            reporter.phase(97, BootBridgeStartupMessage.key("main_menu_ready"));
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
