package io.stamethyst.deltabench;

import java.util.Arrays;
import java.util.Random;

/** Deterministic synthetic fixtures, not real version pairs. */
public final class Corpus {
    public static final long SEED = 0x5a17d31L;
    public static final String[] CASES = {"identical", "insert-head", "insert-middle", "sparse", "unrelated"};
    public static final int MAX_SIZE = 16 * 1024 * 1024;

    public static byte[] base(int size) {
        if (size < 1024 || size > MAX_SIZE) throw new IllegalArgumentException("Size outside 1 KiB..16 MiB");
        byte[] result = new byte[size];
        new Random(SEED).nextBytes(result);
        return result;
    }

    public static byte[] target(byte[] base, String name) {
        if (name.equals("delete-middle")) {
            int at = base.length / 2;
            byte[] result = new byte[base.length - 37];
            System.arraycopy(base, 0, result, 0, at);
            System.arraycopy(base, at + 37, result, at, base.length - at - 37);
            return result;
        }
        if (name.equals("move-quarter")) {
            int at = base.length / 4;
            byte[] result = new byte[base.length];
            System.arraycopy(base, at, result, 0, base.length - at);
            System.arraycopy(base, 0, result, base.length - at, at);
            return result;
        }
        if (name.equals("identical")) return base.clone();
        if (name.equals("unrelated")) {
            byte[] result = new byte[base.length];
            new Random(SEED + 1).nextBytes(result);
            return result;
        }
        if (name.equals("sparse")) {
            byte[] result = base.clone();
            for (int i = 0; i < result.length; i += 4096) result[i] ^= 0x55;
            return result;
        }
        if (name.equals("insert-head") || name.equals("insert-middle")) {
            int at = name.equals("insert-head") ? 0 : base.length / 2;
            byte[] result = new byte[base.length + 37];
            System.arraycopy(base, 0, result, 0, at);
            Arrays.fill(result, at, at + 37, (byte) 0x7b);
            System.arraycopy(base, at, result, at + 37, base.length - at);
            return result;
        }
        throw new IllegalArgumentException("Unknown fixture: " + name);
    }

    private Corpus() {}
}
