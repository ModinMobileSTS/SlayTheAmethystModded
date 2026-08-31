package io.stamethyst.backend.workshop

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal class WorkshopMetadataStore(context: Context) {
    private val file = File(RuntimePaths.workshopRoot(context), "index.json")
    private val legacyFile = File(context.filesDir, "workshop/index.json")
    private val context = context.applicationContext

    fun load(): List<WorkshopInstalledModRecord> = withStoreLock { loadUnlocked() }

    fun save(records: List<WorkshopInstalledModRecord>) = withStoreLock { saveUnlocked(records) }

    fun upsert(record: WorkshopInstalledModRecord) = withStoreLock {
        val current = loadUnlocked().toMutableList()
        val index = current.indexOfFirst { it.appId == record.appId && it.publishedFileId == record.publishedFileId }
        if (index >= 0) {
            current[index] = record.preserveLocalPreviewImage(current[index])
        } else {
            current.add(record)
        }
        saveUnlocked(current)
    }

    fun findByPublishedFileId(appId: UInt, publishedFileId: ULong): WorkshopInstalledModRecord? = withStoreLock {
        loadUnlocked().firstOrNull { it.appId == appId && it.publishedFileId == publishedFileId }
    }

    fun updateState(appId: UInt, publishedFileId: ULong, state: WorkshopModCardState, statusText: String) = withStoreLock {
        val records = loadUnlocked().map { record ->
            if (record.appId == appId && record.publishedFileId == publishedFileId) {
                record.copy(cardState = state, statusText = statusText)
            } else {
                record
            }
        }
        saveUnlocked(records)
    }

    fun applyUpdateCheckResults(results: List<WorkshopUpdateCheckResult>) = withStoreLock {
        if (results.isEmpty()) return@withStoreLock
        val resultByPublishedFileId = results.associateBy { it.publishedFileId }
        val records = loadUnlocked().map { record ->
            val result = resultByPublishedFileId[record.publishedFileId]
                ?.takeIf { it.appId == record.appId }
                ?: return@map record
            when {
                result.hasUpdate -> record.copy(
                    cardState = WorkshopModCardState.UpdateAvailable,
                    statusText = "发现创意工坊更新",
                )
                record.cardState == WorkshopModCardState.UpdateAvailable -> record.copy(
                    cardState = record.restoredImportedState(),
                    statusText = "创意工坊模组均为最新",
                )
                else -> record
            }
        }
        saveUnlocked(records)
    }

    fun remove(appId: UInt, publishedFileId: ULong) = withStoreLock {
        saveUnlocked(loadUnlocked().filterNot { it.appId == appId && it.publishedFileId == publishedFileId })
    }

    fun removeByLocalJarPaths(localJarPaths: Collection<String>): Int = withStoreLock {
        val normalizedPaths = localJarPaths
            .mapNotNull { it.normalizedLocalJarPath().takeIf(String::isNotEmpty) }
            .toSet()
        if (normalizedPaths.isEmpty()) return@withStoreLock 0
        val current = loadUnlocked()
        val remaining = current.filterNot { record ->
            record.allLocalJarPaths().any { path -> path.normalizedLocalJarPath() in normalizedPaths }
        }
        val removedCount = current.size - remaining.size
        if (removedCount > 0) saveUnlocked(remaining)
        removedCount
    }

    fun markPatched(appId: UInt, publishedFileId: ULong, localJarPath: String, statusText: String) =
        markPatched(appId, publishedFileId, listOf(localJarPath), statusText)

    fun markPatched(appId: UInt, publishedFileId: ULong, localJarPaths: List<String>, statusText: String) = withStoreLock {
        val normalizedPaths = localJarPaths.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val primaryPath = normalizedPaths.firstOrNull().orEmpty()
        val records = loadUnlocked().map { record ->
            if (record.appId == appId && record.publishedFileId == publishedFileId) {
                record.copy(
                    localJarPath = primaryPath,
                    localJarPaths = normalizedPaths,
                    cardState = WorkshopModCardState.ImportedPatched,
                    statusText = statusText,
                    localPreviewImagePath = record.localPreviewImagePath,
                )
            } else {
                record
            }
        }
        saveUnlocked(records)
    }

    fun updatePreviewImagePath(appId: UInt, publishedFileId: ULong, localPreviewImagePath: String) {
        if (localPreviewImagePath.isBlank()) return
        withStoreLock {
            val records = loadUnlocked().map { record ->
                if (record.appId == appId && record.publishedFileId == publishedFileId) {
                    record.copy(localPreviewImagePath = localPreviewImagePath)
                } else {
                    record
                }
            }
            saveUnlocked(records)
        }
    }

    fun markMissingFiles() = withStoreLock {
        var changed = false
        val records = loadUnlocked().map { record ->
            if (record.shouldMarkFileMissing(context)) {
                changed = true
                record.copy(
                    cardState = WorkshopModCardState.FileMissing,
                    statusText = "已下载文件缺失，请重新下载",
                )
            } else {
                record
            }
        }
        if (changed) saveUnlocked(records)
    }

    fun list(): List<WorkshopInstalledModRecord> = load()

    fun countUpdateAvailable(): Int = withStoreLock {
        loadUnlocked().count { record -> record.cardState == WorkshopModCardState.UpdateAvailable }
    }

    private fun loadUnlocked(): List<WorkshopInstalledModRecord> {
        val sourceFile = if (file.isFile || !legacyFile.isFile) file else legacyFile
        cachedRecords(sourceFile)?.let { return it }
        val records = WorkshopJsonFileStore.readJsonOrDefault(sourceFile, emptyList<WorkshopInstalledModRecord>()) { text ->
            val root = JSONObject(text)
            val array = root.optJSONArray("items") ?: return@readJsonOrDefault emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(WorkshopMetadataCodec.fromJson(item))
                }
            }
        }
        updateCache(sourceFile, records)
        return records
    }

    private fun saveUnlocked(records: List<WorkshopInstalledModRecord>) {
        val root = JSONObject().put("items", JSONArray())
        val array = root.getJSONArray("items")
        records.sortedByDescending(WorkshopInstalledModRecord::updatedAtMillis).forEach { record ->
            array.put(WorkshopMetadataCodec.toJson(record))
        }
        WorkshopJsonFileStore.writeAtomically(file, root.toString(2))
        updateCache(file, records.sortedByDescending(WorkshopInstalledModRecord::updatedAtMillis))
    }

    private fun <T> withStoreLock(block: () -> T): T = WorkshopJsonFileStore.withFileLock(file, lock, block)

    companion object {
        private val lock = Any()
        private var cachedRecords: CachedWorkshopMetadata? = null

        private fun cachedRecords(file: File): List<WorkshopInstalledModRecord>? {
            val signature = file.cacheSignature() ?: run {
                cachedRecords = null
                return null
            }
            return cachedRecords
                ?.takeIf { it.signature == signature }
                ?.records
        }

        private fun updateCache(file: File, records: List<WorkshopInstalledModRecord>) {
            val signature = file.cacheSignature() ?: run {
                cachedRecords = null
                return
            }
            cachedRecords = CachedWorkshopMetadata(signature, records)
        }
    }
}

private data class CachedWorkshopMetadata(
    val signature: WorkshopFileCacheSignature,
    val records: List<WorkshopInstalledModRecord>,
)

private fun WorkshopInstalledModRecord.preserveLocalPreviewImage(existing: WorkshopInstalledModRecord): WorkshopInstalledModRecord {
    if (localPreviewImagePath.isNotBlank()) return this
    return copy(localPreviewImagePath = existing.localPreviewImagePath)
}

private fun String.normalizedLocalJarPath(): String = trim().replace('\\', '/')

private fun WorkshopInstalledModRecord.restoredImportedState(): WorkshopModCardState {
    if (contentKind == WorkshopInstalledContentKind.TexturePack) return WorkshopModCardState.TexturePackInstalled
    val path = allLocalJarPaths().firstOrNull().orEmpty()
    if (path.isEmpty()) return WorkshopModCardState.ImportedUnpatched
    val file = File(path)
    return if (file.isAbsolute) {
        when {
            file.isDirectory -> WorkshopModCardState.NonStandardDownloaded
            file.isFile -> WorkshopModCardState.ImportedPatched
            else -> WorkshopModCardState.FileMissing
        }
    } else {
        WorkshopModCardState.ImportedUnpatched
    }
}

private fun WorkshopInstalledModRecord.shouldMarkFileMissing(context: Context): Boolean {
    val files = allLocalJarPaths().map { path ->
        if (File(path).isAbsolute) {
            File(path)
        } else {
            val primary = File(RuntimePaths.workshopItemDir(context, appId, publishedFileId), path)
            if (primary.exists()) primary else File(RuntimePaths.legacyWorkshopItemDir(context, appId, publishedFileId), path)
        }
    }
    if (files.isEmpty()) return false
    return when (cardState) {
        WorkshopModCardState.ImportedPatched,
        WorkshopModCardState.ImportedUnpatched -> files.any { file -> !file.isFile }
        WorkshopModCardState.NonStandardDownloaded -> files.any { file -> !file.exists() }
        WorkshopModCardState.TexturePackInstalled -> files.any { file -> !file.isDirectory }
        WorkshopModCardState.UpdateAvailable -> files.any { file -> !file.exists() }
        WorkshopModCardState.Downloading,
        WorkshopModCardState.DownloadPaused,
        WorkshopModCardState.DownloadFailed,
        WorkshopModCardState.FileMissing -> false
    }
}
