package com.badlogic.gdx.graphics;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.glutils.FileTextureData;
import com.badlogic.gdx.utils.GdxNativesLoader;
import optispire.*;
import optispire.BoundedTextureDecoderTest.ManualExecutor;
import org.junit.*;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.Assert.*;

/** Real production decoder, FileTextureData/Pixmap, supplier and managed lifecycle; only GL is mocked. */
public class BackgroundTextureDecodeTest extends RamSaverTestSupport {
    private ManualExecutor executor;
    private BoundedTextureDecoder<PngTextureDecoder.Pixels> queue;
    private final List<Thread> reads = Collections.synchronizedList(new ArrayList<Thread>());
    private final List<Thread> uploads = new ArrayList<>();
    private byte[] uploaded;
    private int mipmaps;
    private boolean failUpload;
    private int failLevel = -1;
    private final List<ByteBuffer> pixelBuffers = new ArrayList<>();

    @Before public void configureDecode() throws Exception {
        GdxNativesLoader.load();
        Gdx.app = proxy(com.badlogic.gdx.Application.class, (name, args) ->
                name.equals("getType") ? com.badlogic.gdx.Application.ApplicationType.Android : null);
        executor = new ManualExecutor();
        queue = new BoundedTextureDecoder<>(executor, 2 * 1024 * 1024, 3);
        set(TextureDecodeService.class, "decoder", queue);
        set(TextureDecodeService.class, "stopped", false);
        GL20 underlying = Gdx.gl20;
        Gdx.gl = Gdx.gl20 = (GL20) java.lang.reflect.Proxy.newProxyInstance(GL20.class.getClassLoader(),
                new Class<?>[] {GL20.class}, (object, method, args) -> {
                    assertSame("Worker reached GL", renderThread, Thread.currentThread());
                    if (method.getName().equals("glGenerateMipmap")) mipmaps++;
                    if (method.getName().equals("glTexImage2D")) {
                        uploads.add(Thread.currentThread());
                        ByteBuffer buffer = ((ByteBuffer) args[8]).duplicate();
                        pixelBuffers.add((ByteBuffer) args[8]);
                        uploaded = new byte[buffer.remaining()]; buffer.get(uploaded);
                        if (failUpload || (Integer) args[1] == failLevel) throw new IllegalStateException("controlled upload failure");
                    }
                    return method.invoke(underlying, args);
                });
    }
    private final Thread renderThread = Thread.currentThread();
    @After public void clearDecode() throws Exception {
        queue.shutdown(); executor.drainWorker();
        assertEquals(0, queue.reservedBytes());
        assertEquals(0, queue.pendingCount());
        set(TextureDecodeService.class, "decoder", null);
        set(TextureDecodeService.class, "stopped", false);
    }

    private Texture image(String path, Pixmap.Format format, boolean mipmap) throws Exception {
        byte[] png = PngFixtures.image(2, 2, 4, new byte[] {
                1, 2, 3, 4, 11, 12, 13, 14, 21, 22, 23, 24, 31, 32, 33, 34}, 4);
        MemoryFile file = new MemoryFile(path, png) {
            public InputStream read() { reads.add(Thread.currentThread()); return super.read(); }
        };
        Texture fake = new Texture(file, format, mipmap);
        fake.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        fake.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.MirroredRepeat);
        reads.clear();
        return fake;
    }

    @Test public void baselineDemandDecodesOnRenderWhilePrewarmDecodesOnWorkerAndUploadsOnRender() throws Exception {
        Texture baseline = image("baseline.png", null, false);
        baseline.getRealTexture();
        assertFalse(reads.isEmpty());
        for (Thread reader : reads) assertSame(renderThread, reader);
        assertEquals(1, uploads.size());
        byte[] expected = uploaded.clone();

        Texture background = image("background.png", null, false);
        String key = background.getRamSaverKey();
        RunTextureResidency.replaceCandidates(Collections.singletonMap(key, 16L));
        RunTextureResidency.prewarmOne(null);
        assertEquals("Enqueue does not upload", 1, uploads.size());
        assertTrue("Enqueue is not completion", RunTextureResidency.hasPending(null));
        assertTrue("Enqueue does not read file on render", reads.isEmpty());
        executor.drainWorker();
        for (Thread reader : reads) assertNotSame(renderThread, reader);
        assertEquals(BoundedTextureDecoder.State.READY, queue.state(key));
        assertTrue(queue.reservedBytes() > 0);
        int workerReads = reads.size();
        RunTextureResidency.prewarmOne(null);
        assertEquals(workerReads, reads.size());
        assertEquals(2, uploads.size());
        assertArrayEquals(expected, uploaded);
        assertFalse(RunTextureResidency.hasPending(null));
        assertEquals(0, queue.reservedBytes());
        Texture real = background.getRealTexture();
        assertTrue(real.isManaged());
        assertTrue(real.getTextureData() instanceof FileTextureData);
        assertEquals(background.file, ((FileTextureData) real.getTextureData()).getFileHandle());
        assertEquals(Texture.TextureFilter.Linear, real.getMinFilter());
        assertEquals(Texture.TextureWrap.Repeat, real.getUWrap());
        assertEquals(Texture.TextureWrap.MirroredRepeat, real.getVWrap());
        live.clear(); // GL context lost: old names disappeared without glDeleteTexture.
        Texture.invalidateAllTextures(Gdx.app);
        assertArrayEquals(expected, uploaded);
        assertEquals(4, uploads.size());
    }

    @Test public void queuedDemandInvalidatesOldGenerationAndNeverUploadsItLater() throws Exception {
        Texture fake = image("demand-race.png", null, false);
        String key = fake.getRamSaverKey();
        assertFalse(RamSaver.prewarmTexture(key, true, TextureDecodeService.RUN));
        Texture synchronous = fake.getRealTexture();
        executor.drainWorker();
        assertSame(synchronous, fake.getRealTexture());
        assertEquals(1, uploads.size());
        assertEquals(0, queue.reservedBytes());
        assertEquals(BoundedTextureDecoder.State.MISSING, queue.state(key));
    }

    @Test public void pressureScopeDisposeAndContextCancelProductionJobs() throws Exception {
        Texture fake = image("cancel-ready.png", null, false);
        String key = fake.getRamSaverKey();
        RunTextureResidency.replaceCandidates(Collections.singletonMap(key, 16L));
        RunTextureResidency.prewarmOne(null); executor.drainWorker();
        RunTextureResidency.replaceCandidates(Collections.emptyMap());
        assertEquals(0, queue.reservedBytes());
        assertFalse(RamSaver.prewarmTexture(key, true, TextureDecodeService.FIRST_COMBAT));
        executor.drainWorker();
        Texture.invalidateAllTextures(Gdx.app);
        assertEquals(0, queue.reservedBytes());
        RamSaver.prewarmTexture(key, true, TextureDecodeService.RUN); executor.drainWorker();
        fake.dispose();
        assertEquals(0, queue.reservedBytes());
        RamSaver.prewarmTexture(key, true, TextureDecodeService.RUN); executor.drainWorker();
        long staging = queue.reservedBytes();
        assertTrue(staging > 0);
        // 64 MiB threshold: staging contributes exactly once, and isn't credited as reclaimable GL.
        RunTextureResidency.updatePressure(64L * 1024 * 1024 - staging, 1, 100, 0);
        assertTrue(RunTextureResidency.isUnderPressure());
        assertEquals(0, queue.reservedBytes());
        assertFalse(RamSaver.prewarmTexture(key, true, TextureDecodeService.RUN));
        assertEquals(0, queue.pendingCount());
    }

    @Test public void runPressureCancelsOnlyRunDecodeAndKeepsReadyFirstCombatPixels() throws Exception {
        Texture run = image("pressure-run.png", null, false);
        Texture firstCombat = image("pressure-first-combat.png", null, false);
        assertFalse(RamSaver.prewarmTexture(run.getRamSaverKey(), true, TextureDecodeService.RUN));
        assertFalse(RamSaver.prewarmTexture(firstCombat.getRamSaverKey(), false, TextureDecodeService.FIRST_COMBAT));
        executor.drainWorker();
        assertEquals(BoundedTextureDecoder.State.READY, queue.state(run.getRamSaverKey()));
        assertEquals(BoundedTextureDecoder.State.READY, queue.state(firstCombat.getRamSaverKey()));
        RunTextureResidency.updatePressure(Long.MAX_VALUE, 1, 100, 0L);
        assertEquals(BoundedTextureDecoder.State.MISSING, queue.state(run.getRamSaverKey()));
        assertEquals(BoundedTextureDecoder.State.READY, queue.state(firstCombat.getRamSaverKey()));
        reads.clear();
        assertTrue(RamSaver.prewarmTexture(firstCombat.getRamSaverKey(), false, TextureDecodeService.FIRST_COMBAT));
        assertTrue("Ready pixels must not be re-read on render after run pressure", reads.isEmpty());
        assertEquals(1, uploads.size());
        assertSame(Thread.currentThread(), uploads.get(0));
        assertEquals(0, queue.reservedBytes());
    }

    @Test public void nativeFormatMipmapAndSamplerVariantsRemainIndependent() throws Exception {
        Texture fake = image("format.png", Pixmap.Format.RGB565, true);
        String key = fake.getRamSaverKey();
        RamSaver.prewarmTexture(key, true, TextureDecodeService.RUN); executor.drainWorker();
        assertTrue(RamSaver.prewarmTexture(key, true, TextureDecodeService.RUN));
        Texture real = fake.getRealTexture();
        assertEquals(Pixmap.Format.RGB565, real.getTextureData().getFormat());
        assertTrue(real.getTextureData().useMipMaps());
        assertEquals(8, uploaded.length);
        assertEquals(1, mipmaps);
        assertEquals(0, queue.reservedBytes());
        Texture different = fake.getRealTexture(Texture.TextureFilter.Nearest, null, null, null);
        assertNotSame(real, different);
        assertEquals(Texture.TextureFilter.Linear, real.getMinFilter());
    }

    @Test public void uploadFailureReleasesStagingAndGeneratedHandle() throws Exception {
        Texture fake = image("upload-failure.png", Pixmap.Format.RGB565, false);
        String key = fake.getRamSaverKey();
        RamSaver.prewarmTexture(key, true, TextureDecodeService.RUN); executor.drainWorker();
        failUpload = true;
        try { fake.getRealTexture(); fail("Controlled upload must fail including fallback"); }
        catch (RuntimeException expected) { }
        finally { failUpload = false; }
        assertEquals(0, queue.reservedBytes());
        assertTrue("All failed handles deleted", live.isEmpty());
        assertEquals(0, Texture.getNumManagedTextures());
    }

    @Test public void softwareMipmapsRestoreBlendAndKeepBorrowedSourceUsableOnFailure() throws Exception {
        com.badlogic.gdx.graphics.glutils.MipMapGenerator.setUseHardwareMipMap(false);
        Pixmap source = new Pixmap(4, 4, Pixmap.Format.RGBA8888);
        Pixmap.Blending before = Pixmap.getBlending();
        // CPU path borrows source; last level and failure level must both be freed.
        try {
            com.badlogic.gdx.graphics.glutils.MipMapGenerator.generateMipMap(GL20.GL_TEXTURE_2D, source, 4, 4);
            assertEquals(3, pixelBuffers.size());
            assertEquals(before, Pixmap.getBlending());
            source.getPixels();
            pixelBuffers.clear();
            failLevel = 1;
            try {
                com.badlogic.gdx.graphics.glutils.MipMapGenerator.generateMipMap(GL20.GL_TEXTURE_2D, source, 4, 4);
                fail("Controlled level upload failure");
            } catch (RuntimeException expected) { }
            assertEquals(before, Pixmap.getBlending());
            source.getPixels();
        } finally {
            failLevel = -1;
            source.dispose();
            com.badlogic.gdx.graphics.glutils.MipMapGenerator.setUseHardwareMipMap(true);
        }
    }

    @Test public void runningProductionDecodeCannotOverwriteSynchronousDemand() throws Exception {
        Texture fake = image("running-demand.png", null, false);
        String key = fake.getRamSaverKey();
        PngTextureDecoder source = new PngTextureDecoder(fake.file);
        java.util.concurrent.CountDownLatch decoded = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch resume = new java.util.concurrent.CountDownLatch(1);
        final PngTextureDecoder.Pixels[] result = {null};
        queue.request(key, new BoundedTextureDecoder.Source<PngTextureDecoder.Pixels>() {
            public long probe() throws Exception { return source.probe(); }
            public PngTextureDecoder.Pixels decode() throws Exception {
                result[0] = source.decode(); decoded.countDown();
                assertTrue(resume.await(5, java.util.concurrent.TimeUnit.SECONDS));
                return result[0];
            }
        });
        Thread worker = new Thread(executor::drain); worker.start();
        try {
            assertTrue(decoded.await(5, java.util.concurrent.TimeUnit.SECONDS));
            Texture synchronous = fake.getRealTexture();
            assertEquals(1, uploads.size());
            assertTrue("Canceled active job keeps reservation", queue.reservedBytes() > 0);
            resume.countDown(); worker.join(5000); assertFalse(worker.isAlive());
            assertEquals(0, queue.reservedBytes());
            assertSame(synchronous, fake.getRealTexture());
            assertEquals(1, uploads.size());
            try { result[0].bytes(); fail("Canceled decoded pixels retained"); }
            catch (IllegalStateException expected) { }
        } finally { resume.countDown(); worker.join(5000); }
    }

    @Test public void rgbDecodedBytesMatchNativeAndUnsupportedJpegKeepsSynchronousSemantics() throws Exception {
        byte[] rgb = {1, 2, 3, 11, 12, 13, 21, 22, 23, 31, 32, 33};
        MemoryFile png = new MemoryFile("rgb.png", PngFixtures.image(2, 2, 3, rgb, 3));
        Texture fake = new Texture(png);
        RamSaver.prewarmTexture(fake.getRamSaverKey(), true, TextureDecodeService.RUN);
        executor.drainWorker();
        Texture real = fake.getRealTexture();
        assertEquals(Pixmap.Format.RGB888, real.getTextureData().getFormat());
        assertArrayEquals(rgb, uploaded);
        RamSaver.dispose(fake.getRamSaverKey());
        fake.getRealTexture(); // synchronous native decoder, same byte/channel semantics
        assertArrayEquals(rgb, uploaded);

        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(image, "jpg", bytes));
        MemoryFile jpeg = new MemoryFile("sync.jpg", bytes.toByteArray()) {
            public InputStream read() { reads.add(Thread.currentThread()); return super.read(); }
        };
        reads.clear();
        Texture jpegTexture = new Texture(jpeg);
        assertTrue(RamSaver.prewarmTexture(jpegTexture.getRamSaverKey(), true, TextureDecodeService.RUN));
        for (Thread reader : reads) assertSame(renderThread, reader);
        assertEquals(0, queue.pendingCount());
        assertEquals(2, jpegTexture.getRealTexture().getWidth());
    }

    @Test public void multirowOddWidthImagesMatchNativeAcrossEveryFilterAndChannelLayout() throws Exception {
        for (int channels : new int[] {3, 4}) {
            byte[] expected = new byte[65 * 33 * channels];
            new Random(713L + channels).nextBytes(expected);
            for (int filter = 0; filter <= 4; filter++) {
                Texture fake = new Texture(new MemoryFile("acceptance-" + channels + "-" + filter + ".png",
                        PngFixtures.image(65, 33, channels, expected, filter)));
                String key = fake.getRamSaverKey();
                int before = uploads.size();
                assertFalse(RamSaver.prewarmTexture(key, true, TextureDecodeService.RUN));
                executor.drainWorker();
                assertEquals("Worker cannot upload", before, uploads.size());
                assertTrue(RamSaver.prewarmTexture(key, true, TextureDecodeService.RUN));
                byte[] background = uploaded.clone();
                assertArrayEquals("All scanlines and alpha bytes survive background decoding", expected, background);
                assertEquals(0, queue.reservedBytes());
                RamSaver.dispose(key);
                fake.getRealTexture(); // Original native path, without a queued job.
                assertArrayEquals("Background output matches the bundled native decoder", uploaded, background);
                RamSaver.dispose(key);
            }
        }
    }

    @Test public void disablingDecodeSwitchMakesProductionPrewarmSynchronousWithoutWorkerWork() throws Exception {
        String old = System.getProperty("ramsaver.decode.enabled");
        System.setProperty("ramsaver.decode.enabled", "false");
        try {
            Texture fake = image("decode-switch-off.png", null, false);
            assertTrue(RamSaver.prewarmTexture(fake.getRamSaverKey(), true, TextureDecodeService.FIRST_COMBAT));
            assertFalse(reads.isEmpty());
            for (Thread reader : reads) assertSame(renderThread, reader);
            assertEquals(1, uploads.size());
            assertEquals(0, queue.pendingCount());
            assertEquals(0, queue.reservedBytes());
            executor.drainWorker();
            assertEquals("No delayed upload or duplicate decode", 1, uploads.size());
        } finally {
            if (old == null) System.clearProperty("ramsaver.decode.enabled");
            else System.setProperty("ramsaver.decode.enabled", old);
        }
    }
}
