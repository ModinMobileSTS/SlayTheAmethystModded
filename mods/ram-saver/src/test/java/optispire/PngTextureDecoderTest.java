package optispire;

import com.badlogic.gdx.files.FileHandle;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class PngTextureDecoderTest {
    static FileHandle file(byte[] bytes) {
        return new FileHandle("fixture.png") { public InputStream read() { return new ByteArrayInputStream(bytes); } };
    }
    @Test public void allFiltersRgbRgbaAndSplitChunksPreserveExactPixels() throws Exception {
        for (int channels : new int[] {3, 4}) {
            byte[] bytes = new byte[7 * 5 * channels];
            new java.util.Random(42).nextBytes(bytes);
            for (int filter = 0; filter <= 4; filter++) {
                PngTextureDecoder decoder = new PngTextureDecoder(file(PngFixtures.image(7, 5, channels, bytes, filter)));
                assertEquals(7L * 5 * 4 * 4 + 7 * channels * 2 + 128 * 1024, decoder.probe());
                try (PngTextureDecoder.Pixels pixels = decoder.decode()) { assertArrayEquals(bytes, pixels.bytes()); }
            }
        }
    }
    @Test public void unknownUnsupportedAndOverflowCannotAllocateDecodedStorage() throws Exception {
        for (byte[] bytes : new byte[][] {new byte[0], PngFixtures.header(1, 1, 16, 6, 0),
                PngFixtures.header(1, 1, 8, 3, 0), PngFixtures.header(1, 1, 8, 6, 1)}) {
            try { new PngTextureDecoder(file(bytes)).probe(); fail("Must fall back"); }
            catch (IOException expected) { }
        }
        assertEquals(Long.MAX_VALUE,
                new PngTextureDecoder(file(PngFixtures.header(Integer.MAX_VALUE, Integer.MAX_VALUE, 8, 6, 0))).probe());
    }
    @Test public void changedDimensionsCrcTruncationAndTransparencyAreRejected() throws Exception {
        byte[] valid = PngFixtures.image(1, 1, 3, new byte[] {1, 2, 3}, 0);
        for (byte[] bytes : new byte[][] {java.util.Arrays.copyOf(valid, valid.length - 4), corrupt(valid), transparent(valid)}) {
            PngTextureDecoder decoder = new PngTextureDecoder(file(bytes)); decoder.probe();
            try { decoder.decode(); fail("Must reject invalid/unsupported PNG"); } catch (IOException expected) { }
        }
        final int[] reads = {0};
        PngTextureDecoder decoder = new PngTextureDecoder(new FileHandle("changing.png") {
            public InputStream read() {
                try { return new ByteArrayInputStream(reads[0]++ == 0 ? valid : PngFixtures.header(999, 999, 8, 6, 0)); }
                catch (IOException e) { throw new AssertionError(e); }
            }
        });
        decoder.probe();
        try { decoder.decode(); fail("Changed dimensions bypassed reservation"); } catch (IOException expected) { }
    }
    private static byte[] corrupt(byte[] bytes) { byte[] copy = bytes.clone(); copy[45] ^= 1; return copy; }
    private static byte[] transparent(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(bytes, 0, 33); PngFixtures.chunk(out, "tRNS", new byte[6]);
        out.write(bytes, 33, bytes.length - 33); return out.toByteArray();
    }
}
