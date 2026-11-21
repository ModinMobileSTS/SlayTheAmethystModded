package io.stamethyst.ui.main

import android.content.Context
import androidx.compose.runtime.Stable
import io.stamethyst.backend.mods.resolveReplacedModKey
import io.stamethyst.model.ModItemUi
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.UUID
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

@Stable
data class ModAssociationGroup(
    val id: String,
    val colorArgb: Int,
    val modKeys: Set<String>
)

@Stable
data class ModAssociationBadgeUi(
    val groupId: String,
    val colorArgb: Int,
    val associatedCount: Int
)

@Stable
data class ModAssociationState(
    val groups: List<ModAssociationGroup> = emptyList()
) {
    fun groupFor(mod: ModItemUi): ModAssociationGroup? {
        val candidates = resolveModAssociationKeyCandidates(mod)
        if (candidates.isEmpty()) {
            return null
        }
        return groups.firstOrNull { group -> group.modKeys.any(candidates::contains) }
    }

    fun badgeFor(mod: ModItemUi): ModAssociationBadgeUi? {
        val group = groupFor(mod) ?: return null
        val modKey = resolveModAssociationKey(mod) ?: return null
        val associatedCount = group.modKeys.count { it != modKey }
        if (associatedCount <= 0) {
            return null
        }
        return ModAssociationBadgeUi(
            groupId = group.id,
            colorArgb = group.colorArgb,
            associatedCount = associatedCount
        )
    }

    fun associatedModKeysFor(mod: ModItemUi): Set<String> {
        val group = groupFor(mod) ?: return emptySet()
        val modKey = resolveModAssociationKey(mod) ?: return emptySet()
        return group.modKeys.filterTo(LinkedHashSet()) { it != modKey }
    }

    fun associatedModsFor(mod: ModItemUi, optionalMods: List<ModItemUi>): List<ModItemUi> {
        val associatedKeys = associatedModKeysFor(mod)
        if (associatedKeys.isEmpty()) {
            return emptyList()
        }
        return optionalMods.filter { candidate ->
            resolveModAssociationKey(candidate)?.let(associatedKeys::contains) == true
        }
    }
}

internal class ModAssociationStore {
    private var state = ModAssociationState()

    fun reload(context: Context) {
        state = readState(context)
    }

    fun snapshot(): ModAssociationState = state

    fun sanitize(context: Context, optionalMods: List<ModItemUi>, replacements: Map<String, String>) {
        val sanitized = sanitizeModAssociationState(state, optionalMods, replacements)
        if (sanitized != state) {
            state = sanitized
            writeState(context, state)
        }
    }

    fun associate(context: Context, source: ModItemUi, target: ModItemUi): Boolean {
        val sourceKey = resolveModAssociationKey(source) ?: return false
        val targetKey = resolveModAssociationKey(target) ?: return false
        if (sourceKey == targetKey) {
            return false
        }

        val sourceGroup = state.groupFor(source)
        val targetGroup = state.groupFor(target)
        val nextGroups = state.groups.toMutableList()
        val changed = when {
            sourceGroup == null && targetGroup == null -> {
                nextGroups.add(
                    ModAssociationGroup(
                        id = UUID.randomUUID().toString(),
                        colorArgb = randomAssociationBadgeColor(nextGroups.map { it.colorArgb }.toSet()),
                        modKeys = linkedSetOf(sourceKey, targetKey)
                    )
                )
                true
            }

            sourceGroup != null && targetGroup == null -> {
                if (sourceGroup.modKeys.contains(targetKey)) {
                    false
                } else {
                    replaceGroup(
                        groups = nextGroups,
                        updated = sourceGroup.copy(modKeys = sourceGroup.modKeys + targetKey)
                    )
                    true
                }
            }

            sourceGroup == null && targetGroup != null -> {
                if (targetGroup.modKeys.contains(sourceKey)) {
                    false
                } else {
                    replaceGroup(
                        groups = nextGroups,
                        updated = targetGroup.copy(modKeys = linkedSetOf(sourceKey).apply { addAll(targetGroup.modKeys) })
                    )
                    true
                }
            }

            sourceGroup != null && targetGroup != null && sourceGroup.id != targetGroup.id -> {
                val merged = sourceGroup.copy(modKeys = sourceGroup.modKeys + targetGroup.modKeys)
                nextGroups.removeAll { it.id == sourceGroup.id || it.id == targetGroup.id }
                nextGroups.add(merged)
                true
            }

            else -> false
        }
        if (!changed) {
            return false
        }

        state = ModAssociationState(nextGroups).withoutSingletonGroups()
        writeState(context, state)
        return true
    }

    fun removeAssociation(context: Context, source: ModItemUi, target: ModItemUi): Boolean {
        val targetKey = resolveModAssociationKey(target) ?: return false
        val group = state.groupFor(source) ?: return false
        if (!group.modKeys.contains(targetKey)) {
            return false
        }
        val updated = group.copy(modKeys = group.modKeys - targetKey)
        state = ModAssociationState(
            state.groups.map { existing -> if (existing.id == group.id) updated else existing }
        ).withoutSingletonGroups()
        writeState(context, state)
        return true
    }

    fun clearGroup(context: Context, mod: ModItemUi): Boolean {
        val group = state.groupFor(mod) ?: return false
        state = ModAssociationState(state.groups.filterNot { it.id == group.id })
        writeState(context, state)
        return true
    }

    fun removeMod(context: Context, mod: ModItemUi): Boolean {
        val candidates = resolveModAssociationKeyCandidates(mod)
        if (candidates.isEmpty()) {
            return false
        }
        val updatedGroups = state.groups.map { group ->
            group.copy(modKeys = group.modKeys.filterTo(LinkedHashSet()) { it !in candidates })
        }
        val updated = ModAssociationState(updatedGroups).withoutSingletonGroups()
        if (updated == state) {
            return false
        }
        state = updated
        writeState(context, state)
        return true
    }

    private fun readState(context: Context): ModAssociationState {
        val json = prefs(context).getString(KEY_GROUPS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(json)
            val groups = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString(KEY_ID).trim()
                    val colorArgb = item.optInt(KEY_COLOR, DEFAULT_ASSOCIATION_BADGE_COLORS.first())
                    val modKeysArray = item.optJSONArray(KEY_MOD_KEYS) ?: continue
                    val modKeys = LinkedHashSet<String>()
                    for (modIndex in 0 until modKeysArray.length()) {
                        val key = modKeysArray.optString(modIndex).trim()
                        if (key.isNotEmpty()) {
                            modKeys.add(key)
                        }
                    }
                    if (id.isNotEmpty() && modKeys.size >= MIN_ASSOCIATION_GROUP_SIZE) {
                        add(
                            ModAssociationGroup(
                                id = id,
                                colorArgb = colorArgb,
                                modKeys = modKeys
                            )
                        )
                    }
                }
            }
            ModAssociationState(groups)
        }.getOrDefault(ModAssociationState())
    }

    private fun writeState(context: Context, state: ModAssociationState) {
        val array = JSONArray()
        state.groups.forEach { group ->
            array.put(
                JSONObject()
                    .put(KEY_ID, group.id)
                    .put(KEY_COLOR, group.colorArgb)
                    .put(
                        KEY_MOD_KEYS,
                        JSONArray().apply { group.modKeys.forEach(::put) }
                    )
            )
        }
        prefs(context).edit()
            .putString(KEY_GROUPS, array.toString())
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_MAIN_MOD_ASSOCIATIONS, Context.MODE_PRIVATE)

    private companion object {
        private const val PREFS_MAIN_MOD_ASSOCIATIONS = "MainModAssociations"
        private const val KEY_GROUPS = "groups"
        private const val KEY_ID = "id"
        private const val KEY_COLOR = "color"
        private const val KEY_MOD_KEYS = "modKeys"
    }
}

internal fun resolveModAssociationKey(mod: ModItemUi): String? =
    resolveStoredOptionalModId(mod)?.trim()?.takeIf { it.isNotEmpty() }

internal fun resolveModAssociationKeyCandidates(mod: ModItemUi): Set<String> =
    resolveAssignmentKeyCandidates(mod)
        .map { it.trim() }
        .filterTo(LinkedHashSet()) { it.isNotEmpty() }

internal fun sanitizeModAssociationState(
    state: ModAssociationState,
    optionalMods: List<ModItemUi>,
    replacements: Map<String, String> = emptyMap(),
): ModAssociationState {
    if (state.groups.isEmpty()) {
        return state
    }
    val primaryKeyByCandidate = LinkedHashMap<String, String>()
    optionalMods.forEach { mod ->
        val primaryKey = resolveModAssociationKey(mod) ?: return@forEach
        resolveModAssociationKeyCandidates(mod).forEach { candidate ->
            primaryKeyByCandidate[candidate] = primaryKey
        }
    }
    if (primaryKeyByCandidate.isEmpty()) {
        return ModAssociationState()
    }

    var rebound = false
    val normalizedGroups = state.groups.map { group ->
        val normalizedKeys = group.modKeys.mapNotNullTo(LinkedHashSet()) { storedKey ->
            val key = resolveReplacedModKey(storedKey, primaryKeyByCandidate.keys, replacements)
            primaryKeyByCandidate[key]?.also { if (key != storedKey) rebound = true }
        }
        group.copy(modKeys = normalizedKeys)
    }.filter { it.modKeys.size >= MIN_ASSOCIATION_GROUP_SIZE }
    if (!rebound) return ModAssociationState(normalizedGroups)

    val mergedGroups = mutableListOf<ModAssociationGroup>()
    for (group in normalizedGroups) {
        // Replacing multiple copies can bring two previously separate groups together.
        val overlapping = mergedGroups.filter { it.modKeys.any(group.modKeys::contains) }
        val first = overlapping.firstOrNull()
        if (first == null) {
            mergedGroups.add(group)
        } else {
            val merged = first.copy(modKeys = overlapping.flatMapTo(LinkedHashSet()) { it.modKeys } + group.modKeys)
            replaceGroup(mergedGroups, merged)
            mergedGroups.removeAll(overlapping.drop(1).toSet())
        }
    }
    return ModAssociationState(mergedGroups)
}

private fun ModAssociationState.withoutSingletonGroups(): ModAssociationState =
    copy(groups = groups.filter { it.modKeys.size >= MIN_ASSOCIATION_GROUP_SIZE })

private fun replaceGroup(
    groups: MutableList<ModAssociationGroup>,
    updated: ModAssociationGroup
) {
    val index = groups.indexOfFirst { it.id == updated.id }
    if (index >= 0) {
        groups[index] = updated
    }
}

private fun randomAssociationBadgeColor(existingColors: Set<Int>): Int {
    val available = DEFAULT_ASSOCIATION_BADGE_COLORS.filterNot(existingColors::contains)
    return (available.ifEmpty { DEFAULT_ASSOCIATION_BADGE_COLORS.toList() }).random(Random.Default)
}

private const val MIN_ASSOCIATION_GROUP_SIZE = 2

private val DEFAULT_ASSOCIATION_BADGE_COLORS = intArrayOf(
    0xFFE57373.toInt(),
    0xFF64B5F6.toInt(),
    0xFF81C784.toInt(),
    0xFFFFB74D.toInt(),
    0xFFBA68C8.toInt(),
    0xFF4DB6AC.toInt(),
    0xFFFF8A65.toInt(),
    0xFFAED581.toInt(),
)
