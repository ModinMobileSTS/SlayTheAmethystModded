package io.stamethyst.backend.steamcloud

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two real presence loops use one production ownership gate and the same simulated CM identity. */
class SteamGamePresenceOwnershipTest {
    @Test
    fun delayedOldCleanupCannotClearANewInstancesAlreadyReportedGame() {
        val ownership = SteamGamePresenceOwnership()
        val cm = SharedPresence()
        val old = Worker("old", ownership.claim(), cm)
        var replacement: Worker? = null
        try {
            old.start()
            await(old.waiting, "old worker idle after upload")
            old.active.set(false) // Stop requested, but the old worker has not unwound yet.

            val new = Worker("new", ownership.claim(), cm).also { replacement = it }
            new.start()
            await(new.waiting, "new worker uploaded on the same CM session")
            old.resume.countDown()
            old.finish()

            assertEquals(false, old.closed.get())
            assertEquals(1, old.closeCalls.get())
            assertEquals(646570L, cm.appId)
            assertEquals("new", cm.detailOwner)
            assertTrue(new.owner.isCurrent)
            assertFalse(cm.snapshot().contains("old:game:0"))
            assertEquals(1, cm.snapshot().count { it == "new:game:646570" })
        } finally {
            old.stop()
            replacement?.stop()
            old.finish()
            replacement?.finish()
        }
    }

    @Test
    fun claimDoesNotWaitForOldCleanupButNewUploadWaitsUntilItsClearFinishes() {
        val ownership = SteamGamePresenceOwnership()
        val cm = SharedPresence()
        val enteredClear = CountDownLatch(1)
        val allowClear = CountDownLatch(1)
        val old = Worker("old", ownership.claim(), cm).apply {
            beforeClear = {
                enteredClear.countDown() // Ownership was checked; the worker gate is still held.
                await(allowClear, "allow old clearing RPC")
            }
        }
        var replacement: Worker? = null
        try {
            old.start()
            await(old.waiting, "old worker uploaded")
            old.stop()
            await(enteredClear, "old cleanup entered the gate")

            // Models onStartCommand: claiming must complete while worker-side network IO is held.
            val claim = FutureTask { ownership.claim() }
            Thread(claim, "presence-test-main-claim").apply { isDaemon = true; start() }
            val new = Worker("new", claim.get(3, TimeUnit.SECONDS), cm).also { replacement = it }
            new.start()
            await(new.created, "new worker reached its first guarded send")
            assertFalse("New upload must wait for the old clear", new.uploaded.await(100, TimeUnit.MILLISECONDS))

            allowClear.countDown()
            old.finish()
            await(new.waiting, "new upload after old clear")

            val events = cm.snapshot()
            assertEquals(1, events.count { it == "old:game:0" })
            assertTrue(events.indexOf("old:game:0") < events.indexOf("new:game:646570"))
            assertEquals(646570L, cm.appId)
            assertEquals("new", cm.detailOwner)
            assertTrue(new.owner.isCurrent) // Old completion cannot release the new claim.
        } finally {
            allowClear.countDown()
            old.stop()
            replacement?.stop()
            old.finish()
            replacement?.finish()
        }
    }

    @Test
    fun oldWorkerStartingLateCannotReclaimOwnershipFromTheNewInstance() {
        val ownership = SteamGamePresenceOwnership()
        val cm = SharedPresence()
        val old = Worker("old", ownership.claim(), cm) // Deliberately do not start its thread yet.
        val new = Worker("new", ownership.claim(), cm)
        try {
            new.start()
            await(new.waiting, "new worker uploaded first")
            old.start()
            old.finish()

            assertEquals(0, old.authReads.get())
            assertEquals(0, old.clientCreations.get())
            assertTrue(cm.snapshot().none { it.startsWith("old:") })
            assertEquals(646570L, cm.appId)
            assertTrue(new.owner.isCurrent)
        } finally {
            old.stop()
            new.stop()
            old.finish()
            new.finish()
        }
    }

    @Test
    fun oldSynchronizationAlreadyPastItsLoopCheckCannotSendAfterNewUpload() {
        val ownership = SteamGamePresenceOwnership()
        val cm = SharedPresence()
        val oldReadPaused = CountDownLatch(1)
        val releaseOldRead = CountDownLatch(1)
        val old = Worker("old", ownership.claim(), cm).apply {
            onAwaitChange = {} // Enter another synchronization on the same session identity.
            onPresenceRead = { read ->
                if (read == 2) {
                    oldReadPaused.countDown()
                    await(releaseOldRead, "resume old synchronization")
                }
            }
        }
        var replacement: Worker? = null
        try {
            old.start()
            await(oldReadPaused, "old worker passed its loop ownership check")
            val new = Worker("new", ownership.claim(), cm).also { replacement = it }
            new.start()
            await(new.waiting, "new worker uploaded")
            releaseOldRead.countDown()
            old.finish()

            assertEquals(1, cm.snapshot().count { it == "old:details" })
            assertEquals(false, old.closed.get())
            assertEquals(646570L, cm.appId)
            assertEquals("new", cm.detailOwner)
        } finally {
            releaseOldRead.countDown()
            old.stop()
            replacement?.stop()
            old.finish()
            replacement?.finish()
        }
    }

    @Test
    fun oldUnstartedWorkerDoesNotReviveAfterNewOwnerHasAlreadyFinished() {
        val ownership = SteamGamePresenceOwnership()
        val cm = SharedPresence()
        val old = Worker("old", ownership.claim(), cm)
        val new = Worker("new", ownership.claim(), cm)
        try {
            new.start()
            await(new.waiting, "new worker uploaded")
            new.stop()
            new.finish()
            old.start()
            old.finish()

            assertFalse(old.owner.isCurrent)
            assertFalse(new.owner.isCurrent)
            assertEquals(0, old.clientCreations.get())
            assertEquals(0L, cm.appId)
            assertEquals(1, cm.snapshot().count { it == "new:game:0" })
            assertTrue(cm.snapshot().none { it.startsWith("old:") })
        } finally {
            old.stop()
            new.stop()
            old.finish()
            new.finish()
        }
    }

    private class SharedPresence {
        val sessionIdentity = Any()
        @Volatile var appId = 0L
        @Volatile var detailOwner = ""
        val events = Collections.synchronizedList(mutableListOf<String>())
        fun snapshot(): List<String> = synchronized(events) { events.toList() }
    }

    private class Worker(val name: String, val owner: SteamGamePresenceOwnership.Owner, val cm: SharedPresence) {
        val active = AtomicBoolean(true)
        val created = CountDownLatch(1)
        val uploaded = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val done = CountDownLatch(1)
        val closed = AtomicReference<Boolean?>()
        val closeCalls = AtomicInteger()
        val authReads = AtomicInteger()
        val clientCreations = AtomicInteger()
        private val failure = AtomicReference<Throwable?>()
        private var presenceReads = 0
        var beforeClear: () -> Unit = {}
        var onPresenceRead: (Int) -> Unit = {}
        var onAwaitChange: () -> Unit = { await(resume, "$name resume") }
        private val loop = SteamGamePresenceLoop(
            owner = owner,
            readAuth = { expectedEpoch ->
                authReads.incrementAndGet()
                SteamCloudAuthStore.readCmAuthMaterialWithStatus(expectedEpoch, { "epoch" }, {
                    "epoch" to SteamCloudAuthStore.SavedAuthMaterial("account", "token", "", "76561198883607238", 1)
                })
            },
            readPresence = {
                onPresenceRead(++presenceReads)
                mapOf("status" to "$name:$presenceReads")
            },
            createClient = {
                clientCreations.incrementAndGet()
                created.countDown()
                Client()
            },
            shouldContinue = { active.get() },
            nowMs = { 0L },
            awaitChange = { waiting.countDown(); onAwaitChange() },
            awaitRetry = { throw AssertionError("No retry expected") },
            onRestored = { uploaded.countDown() },
            onRetry = { throw AssertionError("Unexpected presence failure", it) },
        )
        private val thread = Thread({
            try {
                loop.run()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }, "presence-test-$name").apply { isDaemon = true }

        fun start() = thread.start()
        fun stop() { active.set(false); resume.countDown() }
        fun finish() {
            if (thread.state == Thread.State.NEW) return
            await(done, "$name worker finished")
            thread.join(3_000L)
            assertFalse("$name worker must terminate", thread.isAlive)
            failure.get()?.let { throw AssertionError("$name worker failed", it) }
        }

        private inner class Client : SteamGamePresenceLoop.Client {
            private var loggedOn = false
            override val sessionIdentity: Any get() = cm.sessionIdentity
            override fun logOn(auth: SteamCloudAuthStore.SavedAuthMaterial) {
                loggedOn = true
                cm.events += "$name:logon"
            }
            override fun setPersonaOnline() { cm.events += "$name:persona" }
            override fun setGamePlayedAppId(appId: Long) {
                cm.appId = appId
                cm.events += "$name:game:$appId"
            }
            override fun setRichPresence(presence: Map<String, String>) {
                cm.detailOwner = name
                cm.events += "$name:details"
            }
            override fun close(clearState: Boolean) {
                closeCalls.incrementAndGet()
                if (clearState && loggedOn) {
                    beforeClear()
                    setGamePlayedAppId(0L)
                }
                closed.set(clearState)
                cm.events += "$name:close:$clearState"
            }
        }
    }

    private companion object {
        fun await(latch: CountDownLatch, label: String) {
            assertTrue("Timed out: $label", latch.await(3, TimeUnit.SECONDS))
        }
    }
}
