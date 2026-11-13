package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

internal fun <T> withAgentSourceCacheRoot(block: (File) -> T): T {
    val repo = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "AGENTS.md").isFile }
    val temporary = File(repo, "agent-tmp").apply { mkdirs() }
    val root = Files.createTempDirectory(temporary.toPath(), "source-cache-test-").toFile()
    return try { block(root) } finally { root.deleteRecursively() }
}

internal fun replaceKeepingSizeAndMtime(file: File, content: ByteArray) {
    val modified = Files.getLastModifiedTime(file.toPath())
    assertEquals(file.length(), content.size.toLong())
    val replacement = File(file.parentFile, "${file.name}.replacement")
    replacement.writeBytes(content)
    Files.setLastModifiedTime(replacement.toPath(), modified)
    Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    assertEquals(modified, Files.getLastModifiedTime(file.toPath()))
}

class AgentSourceCacheTest {
    @Test
    fun interruptedWaiterDoesNotCancelOwnerOrOtherWaiters() {
        val cache = AgentSourceWorkCache<String, String>(2)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waiterStarted = CountDownLatch(1)
        val waiterInterrupted = AtomicBoolean()
        val ownerInterrupted = AtomicBoolean()
        val work = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        val owner = pool.submit<String> {
            cache.getOrCompute("same") {
                work.incrementAndGet()
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                ownerInterrupted.set(Thread.currentThread().isInterrupted)
                "source"
            }
        }
        val waiter = Thread {
            waiterStarted.countDown()
            try {
                cache.getOrCompute("same") { error("must share owner") }
            } catch (_: InterruptedException) {
                waiterInterrupted.set(true)
            }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            waiter.start()
            assertTrue(waiterStarted.await(5, TimeUnit.SECONDS))
            waiter.interrupt()
            waiter.join(5_000)
            assertFalse(waiter.isAlive)
            assertTrue(waiterInterrupted.get())
            assertFalse(owner.isDone)
            val other = pool.submit<String> { cache.getOrCompute("same") { error("must share owner") } }
            release.countDown()
            assertEquals("source", owner.get(5, TimeUnit.SECONDS))
            assertEquals("source", other.get(5, TimeUnit.SECONDS))
            assertFalse(ownerInterrupted.get())
            assertEquals(1, work.get())
        } finally {
            release.countDown()
            waiter.interrupt()
            waiter.join(5_000)
            pool.shutdownNow()
        }
    }

    @Test
    fun verifiedHash_repeatedStableFileDoesOneContentReadAndPathsStayIsolated() = withAgentSourceCacheRoot { root ->
        val first = File(root, "a/parent.jar").apply { parentFile.mkdirs(); writeText("aaaa") }
        val second = File(root, "b/parent.jar").apply { parentFile.mkdirs(); writeText("bbbb") }
        Files.setLastModifiedTime(second.toPath(), Files.getLastModifiedTime(first.toPath()))
        var reads = 0
        val hashes = AgentVerifiedJarHashes { reads++; AgentModJarReader.sha256(it) }

        repeat(10) { assertEquals(AgentModJarReader.sha256(first), hashes.sha256(File(first.path))) }
        assertEquals(1, reads)
        assertEquals(AgentModJarReader.sha256(second), hashes.sha256(second))
        assertEquals(2, reads)
        assertFalse(hashes.sha256(first) == hashes.sha256(second))
    }

    @Test
    fun verifiedHash_invalidatesInPlaceWriteAndRenameOverEvenWithSameSizeAndMtime() = withAgentSourceCacheRoot { root ->
        val jar = File(root, "parent.jar").apply { writeText("aaaa") }
        var reads = 0
        val hashes = AgentVerifiedJarHashes { reads++; AgentModJarReader.sha256(it) }
        val original = hashes.sha256(jar)
        val modified = Files.getLastModifiedTime(jar.toPath())
        jar.writeText("bbbb")
        Files.setLastModifiedTime(jar.toPath(), modified)
        assertEquals(modified, Files.getLastModifiedTime(jar.toPath()))
        val rewritten = hashes.sha256(jar)
        assertFalse(original == rewritten)
        replaceKeepingSizeAndMtime(jar, "cccc".toByteArray())
        assertFalse(rewritten == hashes.sha256(jar))
        assertEquals(3, reads)
    }

    @Test
    fun verifiedHash_evictionFailureAndConcurrentMutationNeverBecomeHits() = withAgentSourceCacheRoot { root ->
        val first = File(root, "first.jar").apply { writeText("aaaa") }
        val second = File(root, "second.jar").apply { writeText("bbbb") }
        var reads = 0
        val hashes = AgentVerifiedJarHashes(maxEntries = 1) { reads++; AgentModJarReader.sha256(it) }
        hashes.sha256(first)
        hashes.sha256(second)
        hashes.sha256(first)
        assertEquals(3, reads)

        var attempts = 0
        val unstable = AgentVerifiedJarHashes {
            attempts++
            val hash = AgentModJarReader.sha256(it)
            if (attempts == 1) it.appendText("changed")
            hash
        }
        assertTrue(runCatching { unstable.sha256(first) }.isFailure)
        assertEquals(AgentModJarReader.sha256(first), unstable.sha256(first))
        unstable.sha256(first)
        assertEquals(2, attempts)
    }

    @Test
    fun workCache_singleFlightDoesNotSerializeUnrelatedKeysOrCacheFailures() {
        val cache = AgentSourceWorkCache<String, String>(2)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val pool = Executors.newFixedThreadPool(5)
        try {
            val first = pool.submit<String> {
                cache.getOrCompute("same") {
                    count.incrementAndGet(); started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    "source"
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val others = (1..3).map { pool.submit<String> { cache.getOrCompute("same") { count.incrementAndGet(); "wrong" } } }
            assertEquals("independent", pool.submit<String> { cache.getOrCompute("other") { "independent" } }.get(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals("source", first.get(5, TimeUnit.SECONDS))
            others.forEach { assertEquals("source", it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, count.get())
            assertTrue(runCatching { cache.getOrCompute("failed") { error("failed") } }.isFailure)
            assertEquals("retry", cache.getOrCompute("failed") { "retry" })
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun workCache_boundsRetainedWeightAndEntries() {
        val cache = AgentSourceWorkCache<String, String>(maxEntries = 2, maxWeight = 4, weight = { it.length.toLong() })
        var calls = 0
        fun load(key: String, value: String = key) = cache.getOrCompute(key) { calls++; value }
        load("aa"); load("bb"); load("aa")
        assertEquals(2, calls)
        load("cc"); load("bb") // aa was touched, so bb was evicted first.
        assertEquals(4, calls)
        load("huge", "12345"); load("huge", "12345")
        assertEquals(6, calls)
        val entries = AgentSourceWorkCache<String, String>(maxEntries = 1)
        assertEquals("old", entries.getOrCompute("first") { "old" })
        entries.getOrCompute("second") { "other" }
        assertEquals("new", entries.getOrCompute("first") { "new" })
    }
}
