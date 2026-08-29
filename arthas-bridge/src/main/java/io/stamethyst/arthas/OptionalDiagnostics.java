package io.stamethyst.arthas;

import java.lang.instrument.Instrumentation;

/** Runs optional native diagnostics outside the bridge handshake path. */
public final class OptionalDiagnostics implements Runnable {
    private final Instrumentation instrumentation;

    public OptionalDiagnostics(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    @Override
    public void run() {
        try {
            ArthasCommandBridge.runOptionalDiagnostics(instrumentation);
        } catch (Throwable e) {
            ArthasCommandBridge.log("optional diagnostics failed: " + e);
        }
    }
}
