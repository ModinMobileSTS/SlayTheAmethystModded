package optispire;

import com.badlogic.gdx.files.FileHandle;
import java.io.*;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** Streaming, unpremultiplied 8-bit RGB/RGBA PNG. Deliberately independent of gdx2d/stb global state. */
public final class PngTextureDecoder implements BoundedTextureDecoder.Source<PngTextureDecoder.Pixels> {
    private static final long SIGNATURE = 0x89504e470d0a1a0aL;
    private final FileHandle file;
    private int width, height, channels;
    private volatile boolean canceled;

    public PngTextureDecoder(FileHandle file) { this.file = file; }
    public void cancel() { canceled = true; }

    public static final class Pixels implements AutoCloseable {
        public final int width, height, channels;
        private byte[] bytes;
        public Pixels(int width, int height, int channels, byte[] bytes) {
            this.width = width; this.height = height; this.channels = channels; this.bytes = bytes;
        }
        public byte[] bytes() {
            if (bytes == null) throw new IllegalStateException("Pixels released");
            return bytes;
        }
        public void close() { bytes = null; }
    }

    public long probe() throws IOException {
        try (Chunks png = new Chunks(file.read(), () -> canceled)) {
            int[] header = png.header();
            width = header[0]; height = header[1]; channels = header[2];
            long area = (long) width * height;
            // Heap output, two filter rows, native staging + format/mipmap/downscale temporaries.
            // Oversize/overflow never reaches decode. Native zlib workspace gets a fixed allowance.
            if (area > Integer.MAX_VALUE / 4) return Long.MAX_VALUE;
            long rgba = area * 4;
            return rgba * 4 + (long) width * channels * 2 + 128 * 1024;
        }
    }

    public Pixels decode() throws IOException {
        try (Chunks png = new Chunks(file.read(), () -> canceled)) {
            if (!Arrays.equals(new int[] {width, height, channels}, png.header()))
                throw new IOException("PNG changed after reservation");
            png.startData();
            int stride = Math.multiplyExact(width, channels);
            byte[] pixels = new byte[Math.multiplyExact(stride, height)];
            byte[] row = new byte[stride], previous = new byte[stride];
            Inflater inflater = new Inflater();
            try {
                InflaterInputStream input = new InflaterInputStream(png, inflater, 8192);
                DataInputStream rows = new DataInputStream(input);
                for (int y = 0; y < height; y++) {
                    if (canceled || Thread.currentThread().isInterrupted()) throw new IOException("Canceled PNG decode");
                    int filter = rows.readUnsignedByte();
                    if (filter > 4) throw new IOException("Invalid PNG filter");
                    rows.readFully(row);
                    for (int x = 0; x < stride; x++) {
                        int a = x >= channels ? row[x - channels] & 255 : 0;
                        int b = previous[x] & 255;
                        int c = x >= channels ? previous[x - channels] & 255 : 0;
                        int predictor = filter == 0 ? 0 : filter == 1 ? a : filter == 2 ? b
                                : filter == 3 ? (a + b) / 2 : paeth(a, b, c);
                        row[x] = (byte) ((row[x] & 255) + predictor);
                    }
                    System.arraycopy(row, 0, pixels, y * stride, stride);
                    byte[] swap = previous; previous = row; row = swap;
                }
                if (rows.read() != -1 || !inflater.finished()) throw new IOException("Invalid PNG data length");
                png.finish();
                return new Pixels(width, height, channels, pixels);
            } finally { inflater.end(); }
        }
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }

    /** Bounded streaming chunk reader: no allocation from an untrusted compressed/chunk length. */
    private static final class Chunks extends InputStream {
        final DataInputStream in;
        final CRC32 crc = new CRC32();
        final byte[] scratch = new byte[8192];
        final java.util.function.BooleanSupplier canceled;
        int remaining, type;
        long consumed;
        boolean dataEnded;
        Chunks(InputStream input, java.util.function.BooleanSupplier canceled) {
            in = new DataInputStream(new BufferedInputStream(input, 8192));
            this.canceled = canceled;
        }
        int[] header() throws IOException {
            if (in.readLong() != SIGNATURE) throw new IOException("Not PNG");
            next();
            if (type != 0x49484452 || remaining != 13) throw new IOException("Invalid IHDR");
            byte[] header = new byte[13];
            raw(header, 0, 13);
            DataInputStream h = new DataInputStream(new ByteArrayInputStream(header));
            int w = h.readInt(), height = h.readInt(), depth = h.readUnsignedByte(), color = h.readUnsignedByte();
            if (w <= 0 || height <= 0 || depth != 8 || (color != 2 && color != 6)
                    || h.readUnsignedByte() != 0 || h.readUnsignedByte() != 0 || h.readUnsignedByte() != 0)
                throw new IOException("Unsupported PNG layout");
            end();
            return new int[] {w, height, color == 2 ? 3 : 4};
        }
        void next() throws IOException {
            if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Canceled PNG read");
            remaining = in.readInt(); type = in.readInt();
            if (remaining < 0 || (consumed += (long) remaining + 12) > 64L * 1024 * 1024)
                throw new IOException("PNG input exceeds 64 MiB");
            crc.reset();
            for (int shift = 24; shift >= 0; shift -= 8) crc.update(type >>> shift & 255);
        }
        void end() throws IOException {
            if (remaining != 0 || (int) crc.getValue() != in.readInt()) throw new IOException("PNG CRC mismatch");
        }
        void skipChunk() throws IOException {
            // tRNS changes decoded channel semantics; APNG is intentionally left to the original decoder.
            if (type == 0x74524e53 || type == 0x6163544c
                    || ((type & 0x20000000) == 0 && type != 0x504c5445))
                throw new IOException("Unsupported PNG chunk");
            while (remaining > 0) raw(scratch, 0, Math.min(remaining, scratch.length));
            end();
        }
        void startData() throws IOException {
            next();
            while (type != 0x49444154) { skipChunk(); next(); }
        }
        int raw(byte[] bytes, int off, int len) throws IOException {
            if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Canceled PNG read");
            in.readFully(bytes, off, len); crc.update(bytes, off, len); remaining -= len; return len;
        }
        public int read(byte[] bytes, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (dataEnded) return -1;
            while (remaining == 0) {
                end(); next();
                if (type != 0x49444154) { dataEnded = true; return -1; }
            }
            return raw(bytes, off, Math.min(len, remaining));
        }
        public int read() throws IOException {
            byte[] one = new byte[1]; return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
        }
        void finish() throws IOException {
            // Drain any remaining compressed data through CRC validation, then require IEND.
            while (read(scratch, 0, scratch.length) != -1) { }
            while (type != 0x49454e44) { skipChunk(); next(); }
            if (remaining != 0) throw new IOException("Invalid IEND");
            end();
        }
        public void close() throws IOException { in.close(); }
    }
}
