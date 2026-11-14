package io.stamethyst.deltabench;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Prepare, generate and apply each run in distinct instrumentation processes. */
public final class PhaseInstrumentation extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle args) { super.onCreate(args); this.args = args; start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String token = args.getString("token", "");
            if (!token.matches("[a-zA-Z0-9-]{1,80}")) throw new IOException("Invalid run token");
            String phase = args.getString("phase", ""), engine = args.getString("engine", "");
            Engines.validate(engine);
            File root = new File(getTargetContext().getFilesDir(), "phases/" + token);
            File base = new File(root, "base"), target = new File(root, "target");
            File patch = new File(root, "patch"), candidate = new File(root, "candidate");
            File metadata = new File(root, "metadata.json");
            if (phase.equals("prepare")) {
                if (!root.mkdirs()) throw new IOException("Run exists or cannot be created");
                String fixture = args.getString("fixture", "insert-middle");
                if (fixture.startsWith("real-")) {
                    if (!fixture.matches("real-[a-zA-Z0-9-]{1,70}")) throw new IOException("Invalid corpus fixture");
                    asset(fixture+"/base.bin", base); asset(fixture+"/target.bin", target);
                } else {
                    int size = Integer.parseInt(args.getString("size", "1048576"));
                    FileCorpus.prepare(base,target,size,fixture);
                }
                JSONObject info = new JSONObject().put("fixture", fixture).put("baseBytes",base.length())
                    .put("targetBytes",target.length()).put("baseSha256",hash(base)).put("targetSha256",hash(target));
                if (fixture.startsWith("real-")) info.put("source", assetText(fixture+"/source.json"));
                write(metadata, info.toString().getBytes("UTF-8"));
            } else if (phase.equals("limits")) {
                if (!Engines.streaming(engine)) throw new IOException("Limits mode needs stream engine");
                JSONObject info=new JSONObject(readText(metadata));
                String originalBase=hash(base),originalPatch=hash(patch);
                int checks=0;
                Engines.outputLimit=1;
                try {
                    boolean rejected=false;
                    try { Engines.apply(engine,base,patch,candidate,info.getLong("targetBytes")); }
                    catch(IOException expectedFailure){rejected=true;}
                    if(!rejected || candidate.length()>1) throw new IOException("Apply output limit not enforced");
                    checks++;
                    File rejectedPatch=new File(root,"rejected-patch");
                    rejected=false;
                    try { Engines.generate(engine,base,target,rejectedPatch); }
                    catch(IOException expectedFailure){rejected=true;}
                    if(!rejected || rejectedPatch.length()>1) throw new IOException("Generate output limit not enforced");
                    checks++;
                } finally { Engines.outputLimit=128L*1024*1024; }
                if(engine.contains("xdelta")) {
                    Engines.allocLimit=1;
                    try {
                        boolean rejected=false;
                        try { Engines.apply(engine,base,patch,candidate,info.getLong("targetBytes")); }
                        catch(IOException expectedFailure){rejected=true;}
                        if(!rejected) throw new IOException("xdelta allocator cap not enforced");
                        checks++;
                    } finally { Engines.allocLimit=64L*1024*1024; }
                }
                Engines.apply(engine,base,patch,candidate,info.getLong("targetBytes"));
                compare(target,candidate);
                if(!originalBase.equals(hash(base)) || !originalPatch.equals(hash(patch))) throw new IOException("Limit test modified input");
                result.putString("checks",Integer.toString(checks));
            } else if (phase.equals("generate") || phase.equals("apply")) {
                if (!metadata.isFile()) throw new IOException("Run not prepared");
                JSONObject row = new JSONObject(readText(metadata));
                if (!hash(base).equals(row.getString("baseSha256")) || !hash(target).equals(row.getString("targetSha256")))
                    throw new IOException("Input identity mismatch");
                // Loading JNI is excluded, as is framework/startup and input identity hashing.
                if (engine.startsWith("native-")) Class.forName("io.stamethyst.deltabench.NativeBridge");
                int temperatureBefore = temperature();
                int thermalBefore = thermal();
                long baselineRss = procKb("VmRSS:"), baselineHwm = procKb("VmHWM:");
                AtomicBoolean sampling = new AtomicBoolean(true);
                AtomicLong peak = new AtomicLong(baselineRss), samples = new AtomicLong();
                Thread sampler = new Thread(() -> {
                    while (sampling.get()) {
                        try {
                            peak.accumulateAndGet(procKb("VmRSS:"), Math::max); samples.incrementAndGet();
                            Thread.sleep(10);
                        } catch (InterruptedException stop) { return; }
                        catch (IOException unavailable) { return; }
                    }
                }, "rss-sampler");
                sampler.start();
                long start = SystemClock.elapsedRealtimeNanos();
                long elapsed;
                try {
                    if (phase.equals("generate")) Engines.generate(engine,base,target,patch);
                    else Engines.apply(engine,base,patch,candidate,row.getLong("targetBytes"));
                    elapsed = SystemClock.elapsedRealtimeNanos()-start;
                } finally { sampling.set(false); sampler.interrupt(); sampler.join(); }
                long endRss = procKb("VmRSS:"), endHwm = procKb("VmHWM:");
                peak.accumulateAndGet(endRss, Math::max);
                // Oracle is after all memory observations and engine timing.
                if (phase.equals("apply")) compare(target,candidate);
                if (!hash(base).equals(row.getString("baseSha256"))) throw new IOException("Base changed");
                row.put("schema",2).put("harnessVersion","0.5").put("phase",phase).put("engine",engine)
                    .put("token",token).put("elapsedNs",elapsed).put("patchBytes",patch.length())
                    .put("verified",phase.equals("apply")).put("success",true)
                    .put("baselineRssKb",baselineRss).put("sampledRssPeakKb",peak.get())
                    .put("rssSamples",samples.get()).put("baselineHwmKb",baselineHwm).put("endHwmKb",endHwm)
                    .put("batteryTempBeforeDeciC",temperatureBefore).put("batteryTempAfterDeciC",temperature())
                    .put("thermalBefore",thermalBefore).put("thermalAfter",thermal())
                    .put("fingerprint",Build.FINGERPRINT).put("model",Build.MODEL).put("abi",Build.SUPPORTED_ABIS[0])
                    .put("nativePins",assetText("native/pins.json"));
                if (Engines.streaming(engine)) {
                    row.put("codec",engine.endsWith("deflate6")?"raw-deflate6":"none").put("adapterReadBytes",Engines.streamStats[0])
                        .put("adapterWriteBytes",Engines.streamStats[1]).put("logicalOutputBytes",Engines.streamStats[2])
                        .put("xdeltaAllocatorPeakBytes",Engines.streamStats[3]).put("xdeltaAllocatorLimitBytes",Engines.allocLimit);
                }
                write(new File(root,phase+".json"),row.toString().getBytes("UTF-8"));
                result.putString("row",row.toString());
            } else throw new IOException("Unknown phase");
            result.putString("stream","Delta phase PASS; phase="+phase);
            finish(Activity.RESULT_OK,result);
        } catch (Exception | AssertionError error) {
            result.putString("stream",android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED,result);
        }
    }

    private int temperature() {
        Intent intent = getTargetContext().registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return intent == null ? -1 : intent.getIntExtra("temperature",-1);
    }
    private int thermal() {
        if (Build.VERSION.SDK_INT < 29) return -1;
        return ((PowerManager)getTargetContext().getSystemService("power")).getCurrentThermalStatus();
    }
    private void asset(String name,File output) throws IOException {
        try (InputStream in=getTargetContext().getAssets().open(name); FileOutputStream out=new FileOutputStream(output)) {
            Engines.copy(in,out); out.getFD().sync();
        }
    }
    private String assetText(String name) throws IOException {
        try (InputStream in=getTargetContext().getAssets().open(name); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Engines.copy(in,out); return out.toString("UTF-8");
        }
    }
    private static String readText(File file) throws IOException {
        try (InputStream in=new FileInputStream(file); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Engines.copy(in,out); return out.toString("UTF-8");
        }
    }
    private static void write(File file,byte[] data) throws IOException {
        try (FileOutputStream out=new FileOutputStream(file)) { out.write(data); out.getFD().sync(); }
    }
    private static String hash(File file) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try (InputStream in=new FileInputStream(file)) {
            byte[] buffer=new byte[65536]; int n;
            while ((n=in.read(buffer))!=-1) md.update(buffer,0,n);
        }
        StringBuilder text=new StringBuilder();
        for (byte b:md.digest()) text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return text.toString();
    }
    private static void compare(File expected,File actual) throws IOException {
        if (expected.length()!=actual.length()) throw new IOException("Length mismatch");
        try (DataInputStream left=new DataInputStream(new BufferedInputStream(new FileInputStream(expected)));
             DataInputStream right=new DataInputStream(new BufferedInputStream(new FileInputStream(actual)))) {
            byte[] a=new byte[65536],b=new byte[65536]; long remaining=expected.length();
            while (remaining>0) {
                int n=(int)Math.min(a.length,remaining);
                left.readFully(a,0,n); right.readFully(b,0,n);
                if (!Arrays.equals(a,b)) throw new IOException("Byte mismatch");
                remaining-=n;
            }
        }
    }
    private static long procKb(String key) throws IOException {
        try (BufferedReader in=new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line=in.readLine())!=null) if (line.startsWith(key)) return Long.parseLong(line.split("\\s+")[1]);
        }
        return -1;
    }
}
