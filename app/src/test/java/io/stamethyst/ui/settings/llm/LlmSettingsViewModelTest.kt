package io.stamethyst.ui.settings.llm

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import io.stamethyst.backend.llm.LlmEndpoint
import io.stamethyst.backend.llm.LlmReasoningEffort
import io.stamethyst.backend.llm.LlmSettings
import io.stamethyst.backend.llm.LlmSettingsPatch
import io.stamethyst.backend.llm.LlmSettingsRepository
import java.lang.reflect.Proxy
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmSettingsViewModelTest {
    private val preferences = Preferences()
    private val context = contextFor(preferences)
    private fun contextFor(preferences: Preferences): Context = object : ContextWrapper(Application()) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            check(name != "llm_settings") { "No Android keystore in this offline fixture" }
            return preferences.value
        }
    }
    private val repository = LlmSettingsRepository(context)
    private val initial = LlmSettings(apiKey = "original-key", models = listOf("original"), modelName = "original")
    private val editors = mutableListOf<Editor>()

    @After fun closeEditors() {
        editors.forEach { it.store.clear(); it.flush() }
    }

    @Test fun staleSearchEditorOnlyChangesTimeoutAfterStandaloneEditorSavesConnectionAndModels() {
        repository.set(initial)
        val search = editor()
        val standalone = editor()
        standalone.model.edit(LlmSettingsPatch(
            apiKey = "new-key", baseUrl = "https://new.invalid/v1", modelName = "new-model",
            models = listOf("new-model", "another-model"), endpoint = LlmEndpoint.RESPONSES,
        ))
        standalone.flush()
        val newConnection = repository.get()

        // Deliberately do not refresh the old VM: the repository must merge the actual edit.
        search.model.edit(LlmSettingsPatch(requestTimeoutSeconds = 600))
        assertEquals(newConnection, repository.get()) // Still debounced.
        search.flush()
        assertEquals(newConnection.copy(requestTimeoutSeconds = 600), repository.get())

        standalone.model.edit(LlmSettingsPatch(reasoningEffort = LlmReasoningEffort.MAX))
        standalone.flush()
        assertEquals(newConnection.copy(requestTimeoutSeconds = 600, reasoningEffort = LlmReasoningEffort.MAX), repository.get())
    }

    @Test fun latestSameFieldEditWinsEvenWhenOlderWriterFinishesLast() {
        repository.set(initial)
        val first = editor()
        val second = editor()
        first.model.edit(LlmSettingsPatch(requestTimeoutSeconds = 600))
        second.model.edit(LlmSettingsPatch(requestTimeoutSeconds = 1200))
        second.flush()
        first.flush()
        assertEquals(1200, repository.get().requestTimeoutSeconds)

        // Selecting the old VM's displayed value is still a new, explicit user edit.
        first.model.edit(LlmSettingsPatch(requestTimeoutSeconds = 600))
        first.flush()
        assertEquals(600, repository.get().requestTimeoutSeconds)
    }

    @Test fun pendingPatchesMergeRatherThanConflateDifferentFields() {
        repository.set(initial)
        val editor = editor()
        val before = preferences.commits
        editor.model.edit(LlmSettingsPatch(apiKey = " updated-key "))
        editor.model.edit(LlmSettingsPatch(requestTimeoutSeconds = 60))
        editor.model.edit(LlmSettingsPatch(requestTimeoutSeconds = 180))
        editor.model.edit(LlmSettingsPatch(supportsImageInput = false))
        editor.model.addModels(listOf("added", "added"))
        editor.flush()
        assertEquals(initial.copy(
            apiKey = "updated-key", requestTimeoutSeconds = 180, supportsImageInput = false,
            models = listOf("original", "added"),
        ), repository.get())
        assertEquals(before + 1, preferences.commits)
    }

    @Test fun stalePendingKeyAndModelsCannotOverwriteNewerEditsWhenTimeoutIsFlushed() {
        repository.set(initial)
        val search = editor()
        val standalone = editor()
        search.model.edit(LlmSettingsPatch(apiKey = "older-key", modelName = "older", models = listOf("older")))
        search.model.edit(LlmSettingsPatch(requestTimeoutSeconds = 600))
        standalone.model.edit(LlmSettingsPatch(apiKey = "latest-key", modelName = "latest", models = listOf("latest")))
        standalone.flush()
        search.flush()
        assertEquals(initial.copy(
            apiKey = "latest-key", modelName = "latest", models = listOf("latest"), requestTimeoutSeconds = 600,
        ), repository.get())
    }

    @Test fun emptyKeyKeepsLatestSavedKeyAndReentryDoesNotResubmitAnOldDraft() {
        repository.set(initial)
        val search = editor()
        val standalone = editor()
        standalone.model.edit(LlmSettingsPatch(apiKey = "latest-key", models = listOf("latest"), modelName = "latest"))
        standalone.flush()
        val expected = repository.get()
        val commits = preferences.commits

        search.model.edit(LlmSettingsPatch(apiKey = "  "))
        repeat(3) { search.model.refreshSettings() }
        assertEquals(expected, search.model.settings)
        search.store.clear()
        search.flush()
        assertEquals(expected, repository.get())
        assertEquals(commits, preferences.commits)
    }

    @Test fun navigationClearingViewModelStillDrainsPendingFields() {
        repository.set(initial)
        val editor = editor()
        editor.model.edit(LlmSettingsPatch(baseUrl = "https://leave.invalid/v1/"))
        editor.model.edit(LlmSettingsPatch(requestTimeoutSeconds = 600))
        editor.store.clear()
        assertEquals(initial, repository.get())
        editor.flush()
        assertEquals(initial.copy(baseUrl = "https://leave.invalid/v1", requestTimeoutSeconds = 600), repository.get())
    }

    @Test fun clearingKeyBeforeDebounceDiscardsOnlyThisEditorsUncommittedKey() {
        repository.set(initial)
        val first = editor()
        val second = editor()
        first.model.edit(LlmSettingsPatch(apiKey = "unfinished", requestTimeoutSeconds = 600))
        first.model.edit(LlmSettingsPatch(apiKey = ""))
        first.flush()
        assertEquals(initial.copy(requestTimeoutSeconds = 600), repository.get())

        first.model.edit(LlmSettingsPatch(apiKey = "stale-pending"))
        second.model.edit(LlmSettingsPatch(apiKey = "newer-pending"))
        first.model.edit(LlmSettingsPatch(apiKey = "  "))
        second.flush()
        first.flush()
        assertEquals("newer-pending", repository.get().apiKey)
    }

    @Test fun editsArrivingDuringIoStayVisibleAndAreSavedByTheNextWrite() {
        repository.set(initial)
        val io = QueuedDispatcher()
        val editor = editor(io)
        editor.model.edit(LlmSettingsPatch(requestTimeoutSeconds = 600))
        editor.flush() // First batch is waiting for its IO worker.
        editor.model.edit(LlmSettingsPatch(requestTimeoutSeconds = 1200, apiKey = "during-io"))
        io.runNext()
        assertEquals(1200, editor.model.settings.requestTimeoutSeconds)
        assertEquals("during-io", editor.model.settings.apiKey)
        editor.flush()
        io.runNext()
        assertEquals(initial.copy(requestTimeoutSeconds = 1200, apiKey = "during-io"), repository.get())
        assertTrue(io.tasks.isEmpty())
    }

    @Test fun retryDoesNotReclaimFieldsChangedByAnotherEditorAfterSaveFailure() {
        repository.set(initial)
        val first = editor()
        val second = editor()
        preferences.failCommits = true
        first.model.edit(LlmSettingsPatch(apiKey = "failed-key", requestTimeoutSeconds = 600))
        first.flush()
        assertTrue(first.model.saveError)
        preferences.failCommits = false
        second.model.edit(LlmSettingsPatch(apiKey = "new-key"))
        second.flush()
        val commits = preferences.commits
        first.model.retrySave()
        first.flush()
        assertFalse(first.model.saveError)
        assertEquals(initial.copy(apiKey = "new-key", requestTimeoutSeconds = 600), repository.get())
        assertEquals(repository.get(), LlmSettingsRepository(contextFor(preferences.reopen())).get())
        assertEquals("The other editor's successful snapshot commit already persisted the timeout", commits, preferences.commits)
        first.model.retrySave()
        first.flush()
        assertEquals(commits, preferences.commits)
    }

    @Test fun failedCommitUpdatingMemoryIsRetriedUntilTheEditReachesDisk() {
        repository.set(initial)
        val editor = editor()
        val expected = initial.copy(apiKey = "retry-key", requestTimeoutSeconds = 600)
        preferences.failCommits = true
        editor.model.edit(LlmSettingsPatch(apiKey = expected.apiKey, requestTimeoutSeconds = 600))
        editor.flush()
        assertTrue(editor.model.saveError)
        assertEquals("SharedPreferences updates memory even when commit returns false", expected, repository.get())
        assertEquals(initial, LlmSettingsRepository(contextFor(preferences.reopen())).get())

        val afterFailure = preferences.commits
        editor.model.retrySave()
        editor.flush()
        assertEquals(afterFailure + 1, preferences.commits)
        assertTrue("A second disk failure must remain visible", editor.model.saveError)
        assertEquals(initial, LlmSettingsRepository(contextFor(preferences.reopen())).get())

        preferences.failCommits = false
        editor.model.retrySave()
        editor.flush()
        assertEquals(afterFailure + 2, preferences.commits)
        assertFalse(editor.model.saveError)
        assertEquals(expected, LlmSettingsRepository(contextFor(preferences.reopen())).get())
        editor.model.retrySave()
        editor.flush()
        assertEquals("An empty retry after success must not write again", afterFailure + 2, preferences.commits)
    }

    @Test fun unchangedFirstEditAndEmptyRetryDoNotCommit() {
        repository.set(initial)
        val editor = editor()
        val commits = preferences.commits
        editor.model.edit(LlmSettingsPatch(apiKey = initial.apiKey, requestTimeoutSeconds = initial.requestTimeoutSeconds))
        editor.flush()
        editor.model.retrySave()
        editor.flush()
        assertEquals(commits, preferences.commits)
        assertFalse(editor.model.saveError)
    }

    @Test fun newerSameValueEditStillPersistsAnEarlierFailedMemoryOnlyValue() {
        repository.set(initial)
        val first = editor()
        val second = editor()
        preferences.failCommits = true
        first.model.edit(LlmSettingsPatch(apiKey = "memory-only"))
        first.flush()
        assertTrue(first.model.saveError)
        assertEquals(initial, LlmSettingsRepository(contextFor(preferences.reopen())).get())
        val afterFailure = preferences.commits

        preferences.failCommits = false
        second.model.edit(LlmSettingsPatch(apiKey = "memory-only"))
        second.flush()
        assertEquals(afterFailure + 1, preferences.commits)
        assertEquals(initial.copy(apiKey = "memory-only"), LlmSettingsRepository(contextFor(preferences.reopen())).get())
        first.model.retrySave()
        first.flush()
        assertEquals(afterFailure + 1, preferences.commits)
    }

    @Test fun supersededFailedEditDoesNotWriteOnBehalfOfAnotherEditorsPendingEdit() {
        repository.set(initial)
        val first = editor()
        val second = editor()
        preferences.failCommits = true
        first.model.edit(LlmSettingsPatch(apiKey = "failed-old-key"))
        first.flush()
        second.model.edit(LlmSettingsPatch(apiKey = "new-key"))
        val afterFailure = preferences.commits

        preferences.failCommits = false
        first.model.retrySave()
        first.flush()
        assertEquals(afterFailure, preferences.commits)
        assertEquals(initial, LlmSettingsRepository(contextFor(preferences.reopen())).get())
        second.flush()
        assertEquals(initial.copy(apiKey = "new-key"), LlmSettingsRepository(contextFor(preferences.reopen())).get())
    }

    @Test fun rapidModelListActionsUsePendingListAndPreserveLatestConnection() {
        repository.set(initial)
        val first = editor()
        val second = editor()
        second.model.edit(LlmSettingsPatch(apiKey = "new-key"))
        second.model.addModels(listOf("remote"))
        second.flush()
        first.model.addModels(listOf("custom-a"))
        first.model.addModels(listOf("custom-b"))
        first.model.removeModel("original")
        first.flush()
        assertEquals(initial.copy(
            apiKey = "new-key", modelName = "remote", models = listOf("remote", "custom-a", "custom-b"),
        ), repository.get())
        first.model.edit(LlmSettingsPatch(models = emptyList(), modelName = ""))
        first.flush()
        assertEquals(emptyList<String>(), repository.get().models)
        assertEquals("", repository.get().modelName)
    }

    private fun editor(ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined): Editor = Editor(ioDispatcher).also(editors::add)

    private inner class Editor(ioDispatcher: CoroutineDispatcher) {
        private val debounce = Channel<Unit>(Channel.UNLIMITED)
        val model = LlmSettingsViewModel(
            LlmSettingsRepository(context),
            writeDispatcher = Dispatchers.Unconfined,
            ioDispatcher = ioDispatcher,
            awaitDebounce = { debounce.receive() },
        )
        val store = ViewModelStore().apply { put("settings", model) }
        fun flush() { check(debounce.trySend(Unit).isSuccess) }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runNext() { tasks.removeFirst().run() }
    }

    /** Android commits update memory before disk, including when the disk write fails. */
    private class Preferences(private val disk: MutableMap<String, Any?> = mutableMapOf()) {
        private val values = disk.toMutableMap()
        var commits = 0
        var failCommits = false
        fun reopen() = Preferences(disk)
        private fun editor(): SharedPreferences.Editor {
            val staged = mutableMapOf<String, Any?>()
            var clear = false
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when {
                    method.name.startsWith("put") -> { staged[args[0] as String] = args[1]; editor }
                    method.name == "clear" -> { clear = true; editor }
                    method.name == "remove" -> { staged[args[0] as String] = null; editor }
                    method.name == "commit" || method.name == "apply" -> {
                        if (clear) values.clear()
                        staged.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        commits++
                        if (failCommits) false else {
                            disk.clear()
                            disk.putAll(values)
                            true
                        }
                    }
                    else -> null
                }
            } as SharedPreferences.Editor
            return editor
        }
        val value = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor()
                "getAll" -> values.toMap()
                "contains" -> values.containsKey(args[0])
                "getString", "getInt", "getLong", "getBoolean", "getFloat", "getStringSet" -> values[args[0]] ?: args[1]
                else -> null
            }
        } as SharedPreferences
    }
}
