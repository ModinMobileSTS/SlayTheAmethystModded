package io.stamethyst.backend.llm

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class LlmSettingsRepositoryTest {
    @Test fun availablePresetsAndSelectionSurviveRestart() {
        val encrypted = Preferences()
        val fallback = Preferences()
        val repository = repository(encrypted, fallback)
        val expected = LlmSettings(reasoningEffort = LlmReasoningEffort.XHIGH,
            availableReasoningEfforts = listOf(LlmReasoningEffort.MEDIUM, LlmReasoningEffort.XHIGH))
        repository.set(expected)
        assertEquals(expected, repository(encrypted.reopen(), fallback.reopen()).get())
    }

    @Test fun queuedEffortCannotReintroduceAnUnselectedPreset() {
        val repository = repository(Preferences(), Preferences())
        val oldEffort = repository.newEdit(LlmSettingsPatch(reasoningEffort = LlmReasoningEffort.MAX))!!
        val options = repository.newEdit(LlmSettingsPatch(availableReasoningEfforts = listOf(LlmReasoningEffort.MEDIUM)))!!
        repository.update(listOf(options))
        val actual = repository.update(listOf(oldEffort))
        assertEquals(listOf(LlmReasoningEffort.MEDIUM), actual.availableReasoningEfforts)
        assertEquals(LlmReasoningEffort.MEDIUM, actual.reasoningEffort)
    }

    private val old = LlmSettings(
        apiKey = "old-key", baseUrl = "https://old.invalid/v1", modelName = "old-model",
        models = listOf("old-model", "another"), endpoint = LlmEndpoint.RESPONSES,
        requestTimeoutSeconds = 600, reasoningEffort = LlmReasoningEffort.HIGH, supportsImageInput = false,
    )
    private val updated = old.copy(apiKey = "new-key", baseUrl = "https://new.invalid/v1")

    @Test fun emptyEncryptedStoreDoesNotHidePopulatedFallback() {
        val encrypted = Preferences()
        val fallback = saved(old)
        assertEquals(old, repository(encrypted, fallback).get())
        assertEquals(old, repository(encrypted.reopen(), fallback.reopen()).get())
    }

    @Test fun encryptedMetadataOnlyDoesNotCountAsSettings() {
        val encrypted = Preferences(mutableMapOf("crypto-keyset" to "not-LLM-settings"))
        assertEquals(old, repository(encrypted, saved(old)).get())
    }

    @Test fun emptyStoresReturnDefaultsWithoutWriting() {
        val encrypted = Preferences()
        val fallback = Preferences()
        assertEquals(LlmSettings(), repository(encrypted, fallback).get())
        assertEquals(0, encrypted.commits + fallback.commits)
    }

    @Test fun legacyEncryptedSettingsRemainReadableAndKeepAllFields() {
        val encrypted = saved(old)
        val fallback = Preferences()
        assertEquals(old, repository(encrypted, fallback).get())
        assertEquals(0, encrypted.commits + fallback.commits)
    }

    @Test fun legacyFallbackSnapshotTakesPrecedenceOverOldEncryptedSnapshot() {
        assertEquals(updated, repository(saved(old), saved(updated)).get())
    }

    @Test fun failedEncryptedWriteUsesFallbackAcrossReadersAndRestart() {
        val encrypted = saved(old).apply { throwOnWrite = true }
        val fallback = Preferences()
        val repository = repository(encrypted, fallback)
        repository.set(updated)

        assertEquals(updated, repository.get())
        assertEquals(updated, repository(encrypted, fallback).get())
        assertEquals(updated, repository(encrypted.reopen(), fallback.reopen()).get())
        assertEquals("old-key", encrypted.disk["api_key"])
        assertEquals("new-key", fallback.disk["api_key"])
    }

    @Test fun encryptedCommitFailureUpdatingMemoryStillPersistsFallbackForRestart() {
        val encrypted = saved(old).apply { failCommits = true }
        val fallback = Preferences()
        repository(encrypted, fallback).set(updated)
        assertEquals("new-key", encrypted.value.getString("api_key", null))
        assertEquals("old-key", encrypted.disk["api_key"])
        assertEquals(updated, repository(encrypted.reopen(), fallback.reopen()).get())
    }

    @Test fun recoveringEncryptedStoreDoesNotResurrectItsOldSnapshot() {
        val encrypted = saved(old).apply { throwOnWrite = true }
        val fallback = Preferences()
        repository(encrypted, fallback).set(updated)
        encrypted.throwOnWrite = false

        val recovered = repository(encrypted.reopen(), fallback.reopen())
        assertEquals(updated, recovered.get())
        val edit = recovered.newEdit(LlmSettingsPatch(requestTimeoutSeconds = 1200))!!
        val expected = updated.copy(requestTimeoutSeconds = 1200)
        assertEquals(expected, recovered.update(listOf(edit)))
        assertEquals(expected, repository(encrypted.reopen(), fallback.reopen()).get())
        assertFalse("A successful encrypted save removes the plaintext key", fallback.disk.containsKey("api_key"))
        assertFalse(fallback.disk.containsKey("base_url"))
    }

    @Test fun successfulEncryptedSaveClearsOnlyFallbackSettingsAndSurvivesReload() {
        val encrypted = saved(old)
        val fallback = saved(updated)
        fallback.disk["unrelated"] = "keep"
        val actualFallback = fallback.reopen()
        repository(encrypted, actualFallback).set(old)

        assertEquals(old, repository(encrypted.reopen(), actualFallback.reopen()).get())
        assertFalse(actualFallback.disk.containsKey("api_key"))
        assertEquals("keep", actualFallback.disk["unrelated"])
    }

    @Test fun fallbackSelectionSurvivesEncryptedStorageBecomingAvailableLater() {
        val encrypted = saved(old)
        val fallback = Preferences()
        repository(null, fallback).set(updated)
        assertEquals(updated, repository(encrypted, fallback.reopen()).get())
    }

    @Test fun selectorCommitFailureIsReportedAndRetryPersistsTheSwitch() {
        val encrypted = saved(old)
        val fallback = Preferences()
        repository(null, fallback).set(updated)
        val repository = repository(encrypted, fallback)
        fallback.failCommits = true
        val edit = repository.newEdit(LlmSettingsPatch(requestTimeoutSeconds = 1200))!!
        assertThrows(IllegalStateException::class.java) { repository.update(listOf(edit)) }
        // The old selector remains on disk, so a failed save must not be reported as successful.
        assertEquals(updated, repository(encrypted.reopen(), fallback.reopen()).get())

        fallback.failCommits = false
        val expected = updated.copy(requestTimeoutSeconds = 1200)
        assertEquals(expected, repository.update(listOf(edit)))
        assertEquals(expected, repository(encrypted.reopen(), fallback.reopen()).get())
    }

    @Test fun selectorWriteExceptionKeepsOldFallbackReadableAndRetryWorks() {
        val encrypted = saved(old)
        val fallback = Preferences()
        repository(null, fallback).set(updated)
        val repository = repository(encrypted, fallback)
        fallback.throwOnWrite = true
        assertThrows(IllegalStateException::class.java) { repository.set(old) }
        assertEquals(updated, repository.get())

        fallback.throwOnWrite = false
        repository.set(old)
        assertEquals(old, repository(encrypted.reopen(), fallback.reopen()).get())
    }

    @Test fun bothWritesFailWithoutReportingSuccessOrLosingDiskSnapshot() {
        val encrypted = saved(old).apply { throwOnWrite = true }
        val fallback = Preferences().apply { failCommits = true }
        assertThrows(IllegalStateException::class.java) { repository(encrypted, fallback).set(updated) }
        assertEquals(old, repository(encrypted.reopen(), fallback.reopen()).get())
    }

    @Test fun unreadableEncryptedSnapshotFallsBackToReadableSettings() {
        assertEquals(updated, repository(saved(old).apply { throwOnRead = true }, saved(updated)).get())
    }

    @Test fun unreadableFallbackDoesNotPreventReadingEncryptedSettings() {
        assertEquals(old, repository(saved(old), saved(updated).apply { throwOnRead = true }).get())
    }

    @Test fun explicitEncryptedSelectionWinsOverResidualFallbackFields() {
        val encrypted = saved(old)
        val fallback = Preferences()
        repository(encrypted, fallback).set(updated)
        val durable = fallback.reopen()
        durable.value.edit().putString("api_key", "stale-recovery-key").commit()

        assertEquals(updated, repository(encrypted.reopen(), durable.reopen()).get())
    }

    @Test fun encryptedSelectionStillRecoversWhenEncryptedSnapshotCannotBeRead() {
        val encrypted = saved(old)
        val fallback = Preferences()
        repository(encrypted, fallback).set(updated)
        fallback.value.edit().putString("api_key", "recovery-key").commit()
        encrypted.throwOnRead = true

        assertEquals("recovery-key", repository(encrypted, fallback).get().apiKey)
    }

    @Test fun unrecognizedSelectionKeepsLegacyFallbackRecoveryBehaviour() {
        val fallback = saved(updated)
        fallback.value.edit().putString("active_store", "unrecognized-future-value").commit()
        assertEquals(updated, repository(saved(old), fallback.reopen()).get())
    }

    @Test fun partialLegacySettingsRetainDefaultsForMissingFields() {
        val encrypted = Preferences(mutableMapOf("api_key" to "legacy-key", "model_name" to "legacy-model"))
        assertEquals(LlmSettings(
            apiKey = "legacy-key", modelName = "legacy-model", models = listOf("legacy-model"),
        ), repository(encrypted, Preferences()).get())
    }

    private fun repository(encrypted: Preferences?, fallback: Preferences): LlmSettingsRepository {
        val context = object : ContextWrapper(Application()) {
            override fun getApplicationContext(): Context = this
        }
        return LlmSettingsRepository(context).also { repository ->
            // Inject at the preference boundary; no fake Android Keystore behaviour is required.
            mapOf("encryptedPrefs\$delegate" to encrypted?.value, "fallbackPrefs\$delegate" to fallback.value)
                .forEach { (field, prefs) ->
                    LlmSettingsRepository::class.java.getDeclaredField(field).apply { isAccessible = true }
                        .set(repository, lazyOf(prefs))
                }
        }
    }

    private fun saved(settings: LlmSettings) = Preferences(mutableMapOf(
        "api_key" to settings.apiKey, "base_url" to settings.baseUrl, "model_name" to settings.modelName,
        "models" to org.json.JSONArray(settings.models).toString(), "endpoint" to settings.endpoint.name,
        "request_timeout_seconds" to settings.requestTimeoutSeconds, "reasoning_effort" to settings.reasoningEffort.name,
        "supports_image_input" to settings.supportsImageInput,
    ))

    /** Models commit's memory-before-disk behaviour; reopening shares only the durable snapshot. */
    private class Preferences(val disk: MutableMap<String, Any?> = mutableMapOf()) {
        private val values = disk.toMutableMap()
        var throwOnRead = false
        var throwOnWrite = false
        var failCommits = false
        var commits = 0
        fun reopen() = Preferences(disk)
        private fun editor(): SharedPreferences.Editor {
            check(!throwOnWrite) { "Simulated preference write failure" }
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
                        if (failCommits) false else { disk.clear(); disk.putAll(values); true }
                    }
                    else -> null
                }
            } as SharedPreferences.Editor
            return editor
        }
        val value = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor()
                "getAll" -> { check(!throwOnRead); values.toMap() }
                "contains" -> { check(!throwOnRead); values.containsKey(args[0]) }
                "getString", "getInt", "getLong", "getBoolean", "getFloat", "getStringSet" -> {
                    check(!throwOnRead)
                    values[args[0]] ?: args[1]
                }
                else -> null
            }
        } as SharedPreferences
    }
}
