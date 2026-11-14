package io.stamethyst.deltabench;

import java.io.IOException;

final class NativeBridge {
    static { System.loadLibrary("delta_benchmark"); }
    static native byte[] transform(int engine, boolean encode, byte[] base, byte[] input, int expected) throws IOException;
    static native long[] stream(boolean hd, boolean encode, String base, String input, String output,
        long expected, int window, long outputLimit, long allocLimit, long sourceWindow) throws IOException;
    private NativeBridge() {}
}
