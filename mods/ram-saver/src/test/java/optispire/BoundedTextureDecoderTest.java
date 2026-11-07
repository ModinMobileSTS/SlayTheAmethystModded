package optispire;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class BoundedTextureDecoderTest {
    public static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable task) { tasks.add(task); }
        public void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
        public void drainWorker() throws Exception {
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { drain(); } catch (Throwable t) { error.set(t); }
            }, "controlled-decode");
            worker.start(); worker.join(5000);
            assertFalse("Worker deadlock", worker.isAlive());
            if (error.get() != null) throw new AssertionError(error.get());
        }
    }
    static final class Pixels implements AutoCloseable {
        int closes;
        public void close() { assertEquals("Double release", 0, closes++); }
    }
    static class Source implements BoundedTextureDecoder.Source<Pixels> {
        final long bytes;
        final Pixels pixels = new Pixels();
        int probes, decodes;
        Thread thread;
        Source(long bytes) { this.bytes = bytes; }
        public long probe() { probes++; return bytes; }
        public Pixels decode() { thread = Thread.currentThread(); decodes++; return pixels; }
    }

    @Test public void reservesBeforeDecodeDeduplicatesAndKeepsLeaseCharged() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        BoundedTextureDecoder<Pixels> queue = new BoundedTextureDecoder<>(executor, 100, 3);
        Source first = new Source(60) {
            public Pixels decode() { assertEquals(60, queue.reservedBytes()); return super.decode(); }
        };
        Source second = new Source(60);
        assertEquals(BoundedTextureDecoder.State.PENDING, queue.request("a", first));
        queue.request("a", new Source(99));
        queue.request("b", second);
        assertEquals(1, executor.tasks.size());
        executor.drainWorker();
        assertNotSame(Thread.currentThread(), first.thread);
        assertEquals(1, first.decodes);
        assertEquals(0, second.decodes);
        assertEquals(60, queue.reservedBytes());
        BoundedTextureDecoder<Pixels>.Lease lease = queue.take("a");
        assertNotNull(lease);
        assertEquals(60, queue.reservedBytes());
        // Simulated uploader executes on the calling render thread while the lease owns the pixels.
        assertEquals(0, lease.pixels.closes);
        lease.close(); lease.close();
        assertEquals(1, first.pixels.closes);
        executor.drainWorker();
        assertEquals(1, second.decodes);
        queue.cancelAll();
        assertEquals(1, second.pixels.closes);
        assertEquals(0, queue.reservedBytes());
        assertEquals(0, queue.pendingCount());
    }

    @Test public void unknownOversizeAndFailuresAreTerminalWithoutDecodeOrReservation() {
        ManualExecutor executor = new ManualExecutor();
        BoundedTextureDecoder<Pixels> queue = new BoundedTextureDecoder<>(executor, 100, 4);
        Source unknown = new Source(0), huge = new Source(101);
        queue.request("unknown", unknown); queue.request("huge", huge);
        queue.request("bad", new Source(50) { public Pixels decode() { throw new IllegalStateException(); } });
        executor.drain();
        for (String key : new String[] {"unknown", "huge", "bad"})
            assertEquals(BoundedTextureDecoder.State.FALLBACK, queue.state(key));
        assertEquals(0, unknown.decodes + huge.decodes);
        assertEquals(0, queue.reservedBytes());
        queue.cancelAll();
        assertEquals(0, queue.pendingCount());
    }

    @Test public void canceledRunningGenerationRetainsBudgetAndCannotReplaceNewResult() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        BoundedTextureDecoder<Pixels> queue = new BoundedTextureDecoder<>(executor, 100, 2);
        CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        Source old = new Source(60) {
            public Pixels decode() {
                entered.countDown();
                try { assertTrue(resume.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return super.decode();
            }
        };
        queue.request("a", old);
        Thread worker = new Thread(executor::drain); worker.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertNull(queue.take("a")); // demand does not wait
        queue.cancel("a");
        Source replacement = new Source(60);
        queue.request("a", replacement);
        assertEquals(BoundedTextureDecoder.State.MISSING, queue.request("full", new Source(1)));
        assertEquals(60, queue.reservedBytes());
        assertEquals(2, queue.pendingCount());
        resume.countDown(); worker.join(5000);
        assertFalse(worker.isAlive());
        assertEquals(1, old.pixels.closes);
        try (BoundedTextureDecoder<Pixels>.Lease lease = queue.take("a")) {
            assertSame(replacement.pixels, lease.pixels);
        }
        assertEquals(0, queue.reservedBytes());
        assertEquals(0, queue.pendingCount());
    }

    @Test public void uploadFailureScopeCancelAndShutdownReleaseExactlyOnce() {
        ManualExecutor executor = new ManualExecutor();
        BoundedTextureDecoder<Pixels> queue = new BoundedTextureDecoder<>(executor, 100, 2);
        Source a = new Source(30), b = new Source(40);
        queue.request("a", a, 1); queue.request("a", a, 2); queue.request("b", b, 1);
        queue.cancelScope(1);
        executor.drain();
        assertEquals(0, b.decodes);
        assertEquals(BoundedTextureDecoder.State.READY, queue.state("a"));
        try (BoundedTextureDecoder<Pixels>.Lease lease = queue.take("a")) {
            throw new IllegalStateException("upload failed");
        } catch (IllegalStateException expected) { }
        assertEquals(1, a.pixels.closes);
        queue.request("b", b); executor.drain();
        queue.shutdown(); executor.drain();
        assertEquals(1, b.pixels.closes);
        assertEquals(0, queue.reservedBytes());
        assertEquals(0, queue.pendingCount());
        assertEquals(BoundedTextureDecoder.State.FALLBACK, queue.request("late", new Source(1)));
    }

    @Test public void executorRejectionCannotStrandPendingWork() {
        BoundedTextureDecoder<Pixels> queue = new BoundedTextureDecoder<>(task -> {
            throw new java.util.concurrent.RejectedExecutionException();
        }, 100, 2);
        assertEquals(BoundedTextureDecoder.State.FALLBACK, queue.request("a", new Source(10)));
        queue.cancelAll();
        assertEquals(0, queue.pendingCount());
    }

    @Test public void shutdownDuringDecodeReturnsImmediatelyAndDiscardsEventualPixels() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        BoundedTextureDecoder<Pixels> queue = new BoundedTextureDecoder<>(executor, 100, 2);
        CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        Source source = new Source(80) {
            public Pixels decode() {
                entered.countDown();
                try { assertTrue(resume.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return super.decode();
            }
        };
        queue.request("running", source);
        Thread worker = new Thread(executor::drain); worker.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            queue.shutdown();
            assertEquals(80, queue.reservedBytes());
            assertEquals(1, queue.pendingCount());
            resume.countDown(); worker.join(5000); assertFalse(worker.isAlive());
            assertEquals(1, source.pixels.closes);
            assertEquals(0, queue.reservedBytes());
            assertEquals(0, queue.pendingCount());
        } finally { resume.countDown(); worker.join(5000); }
    }

    @Test public void cancellationDuringProbeDoesNotDecodeOrLeakSlot() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        BoundedTextureDecoder<Pixels> queue = new BoundedTextureDecoder<>(executor, 100, 1);
        Source source = new Source(80) {
            public long probe() { queue.cancel("probe"); return super.probe(); }
        };
        queue.request("probe", source); executor.drainWorker();
        assertEquals(0, source.decodes);
        assertEquals(0, queue.pendingCount());
        assertEquals(0, queue.reservedBytes());
    }
}
