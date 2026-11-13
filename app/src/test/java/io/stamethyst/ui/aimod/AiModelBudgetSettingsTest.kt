package io.stamethyst.ui.aimod

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.stamethyst.backend.llm.AgentModelLimits
import io.stamethyst.backend.llm.NewApiModel
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class AiModelBudgetSettingsTest {
    @Test fun legacySavedInputLimitRemainsAnExplicitInputBudget() {
        val context = context()
        val key = AiContextLimits.key("https://provider/v1", "model")
        context.getSharedPreferences("ai_context_limits", Context.MODE_PRIVATE).edit().putInt(key, 200_000).apply()
        val budget = AiContextLimits.budget(context, key)
        assertEquals(200_000, AiContextLimits.get(context, key))
        assertEquals(200_000, budget.inputLimit)
        assertEquals(4_000, budget.outputReserve)
        assertFalse(budget.usesDefaultInputBudget)
    }

    @Test fun clearingInputRestoresDefaultWithoutSavingItAsAnExplicitOverride() {
        val context = context()
        AiContextLimits.set(context, "key", 80_000, AgentModelLimits())
        AiContextLimits.set(context, "key", null, AgentModelLimits())
        assertNull(AiContextLimits.explicitInputLimit(context, "key"))
        assertEquals(32_000, AiContextLimits.budget(context, "key").inputLimit)
        assertTrue(AiContextLimits.budget(context, "key").usesDefaultInputBudget)
    }

    @Test fun catalogAndOverridePrecedenceSurvivesSavingAndIsModelScoped() {
        val context = context()
        val base = "https://provider/v1"
        val key = AiContextLimits.key(base, "model")
        AiContextLimits.cacheCatalog(context, " $base/ ", listOf(NewApiModel("model",
            context_window = JsonPrimitive(20_000), max_output_tokens = JsonPrimitive(1_000))))
        AiContextLimits.set(context, key, 30_000, AgentModelLimits(contextWindow = 24_000, maxOutputTokens = 2_000))
        val budget = AiContextLimits.budget(context, key)
        assertEquals(22_000, budget.inputLimit)
        assertEquals(2_000, budget.outputReserve)
        assertEquals(AgentModelLimits(24_000, maxOutputTokens = 2_000), AiContextLimits.overrides(context, key))
        assertEquals(32_000, AiContextLimits.budget(context, AiContextLimits.key(base, "other")).inputLimit)
        assertEquals(32_000, AiContextLimits.budget(context, AiContextLimits.key("https://other/v1", "model")).inputLimit)
        AiContextLimits.set(context, key, 30_000, AgentModelLimits())
        assertEquals(19_000, AiContextLimits.budget(context, key).inputLimit)
    }

    @Test fun catalogRefreshWithoutOptionalLimitsClearsOldCapabilities() {
        val context = context()
        val key = AiContextLimits.key("base", "model")
        AiContextLimits.cacheCatalog(context, "base", listOf(NewApiModel("model", context_window = JsonPrimitive(16_000))))
        assertEquals(12_000, AiContextLimits.budget(context, key).inputLimit)
        AiContextLimits.cacheCatalog(context, "base", listOf(NewApiModel("model")))
        assertEquals(AgentModelLimits(), AiContextLimits.budget(context, key).modelLimits)
    }

    @Test fun modelLevelOutputTargetIsIndependentOfCapabilityOverrideAndCanBeReset() {
        val context = context()
        val limits = AgentModelLimits(contextWindow = 32_000, maxOutputTokens = 8_000)
        AiContextLimits.set(context, "key", 32_000, limits, outputTarget = 6_000)
        assertEquals(6_000, AiContextLimits.explicitOutputTarget(context, "key"))
        assertEquals(6_000, AiContextLimits.budget(context, "key").outputReserve)
        assertEquals(26_000, AiContextLimits.budget(context, "key").inputLimit)
        assertEquals(2_000, AiContextLimits.budget(context, "key").summaryOutputLimit)
        AiContextLimits.set(context, "key", 32_000, limits, outputTarget = 12_000)
        assertEquals(8_000, AiContextLimits.budget(context, "key").outputReserve)
        AiContextLimits.set(context, "key", 32_000, limits, outputTarget = null)
        assertNull(AiContextLimits.explicitOutputTarget(context, "key"))
        assertEquals(4_000, AiContextLimits.budget(context, "key").outputReserve)
    }

    @Test fun corruptOptionalCapabilityStorageDoesNotDiscardLegacyInputSetting() {
        val context = context()
        AiContextLimits.set(context, "key", 25_000, AgentModelLimits())
        context.getSharedPreferences("ai_model_budget_overrides", Context.MODE_PRIVATE).edit()
            .putString("key", "{broken").apply()
        assertEquals(25_000, AiContextLimits.budget(context, "key").inputLimit)
        assertEquals(AgentModelLimits(), AiContextLimits.overrides(context, "key"))
    }

    private fun context(): Context {
        val preferences = mutableMapOf<String, SharedPreferences>()
        return object : ContextWrapper(Application()) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                preferences.getOrPut(name) { InMemoryPreferences() }
        }
    }

    private class InMemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = Editor()
        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clear = false
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toSet() }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) values.clear()
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
