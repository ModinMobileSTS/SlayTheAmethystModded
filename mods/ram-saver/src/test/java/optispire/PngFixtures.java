package optispire;

import java.io.*;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/** Fixtures independent of the production parser: encode all PNG filters and split IDAT chunks. */
public final class PngFixtures {
    public static byte[] image(int width, int height, int channels, byte[] pixels, int filter) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        int stride = width * channels;
        for (int y = 0; y < height; y++) {
            raw.write(filter);
            for (int x = 0; x < stride; x++) {
                int offset = y * stride + x;
                int a = x >= channels ? pixels[offset - channels] & 255 : 0;
                int b = y > 0 ? pixels[offset - stride] & 255 : 0;
                int c = x >= channels && y > 0 ? pixels[offset - stride - channels] & 255 : 0;
                int p = a + b - c;
                int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
                int prediction = filter == 0 ? 0 : filter == 1 ? a : filter == 2 ? b : filter == 3
                        ? (a + b) / 2 : pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
                raw.write((pixels[offset] & 255) - prediction);
            }
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(compressed)) { deflate.write(raw.toByteArray()); }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(header(width, height, 8, channels == 3 ? 2 : 6, 0));
        byte[] zipped = compressed.toByteArray();
        int split = zipped.length / 2;
        chunk(png, "IDAT", java.util.Arrays.copyOfRange(zipped, 0, split));
        chunk(png, "IDAT", java.util.Arrays.copyOfRange(zipped, split, zipped.length));
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }
    public static byte[] header(int w, int h, int depth, int color, int interlace) throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        new DataOutputStream(png).writeLong(0x89504e470d0a1a0aL);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(header);
        data.writeInt(w); data.writeInt(h); data.writeByte(depth); data.writeByte(color);
        data.writeByte(0); data.writeByte(0); data.writeByte(interlace);
        chunk(png, "IHDR", header.toByteArray());
        return png.toByteArray();
    }
    public static void chunk(OutputStream out, String type, byte[] payload) throws IOException {
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(payload.length);
        byte[] name = type.getBytes("US-ASCII");
        data.write(name); data.write(payload);
        CRC32 crc = new CRC32(); crc.update(name); crc.update(payload);
        data.writeInt((int) crc.getValue());
    }
}
