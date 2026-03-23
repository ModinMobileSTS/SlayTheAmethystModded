package io.stamethyst.ui.main

import android.app.Activity
import android.content.SharedPreferences
import io.stamethyst.model.ModItemUi
import java.util.LinkedHashMap
import org.json.JSONArray
import org.json.JSONObject

internal class MainFolderStateStore {
    private val modFolders = ArrayList<MainScreenViewModel.ModFolder>()
    private val folderAssignments = LinkedHashMap<String, String>()
    private val folderCollapsed = LinkedHashMap<String, Boolean>()
    private var unassignedCollapsed = false
    private var statusSummaryCollapsed = true
    private var unassignedFolderName = DEFAULT_UNASSIGNED_FOLDER_NAME
    private var unassignedFolderOrder = 0
    private var loaded = false

    val folders: MutableList<MainScreenViewModel.ModFolder>
        get() = modFolders
    val assignments: MutableMap<String, String>
        get() = folderAssignments
    val collapsedMap: MutableMap<String, Boolean>
        get() = folderCollapsed
    var unassignedIsCollapsed: Boolean
        get() = unassignedCollapsed
        set(value) {
            unassignedCollapsed = value
        }
    var statusSummaryIsCollapsed: Boolean
        get() = statusSummaryCollapsed
        set(value) {
            statusSummaryCollapsed = value
        }
    var unassignedName: String
        get() = unassignedFolderName
        set(value) {
            unassignedFolderName = value
        }
    var unassignedOrder: Int
        get() = unassignedFolderOrder
        set(value) {
            unassignedFolderOrder = value.coerceIn(0, modFolders.size)
        }

    fun ensureLoaded(host: Activity) {
        if (loaded) {
            return
        }
        load(host)
        loaded = true
    }

    fun persist(host: Activity) {
        val foldersArray = JSONArray()
        modFolders.forEach { folder ->
            val item = JSONObject()
            item.put("id", folder.id)
            item.put("name", folder.name)
            foldersArray.put(item)
        }

        val assignmentsObject = JSONObject()
        folderAssignments.forEach { (modKey, folderId) ->
            assignmentsObject.put(modKey, folderId)
        }

        val collapsedObject = JSONObject()
        folderCollapsed.forEach { (folderId, collapsed) ->
            collapsedObject.put(folderId, collapsed)
        }

        prefs(host).edit()
            .putString(KEY_FOLDERS, foldersArray.toString())
            .putString(KEY_ASSIGNMENTS, assignmentsObject.toString())
            .putString(KEY_COLLAPSED, collapsedObject.toString())
            .putBoolean(KEY_UNASSIGNED_COLLAPSED, unassignedCollapsed)
            .putBoolean(KEY_STATUS_SUMMARY_COLLAPSED, statusSummaryCollapsed)
            .putString(KEY_UNASSIGNED_NAME, unassignedFolderName)
            .putInt(KEY_UNASSIGNED_ORDER, unassignedFolderOrder.coerceIn(0, modFolders.size))
            .apply()
    }

    fun sanitize(optionalMods: List<ModItemUi>) {
        val validFolderIds = modFolders.map { it.id }.toHashSet()
        val normalized = LinkedHashMap<String, String>()
        optionalMods.forEach { mod ->
            val primaryKey = resolveModAssignmentKey(mod) ?: return@forEach
            val assignedFolderId = resolveAssignedFolderId(mod, folderAssignments, validFolderIds)
            if (!assignedFolderId.isNullOrBlank() && validFolderIds.contains(assignedFolderId)) {
                normalized[primaryKey] = assignedFolderId
            }
        }
        folderAssignments.clear()
        folderAssignments.putAll(normalized)

        val normalizedCollapsed = LinkedHashMap<String, Boolean>()
        modFolders.forEach { folder ->
            normalizedCollapsed[folder.id] = folderCollapsed[folder.id] == true
        }
        folderCollapsed.clear()
        folderCollapsed.putAll(normalizedCollapsed)
        unassignedFolderOrder = unassignedFolderOrder.coerceIn(0, modFolders.size)
    }

    fun buildFolderOrderTokens(): List<String> {
        val tokens = modFolders.map { it.id }.toMutableList()
        val insertIndex = unassignedFolderOrder.coerceIn(0, tokens.size)
        tokens.add(insertIndex, UNASSIGNED_FOLDER_ID)
        return tokens
    }

    fun applyFolderOrderTokens(tokens: List<String>) {
        val folderMap = modFolders.associateBy { it.id }
        val reordered = tokens
            .filter { it != UNASSIGNED_FOLDER_ID }
            .mapNotNull { folderMap[it] }
        modFolders.clear()
        modFolders.addAll(reordered)
        unassignedFolderOrder = tokens.indexOf(UNASSIGNED_FOLDER_ID)
            .coerceAtLeast(0)
            .coerceIn(0, modFolders.size)
    }

    fun moveFolderToken(host: Activity, folderId: String, offset: Int): Boolean {
        ensureLoaded(host)
        val tokens = buildFolderOrderTokens().toMutableList()
        val fromIndex = tokens.indexOf(folderId)
        if (fromIndex < 0) {
            return false
        }
        val toIndex = fromIndex + offset
        if (toIndex !in tokens.indices) {
            return false
        }
        tokens.add(toIndex, tokens.removeAt(fromIndex))
        applyFolderOrderTokens(tokens)
        persist(host)
        return true
    }

    private fun prefs(host: Activity): SharedPreferences {
        return host.getSharedPreferences(PREFS_MAIN_MOD_FOLDERS, Activity.MODE_PRIVATE)
    }

    private fun load(host: Activity) {
        val preferences = prefs(host)
        modFolders.clear()
        folderAssignments.clear()
        folderCollapsed.clear()

        runCatching {
            val array = JSONArray(preferences.getString(KEY_FOLDERS, "[]") ?: "[]")
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                if (id.isEmpty() || name.isEmpty()) {
                    continue
                }
                modFolders.add(MainScreenViewModel.ModFolder(id = id, name = name))
            }
        }

        runCatching {
            val obj = JSONObject(preferences.getString(KEY_ASSIGNMENTS, "{}") ?: "{}")
            val keys = obj.keys()
            while (keys.hasNext()) {
                val modKey = keys.next().trim()
                val folderId = obj.optString(modKey).trim()
                if (modKey.isNotEmpty() && folderId.isNotEmpty()) {
                    folderAssignments[modKey] = folderId
                }
            }
        }

        runCatching {
            val obj = JSONObject(preferences.getString(KEY_COLLAPSED, "{}") ?: "{}")
            val keys = obj.keys()
            while (keys.hasNext()) {
                val folderId = keys.next().trim()
                if (folderId.isNotEmpty()) {
                    folderCollapsed[folderId] = obj.optBoolean(folderId, false)
                }
            }
        }

        unassignedCollapsed = preferences.getBoolean(KEY_UNASSIGNED_COLLAPSED, false)
        statusSummaryCollapsed = preferences.getBoolean(KEY_STATUS_SUMMARY_COLLAPSED, true)
        unassignedFolderName = preferences.getString(KEY_UNASSIGNED_NAME, DEFAULT_UNASSIGNED_FOLDER_NAME)
            ?.trim()
            ?.ifEmpty { DEFAULT_UNASSIGNED_FOLDER_NAME }
            ?: DEFAULT_UNASSIGNED_FOLDER_NAME
        unassignedFolderOrder = preferences.getInt(KEY_UNASSIGNED_ORDER, 0).coerceIn(0, modFolders.size)
    }

    private fun resolveModAssignmentKey(mod: ModItemUi): String? {
        val storage = mod.storagePath.trim()
        if (storage.isNotEmpty()) {
            return storage
        }
        return resolveStoredOptionalModId(mod)
    }

    companion object {
        private const val PREFS_MAIN_MOD_FOLDERS = "MainModFolders"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_ASSIGNMENTS = "assignments"
        private const val KEY_COLLAPSED = "collapsed"
        private const val KEY_UNASSIGNED_COLLAPSED = "unassigned_collapsed"
        private const val KEY_STATUS_SUMMARY_COLLAPSED = "status_summary_collapsed"
        private const val KEY_UNASSIGNED_NAME = "unassigned_name"
        private const val KEY_UNASSIGNED_ORDER = "unassigned_order"
        private const val DEFAULT_UNASSIGNED_FOLDER_NAME = "未分类"
    }
}
