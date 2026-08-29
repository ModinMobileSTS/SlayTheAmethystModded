package io.stamethyst.arthas;

import java.lang.instrument.Instrumentation;

/** ByteKit patch task resolved before the agent JAR enters the system classpath. */
public final class ByteKitPatchTask implements Runnable {
    private final Instrumentation instrumentation;

    public ByteKitPatchTask(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    @Override
    public void run() {
        ArthasCommandBridge.log("starting ByteKit retransformation");
        ArthasCommandBridge.retransformByteKitClasses(instrumentation);
        ArthasCommandBridge.log("finished ByteKit retransformation");
    }
}
