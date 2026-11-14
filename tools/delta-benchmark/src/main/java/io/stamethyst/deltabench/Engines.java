package io.stamethyst.deltabench;

import com.google.archivepatcher.applier.bsdiff.BsPatch;
import com.google.archivepatcher.generator.bsdiff.BsDiffPatchWriter;
import java.io.*;
import java.util.zip.*;

/** Experiment-only adapters. No production publishing or recovery promises. */
public final class Engines {
    public static final String[] NAMES = {"replace-deflate6", "jvm-bsdiff43-deflate6"};
    public static final String[] ALL_NAMES = {"replace-deflate6", "jvm-bsdiff43-deflate6",
        "native-bsdiff43-deflate6", "native-hdiff-single-deflate6", "native-xdelta3-deflate6",
        "native-hdiff-stream256-raw", "native-xdelta-stream256-raw",
        "native-hdiff-stream1024-raw", "native-xdelta-stream1024-raw",
        "native-xdelta-stream1024-src32m-raw", "native-xdelta-stream1024-src4m-raw",
        "native-xdelta-stream1024-src8m-raw", "native-hdiff-stream256-deflate6",
        "native-xdelta-stream1024-src8m-deflate6"};
    public static long[] streamStats;
    public static long outputLimit = 128L * 1024 * 1024;
    public static long allocLimit = 64L * 1024 * 1024;

    static boolean streaming(String engine) { return engine.contains("-stream"); }
    static long[] stream(String engine, boolean encode, File base, File input, File out, long expected) throws IOException {
        int window = engine.contains("1024") ? 1024*1024 : 256*1024;
        if(engine.endsWith("deflate6")){
            File raw=new File(out.getParentFile(),"codec-work.raw");
            if(raw.exists())throw new IOException("Owned codec temporary already exists");
            try{
                if(encode){
                    long[] stats=nativeStream(engine,true,base,input,raw,expected,window);
                    Deflater codec=new Deflater(6,true);
                    try(InputStream in=new FileInputStream(raw);FileOutputStream file=new FileOutputStream(out)){
                        DeflaterOutputStream compressed=new DeflaterOutputStream(file,codec,65536);
                        copy(in,compressed);compressed.finish();file.getFD().sync();
                    }finally{codec.end();}
                    return stats;
                }else{
                    Inflater codec=new Inflater(true);
                    try(InputStream file=new FileInputStream(input);InputStream in=new InflaterInputStream(file,codec,65536);
                        FileOutputStream output=new FileOutputStream(raw)){
                        byte[] buf=new byte[65536];int n;long total=0;
                        while((n=in.read(buf))!=-1){total+=n;if(total>128L*1024*1024)throw new IOException("Expanded patch cap");output.write(buf,0,n);}
                        if(!codec.finished())throw new IOException("Incomplete compressed stream");output.getFD().sync();
                    }finally{codec.end();}
                    return nativeStream(engine,false,base,raw,out,expected,window);
                }
            }finally{if(raw.exists()&&!raw.delete())throw new IOException("Codec workspace cleanup failed");}
        }
        return nativeStream(engine,encode,base,input,out,expected,window);
    }
    private static long[] nativeStream(String engine,boolean encode,File base,File input,File out,long expected,int window)throws IOException{
        return NativeBridge.stream(engine.contains("hdiff"),encode,base.getAbsolutePath(),input.getAbsolutePath(),
            out.getAbsolutePath(),expected,window,outputLimit,allocLimit,
            engine.contains("src32m") ? 32L*1024*1024 : engine.contains("src8m") ? 8L*1024*1024
            : engine.contains("src4m") ? 4L*1024*1024 : window);
    }

    public static void validate(String engine) {
        for (String name : ALL_NAMES) if (name.equals(engine)) return;
        throw new IllegalArgumentException("Engine not implemented: " + engine);
    }

    public static void generate(String engine, File base, File target, File patch) throws Exception {
        validate(engine);
        if (streaming(engine)) { streamStats=stream(engine,true,base,target,patch,target.length()); return; }
        Deflater codec = new Deflater(6, true);
        try (FileOutputStream file = new FileOutputStream(patch)) {
            DeflaterOutputStream out = new DeflaterOutputStream(file, codec, 65536);
            if (engine.equals(NAMES[0])) {
                try (InputStream in = new BufferedInputStream(new FileInputStream(target))) { copy(in, out); }
            } else if (engine.startsWith("native-")) {
                out.write(NativeBridge.transform(nativeId(engine), true, read(base), read(target), (int) target.length()));
            } else {
                // Includes input materialization and suffix-array allocation in measured work.
                BsDiffPatchWriter.generatePatch(read(base), read(target), out, 16);
            }
            out.finish();
            file.getFD().sync();
        } finally { codec.end(); }
    }

    public static void apply(String engine, File base, File patch, File output, long expectedSize) throws Exception {
        validate(engine);
        if (streaming(engine)) { streamStats=stream(engine,false,base,patch,output,expectedSize); return; }
        Inflater codec = new Inflater(true);
        try (InputStream compressed = new BufferedInputStream(new FileInputStream(patch));
             InputStream in = new InflaterInputStream(compressed, codec, 65536);
             FileOutputStream file = new FileOutputStream(output)) {
            BoundedOutput out = new BoundedOutput(file, expectedSize);
            if (engine.startsWith("native-")) {
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (raw.size() + count > 64 * 1024 * 1024) throw new IOException("Raw patch limit");
                    raw.write(buffer, 0, count);
                }
                if (expectedSize < 0 || expectedSize > Corpus.MAX_SIZE + 37) throw new IOException("Output limit");
                out.write(NativeBridge.transform(nativeId(engine), false, read(base), raw.toByteArray(), (int) expectedSize));
            } else if (engine.equals(NAMES[0])) copy(in, out);
            else try (RandomAccessFile old = new RandomAccessFile(base, "r")) {
                BsPatch.applyPatch(old, out, in, expectedSize);
            }
            if (out.count != expectedSize || in.read() != -1 || !codec.finished()) {
                throw new IOException("Incomplete or oversized output/stream");
            }
            out.flush();
            file.getFD().sync();
        } finally { codec.end(); }
    }

    private static int nativeId(String engine) {
        if (engine.equals(ALL_NAMES[2])) return 0;
        if (engine.equals(ALL_NAMES[3])) return 1;
        if (engine.equals(ALL_NAMES[4])) return 2;
        throw new IllegalArgumentException("Unknown native engine");
    }

    private static byte[] read(File file) throws IOException {
        if (file.length() > Corpus.MAX_SIZE + 37) throw new IOException("Input too large");
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(in, out);
            return out.toByteArray();
        }
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
    }

    private static final class BoundedOutput extends FilterOutputStream {
        final long limit;
        long count;
        BoundedOutput(OutputStream out, long limit) { super(out); this.limit = limit; }
        @Override public void write(int value) throws IOException {
            if (count >= limit) throw new IOException("Output limit exceeded");
            out.write(value); count++;
        }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (len > limit - count) throw new IOException("Output limit exceeded");
            out.write(b, off, len); count += len;
        }
    }

    private Engines() {}
}
