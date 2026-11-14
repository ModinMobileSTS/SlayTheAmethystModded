package io.stamethyst.deltabench;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.UUID;

/** One engine per instrumentation invocation (fresh Android process). */
public final class BenchmarkInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String engine = arguments.getString("engine", Engines.NAMES[0]);
            Engines.validate(engine);
            int size = Integer.parseInt(arguments.getString("size", "262144"));
            int repeats = Integer.parseInt(arguments.getString("repeats", "3"));
            if (repeats < 1 || repeats > 10) throw new IllegalArgumentException("repeats outside 1..10");
            byte[] original = Corpus.base(size);
            File root = new File(getTargetContext().getFilesDir(), "runs/" + UUID.randomUUID());
            if (!root.mkdirs()) throw new IOException("Cannot create run directory");
            File base = new File(root, "base.bin");
            File target = new File(root, "target.bin");
            File patch = new File(root, "patch.bin");
            File output = new File(root, "candidate.bin");
            write(base, original);
            if (arguments.getString("safety", "false").equals("true")) {
                runSafety(engine, base, target, patch, output, original);
                result.putString("stream", "Delta safety PASS; engine=" + engine
                    + "; checks=" + (engine.equals(Engines.NAMES[0]) ? 2 : 3));
                finish(Activity.RESULT_OK, result);
                return;
            }
            File report = new File(root, "results.jsonl");
            try (FileOutputStream reportFile = new FileOutputStream(report)) {
                for (String fixture : Corpus.CASES) {
                    byte[] expected = Corpus.target(original, fixture);
                    write(target, expected);
                    String baseHash = sha(original), targetHash = sha(expected);
                    for (int iteration = -1; iteration < repeats; iteration++) {
                        // -1 is explicitly recorded warmup, never silently aggregated.
                        long start = SystemClock.elapsedRealtimeNanos();
                        Engines.generate(engine, base, target, patch);
                        long generated = SystemClock.elapsedRealtimeNanos();
                        Engines.apply(engine, base, patch, output, expected.length);
                        long applied = SystemClock.elapsedRealtimeNanos();
                        verify(output, expected);
                        verify(base, original);
                        Debug.MemoryInfo memory = new Debug.MemoryInfo();
                        Debug.getMemoryInfo(memory);
                        JSONObject row = new JSONObject();
                        row.put("schema", 1).put("engine", engine).put("fixture", fixture)
                            .put("seed", Corpus.SEED).put("iteration", iteration).put("warmup", iteration < 0)
                            .put("baseBytes", original.length).put("targetBytes", expected.length)
                            .put("baseSha256", baseHash).put("targetSha256", targetHash)
                            .put("patchBytes", patch.length()).put("generateNs", generated - start)
                            .put("applyAndSyncNs", applied - generated).put("verifyNs", SystemClock.elapsedRealtimeNanos() - applied)
                            .put("pssAfterKb", memory.getTotalPss()).put("vmHwmKb", highWaterKb())
                            .put("workBytesAfter", base.length() + target.length() + patch.length() + output.length())
                            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                            .put("sdk", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
                            .put("abi", Build.SUPPORTED_ABIS[0]).put("verified", true)
                            .put("harnessVersion", "0.2").put("nativeCompiler", "NDK 27.0.12077973 clang -O3")
                            .put("nativePins", nativePins());
                        reportFile.write((row + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        reportFile.getFD().sync();
                        Bundle progress = new Bundle();
                        progress.putString("row", row.toString());
                        sendStatus(0, progress);
                    }
                }
            }
            result.putString("report", report.getAbsolutePath());
            result.putString("stream", "Delta smoke PASS; report=" + report);
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private String nativePins() throws IOException {
        try (InputStream in = getTargetContext().getAssets().open("native/pins.json");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Engines.copy(in, out);
            return out.toString("UTF-8");
        }
    }

    private static void runSafety(String engine, File base, File target, File patch,
            File output, byte[] original) throws Exception {
        byte[] expected = Corpus.target(original, "insert-middle");
        write(target, expected);
        Engines.generate(engine, base, target, patch);
        byte[] valid;
        try (FileInputStream in = new FileInputStream(patch); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Engines.copy(in, out); valid = out.toByteArray();
        }
        boolean rejected = false;
        try { Engines.apply(engine, base, patch, output, 1); }
        catch (IOException error) { rejected = true; }
        if (!rejected) throw new AssertionError("Output-size mismatch accepted");
        write(patch, java.util.Arrays.copyOf(valid, valid.length / 2));
        rejected = false;
        try { Engines.apply(engine, base, patch, output, expected.length); }
        catch (IOException error) { rejected = true; }
        if (!rejected) throw new AssertionError("Truncated patch accepted");
        if (engine.equals(Engines.NAMES[0])) {
            verify(base, original); // Full replacement intentionally does not use base.
            return;
        }
        write(patch, valid);
        byte[] wrong = Corpus.target(original, "unrelated");
        write(base, wrong);
        // Raw codecs need not authenticate the base. The harness's byte oracle must
        // either detect wrong reconstruction, or the decoder itself must reject it.
        rejected = false;
        try { Engines.apply(engine, base, patch, output, expected.length); verify(output, expected); }
        catch (IOException error) { rejected = true; }
        if (!rejected) throw new AssertionError("Wrong base was not detected");
        verify(base, wrong);
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); out.getFD().sync(); }
    }
    private static String sha(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    private static void verify(File file, byte[] expected) throws IOException {
        if (file.length() != expected.length) throw new IOException("Length mismatch");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            for (byte value : expected) if (in.read() != (value & 255)) throw new IOException("Byte mismatch");
            if (in.read() != -1) throw new IOException("Trailing bytes");
        }
    }
    private static long highWaterKb() throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmHWM:")) return Long.parseLong(line.split("\\s+")[1]);
            }
        }
        return -1;
    }
}
