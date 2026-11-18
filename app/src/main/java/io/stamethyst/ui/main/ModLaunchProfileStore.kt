package io.stamethyst.ui.main

import android.app.Activity
import android.content.SharedPreferences
import io.stamethyst.backend.mods.rebindReplacedModSelection
import java.util.LinkedHashSet
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class ModLaunchProfile(
    val id: String,
    val name: String,
    val enabledModKeys: Set<String>
)

internal const val DEFAULT_MOD_LAUNCH_PROFILE_ID = "default"

internal class ModLaunchProfileStore {
    data class State(
        val profiles: List<ModLaunchProfile>,
        val activeProfileId: String,
        val autoSave: Boolean = true,
    ) {
        val activeProfile: ModLaunchProfile?
            get() = profiles.firstOrNull { it.id == activeProfileId }

        fun hasUnsavedSelection(workingSelection: Set<String>): Boolean =
            !autoSave && activeProfile?.let { it.enabledModKeys != workingSelection } == true
    }

    fun load(host: Activity, currentEnabledModKeys: Set<String>): State {
        val state = readOnly(host, currentEnabledModKeys)
        persist(host, state)
        return state
    }

    /** Preview saved profiles without writing or pruning selections against an incomplete scan. */
    fun readOnly(host: Activity, currentEnabledModKeys: Set<String> = emptySet()): State {
        val preferences = prefs(host)
        val loadedProfiles = readProfiles(preferences)
        val profiles = when {
            loadedProfiles.isEmpty() -> listOf(defaultProfile(currentEnabledModKeys))
            loadedProfiles.none { it.id == DEFAULT_MOD_LAUNCH_PROFILE_ID } ->
                listOf(defaultProfile(emptySet())) + loadedProfiles
            else -> loadedProfiles
        }
        val requestedActiveId = preferences.getString(KEY_ACTIVE_PROFILE_ID, DEFAULT_MOD_LAUNCH_PROFILE_ID)
            ?.trim()
            .orEmpty()
        val activeProfileId = profiles.firstOrNull { it.id == requestedActiveId }?.id ?: profiles.first().id
        return State(profiles, activeProfileId, autoSave = preferences.getBoolean(KEY_AUTO_SAVE, true))
    }

    fun addProfile(state: State, enabledModKeys: Set<String>, name: String): State? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            return null
        }
        val profile = ModLaunchProfile(
            id = UUID.randomUUID().toString(),
            name = trimmedName,
            enabledModKeys = LinkedHashSet(enabledModKeys)
        )
        return state.copy(profiles = state.profiles + profile, activeProfileId = profile.id)
    }

    fun renameProfile(state: State, profileId: String, name: String): State? {
        val targetId = profileId.trim()
        val trimmedName = name.trim()
        if (targetId == DEFAULT_MOD_LAUNCH_PROFILE_ID || trimmedName.isEmpty()) {
            return null
        }
        var renamed = false
        val profiles = state.profiles.map { profile ->
            if (profile.id == targetId) {
                renamed = true
                profile.copy(name = trimmedName)
            } else {
                profile
            }
        }
        if (!renamed) {
            return null
        }
        return state.copy(profiles = profiles)
    }

    fun updateActiveSelection(state: State, enabledModKeys: Set<String>): State {
        val profiles = state.profiles.map { profile ->
            if (profile.id == state.activeProfileId) {
                profile.copy(enabledModKeys = LinkedHashSet(enabledModKeys))
            } else {
                profile
            }
        }
        return state.copy(profiles = profiles)
    }

    fun selectProfile(state: State, profileId: String): State? {
        val targetId = profileId.trim()
        if (state.profiles.none { it.id == targetId }) {
            return null
        }
        return state.copy(activeProfileId = targetId)
    }

    fun deleteProfile(state: State, profileId: String): State? {
        val targetId = profileId.trim()
        if (targetId == DEFAULT_MOD_LAUNCH_PROFILE_ID) {
            return null
        }
        val profiles = state.profiles.filterNot { it.id == targetId }
        if (profiles.size == state.profiles.size || profiles.isEmpty()) {
            return null
        }
        val activeProfileId = if (state.activeProfileId == targetId) {
            profiles.first().id
        } else {
            state.activeProfileId
        }
        return state.copy(profiles = profiles, activeProfileId = activeProfileId)
    }

    fun sanitizeSelections(
        host: Activity,
        state: State,
        installedKeys: Set<String>,
        replacements: Map<String, String> = emptyMap(),
    ): State {
        val profiles = state.profiles.map { profile ->
            profile.copy(enabledModKeys = rebindReplacedModSelection(profile.enabledModKeys, installedKeys, replacements)
                .filterTo(LinkedHashSet()) { installedKeys.contains(it) })
        }
        return state.copy(profiles = profiles).also { persist(host, it) }
    }

    private fun defaultProfile(currentEnabledModKeys: Set<String>): ModLaunchProfile {
        return ModLaunchProfile(
            id = DEFAULT_MOD_LAUNCH_PROFILE_ID,
            name = DEFAULT_PROFILE_NAME,
            enabledModKeys = LinkedHashSet(currentEnabledModKeys)
        )
    }

    private fun readProfiles(preferences: SharedPreferences): List<ModLaunchProfile> {
        val result = ArrayList<ModLaunchProfile>()
        runCatching {
            val array = JSONArray(preferences.getString(KEY_PROFILES, "[]") ?: "[]")
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                if (id.isEmpty() || name.isEmpty()) {
                    continue
                }
                val enabled = LinkedHashSet<String>()
                val enabledArray = item.optJSONArray("enabledModKeys") ?: JSONArray()
                for (enabledIndex in 0 until enabledArray.length()) {
                    val token = enabledArray.optString(enabledIndex).trim()
                    if (token.isNotEmpty()) {
                        enabled.add(token)
                    }
                }
                result.add(ModLaunchProfile(id = id, name = name, enabledModKeys = enabled))
            }
        }
        return result.distinctBy { it.id }
    }

    fun persist(host: Activity, state: State) {
        val array = JSONArray()
        state.profiles.forEach { profile ->
            val item = JSONObject()
            item.put("id", profile.id)
            item.put("name", profile.name)
            val enabledArray = JSONArray()
            profile.enabledModKeys.forEach { enabledArray.put(it) }
            item.put("enabledModKeys", enabledArray)
            array.put(item)
        }
        val preferences = prefs(host)
        val serialized = array.toString()
        if (preferences.getString(KEY_PROFILES, null) == serialized &&
            preferences.getString(KEY_ACTIVE_PROFILE_ID, null) == state.activeProfileId &&
            preferences.getBoolean(KEY_AUTO_SAVE, true) == state.autoSave) return
        preferences.edit()
            .putString(KEY_PROFILES, serialized)
            .putString(KEY_ACTIVE_PROFILE_ID, state.activeProfileId)
            .putBoolean(KEY_AUTO_SAVE, state.autoSave)
            .apply()
    }

    private fun prefs(host: Activity): SharedPreferences {
        return host.getSharedPreferences(PREFS_MOD_LAUNCH_PROFILES, Activity.MODE_PRIVATE)
    }

    companion object {
        private const val PREFS_MOD_LAUNCH_PROFILES = "ModLaunchProfiles"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        private const val KEY_AUTO_SAVE = "auto_save"
        private const val DEFAULT_PROFILE_NAME = "Default"
    }
}
