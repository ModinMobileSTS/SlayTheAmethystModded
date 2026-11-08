package io.stamethyst.ui.settings.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRefreshTaskTest {
    @Test
    fun blockedStatusDomainCannotDelaySavedConfiguration() {
        val configurationExecutor = Executors.newSingleThreadExecutor()
        val statusExecutor = Executors.newSingleThreadExecutor()
        val releaseStatus = CountDownLatch(1)
        val statusStarted = CountDownLatch(1)
        val configurationPublished = CountDownLatch(1)
        val statusPublished = CountDownLatch(1)
        try {
            SettingsRefreshTask(statusExecutor).submit(
                dispatch = { it() },
                load = {
                    statusStarted.countDown()
                    assertTrue(releaseStatus.await(5, TimeUnit.SECONDS))
                    "status"
                },
                onSuccess = { statusPublished.countDown() },
                onFailure = { throw AssertionError(it) },
            )
            assertTrue(statusStarted.await(5, TimeUnit.SECONDS))
            SettingsRefreshTask(configurationExecutor).submit(
                dispatch = { it() },
                load = { "saved value, not default" },
                onSuccess = {
                    assertEquals("saved value, not default", it)
                    configurationPublished.countDown()
                },
                onFailure = { throw AssertionError(it) },
            )
            assertTrue(configurationPublished.await(5, TimeUnit.SECONDS))
            assertEquals(1L, statusPublished.count)
            releaseStatus.countDown()
            assertTrue(statusPublished.await(5, TimeUnit.SECONDS))
        } finally {
            releaseStatus.countDown()
            configurationExecutor.shutdownNow()
            statusExecutor.shutdownNow()
        }
    }

    @Test
    fun queuedOldResultCannotOverwriteNewSetting() {
        val executor = Executors.newSingleThreadExecutor()
        val callbacks = LinkedBlockingQueue<() -> Unit>()
        val published = mutableListOf<String>()
        try {
            val task = SettingsRefreshTask(executor)
            task.submit(callbacks::add, { "old" }, published::add) { throw AssertionError(it) }
            val oldCallback = callbacks.poll(5, TimeUnit.SECONDS)
            assertNotNull(oldCallback)
            task.submit(callbacks::add, { "new" }, published::add) { throw AssertionError(it) }
            val newCallback = callbacks.poll(5, TimeUnit.SECONDS)
            assertNotNull(newCallback)
            newCallback!!.invoke()
            oldCallback!!.invoke()
            assertEquals(listOf("new"), published)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun failedDomainCanBeRetriedWithoutResettingOtherValues() {
        val executor = Executors.newSingleThreadExecutor()
        val callbacks = LinkedBlockingQueue<() -> Unit>()
        var state = SettingsLoadState.LOADING
        var savedValue = "custom"
        try {
            val task = SettingsRefreshTask(executor)
            task.submit<String>(callbacks::add, { error("resource unavailable") }, {
                savedValue = it
            }, { state = SettingsLoadState.ERROR })
            callbacks.poll(5, TimeUnit.SECONDS)!!.invoke()
            assertEquals(SettingsLoadState.ERROR, state)
            assertEquals("custom", savedValue)
            task.submit(callbacks::add, { "ready" }, {
                state = SettingsLoadState.READY
            }, { throw AssertionError(it) })
            callbacks.poll(5, TimeUnit.SECONDS)!!.invoke()
            assertEquals(SettingsLoadState.READY, state)
            assertEquals("custom", savedValue)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun cancelInvalidatesAnAlreadyQueuedCallback() {
        val executor = Executors.newSingleThreadExecutor()
        val callbacks = LinkedBlockingQueue<() -> Unit>()
        var published = false
        try {
            val task = SettingsRefreshTask(executor)
            task.submit(callbacks::add, { "old account" }, { published = true }, {})
            val callback = callbacks.poll(5, TimeUnit.SECONDS)!!
            task.cancel()
            callback()
            assertEquals(false, published)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun cancelledWorkerCannotPublishEvenIfItIgnoresInterruption() {
        val executor = Executors.newSingleThreadExecutor()
        val callbacks = LinkedBlockingQueue<() -> Unit>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = mutableListOf<String>()
        try {
            val task = SettingsRefreshTask(executor)
            task.submit(callbacks::add, {
                started.countDown()
                var waiting = true
                while (waiting) {
                    try {
                        release.await()
                        waiting = false
                    } catch (_: InterruptedException) {
                        // Some storage/network libraries swallow cancellation interrupts.
                    }
                }
                "old account"
            }, published::add, { throw AssertionError(it) })
            assertTrue(started.await(5, TimeUnit.SECONDS))
            task.submit(callbacks::add, { "new account" }, published::add) { throw AssertionError(it) }
            release.countDown()
            callbacks.poll(5, TimeUnit.SECONDS)!!.invoke()
            callbacks.poll(5, TimeUnit.SECONDS)!!.invoke()
            assertEquals(listOf("new account"), published)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun onlyActualDependenciesAffectAnOptionLoadingState() {
        val state = SettingsScreenViewModel.UiState(loadStates = mapOf(
            SettingsLoadDomain.CONFIGURATION to SettingsLoadState.READY,
            SettingsLoadDomain.STEAM_ACCOUNT to SettingsLoadState.LOADING,
            SettingsLoadDomain.RESOURCES to SettingsLoadState.ERROR,
        ))
        assertEquals(SettingsLoadState.READY, state.loadState(SettingsLoadDomain.CONFIGURATION))
        assertEquals(SettingsLoadState.LOADING, state.loadState(
            SettingsLoadDomain.CONFIGURATION, SettingsLoadDomain.STEAM_ACCOUNT,
        ))
        assertEquals(SettingsLoadState.ERROR, state.loadState(SettingsLoadDomain.RESOURCES))
    }
}
