package io.stamethyst.backend.workshop

import android.content.Context
import android.util.JsonReader
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

enum class WorkshopDownloadTaskStatus {
    Queued,
    Resolving,
    Downloading,
    Pausing,
    Cancelling,
    Paused,
    Completed,
    Failed,
    Cancelled,
}

data class WorkshopDownloadTaskRecord(
    val publishedFileId: ULong,
    val title: String,
    val status: WorkshopDownloadTaskStatus,
    val message: String,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val details: WorkshopItemDetails,
    val previewUrl: String = details.summary.previewUrl,
    val description: String = details.summary.description,
    val authorName: String = details.summary.authorName,
    val fileSizeBytes: Long = details.summary.fileSizeBytes,
    val progressPercent: Int? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = details.summary.fileSizeBytes.takeIf { it > 0L },
    val completedFiles: Int? = null,
    val totalFiles: Int? = null,
    val completedChunks: Int? = null,
    val totalChunks: Int? = null,
    val errorClass: String = "",
    val errorMessage: String = "",
    val errorStackTrace: String = "",
    val downloadLog: String = "",
    val preservePartialDownload: Boolean = false,
    /** Explicit patch upgrades always import the downloaded jar, regardless of the global preference. */
    val forceAutoImport: Boolean = false,
)

class WorkshopDownloadTaskStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "workshop/download_tasks.json")

    fun list(): List<WorkshopDownloadTaskRecord> = withStoreLock { loadUnlocked() }

    fun listLauncherVisible(): List<WorkshopDownloadTaskRecord> = withStoreLock {
        loadLauncherVisibleUnlocked()
    }

    fun save(tasks: List<WorkshopDownloadTaskRecord>) = withStoreLock { saveUnlocked(tasks) }

    fun pauseAllForLogout() = withStoreLock {
        saveUnlocked(loadUnlocked().map { task ->
            if (task.status.isRunningDownload() || task.status == WorkshopDownloadTaskStatus.Queued) {
                task.copy(status = WorkshopDownloadTaskStatus.Paused, message = "Steam 账号已退出，下载已暂停",
                    preservePartialDownload = true, updatedAtMillis = System.currentTimeMillis())
            } else task
        })
    }

    fun upsert(task: WorkshopDownloadTaskRecord) = withStoreLock {
        clearDeletedMarker(task.publishedFileId)
        val current = loadUnlocked().toMutableList()
        val index = current.indexOfFirst { it.publishedFileId == task.publishedFileId }
        if (index >= 0) current[index] = task else current.add(0, task)
        saveUnlocked(current)
    }

    fun enqueueSubscriptionIfEligible(
        task: WorkshopDownloadTaskRecord,
        expectedStatus: WorkshopDownloadTaskStatus?,
        isCurrent: () -> Boolean = { true },
    ): Boolean = withStoreLock {
        if (task.status != WorkshopDownloadTaskStatus.Queued || isMarkedDeleted(task.publishedFileId)) return@withStoreLock false
        val current = loadUnlocked().toMutableList()
        val index = current.indexOfFirst { it.publishedFileId == task.publishedFileId }
        val status = current.getOrNull(index)?.status
        if (status != expectedStatus || (status != null && status != WorkshopDownloadTaskStatus.Completed)) return@withStoreLock false
        if (!isCurrent()) return@withStoreLock false
        if (index >= 0) current[index] = task else current.add(0, task)
        saveUnlocked(current)
        true
    }

    fun update(publishedFileId: ULong, transform: (WorkshopDownloadTaskRecord) -> WorkshopDownloadTaskRecord) = withStoreLock {
        val current = loadUnlocked().toMutableList()
        val index = current.indexOfFirst { it.publishedFileId == publishedFileId }
        if (index < 0) return@withStoreLock
        current[index] = transform(current[index])
        saveUnlocked(current)
    }

    fun appendLog(publishedFileId: ULong, message: String) = withStoreLock {
        val cleanMessage = message.trim().takeIf { it.isNotEmpty() } ?: return@withStoreLock
        val current = loadUnlocked().toMutableList()
        val index = current.indexOfFirst { it.publishedFileId == publishedFileId }
        if (index < 0) return@withStoreLock
        val line = "${downloadLogTimestamp()} $cleanMessage"
        current[index] = current[index].copy(downloadLog = appendBoundedDownloadLog(current[index].downloadLog, line))
        saveUnlocked(current)
    }

    fun remove(publishedFileId: ULong) = withStoreLock {
        saveUnlocked(loadUnlocked().filterNot { it.publishedFileId == publishedFileId })
    }

    fun removeAndMarkDeleted(publishedFileId: ULong) = withStoreLock {
        saveUnlocked(loadUnlocked().filterNot { it.publishedFileId == publishedFileId })
        val markerFile = deletedMarkerFile(publishedFileId)
        markerFile.parentFile?.mkdirs()
        markerFile.writeText(System.currentTimeMillis().toString(), StandardCharsets.UTF_8)
    }

    fun clearDeletedMarker(publishedFileId: ULong) {
        deletedMarkerFile(publishedFileId).delete()
    }

    fun isMarkedDeleted(publishedFileId: ULong): Boolean = deletedMarkerFile(publishedFileId).isFile

    fun find(publishedFileId: ULong): WorkshopDownloadTaskRecord? = withStoreLock {
        loadUnlocked().firstOrNull { it.publishedFileId == publishedFileId }
    }

    fun hasRunningTask(): Boolean = withStoreLock { loadUnlocked().any { it.status.isRunningDownload() } }

    fun nextQueuedTask(): WorkshopDownloadTaskRecord? = withStoreLock { loadUnlocked()
        .filter { it.status == WorkshopDownloadTaskStatus.Queued }
        .minByOrNull { it.updatedAtMillis } }

    fun claimNextQueuedTasks(limit: Int): List<WorkshopDownloadTaskRecord> = withStoreLock {
        if (limit <= 0) return@withStoreLock emptyList()
        val now = System.currentTimeMillis()
        val claimed = ArrayList<WorkshopDownloadTaskRecord>()
        val current = loadUnlocked().toMutableList()
        val queuedIndexes = current.withIndex()
            .filter { it.value.status == WorkshopDownloadTaskStatus.Queued }
            .sortedBy { it.value.updatedAtMillis }
            .take(limit)
            .map { it.index }
        queuedIndexes.forEach { index ->
            val task = current[index].copy(
                status = WorkshopDownloadTaskStatus.Resolving,
                message = "正在准备下载",
                updatedAtMillis = now,
            )
            current[index] = task
            claimed += task
        }
        if (claimed.isNotEmpty()) saveUnlocked(current)
        claimed
    }

    fun recoverInterruptedTasksWithResult(
        shouldRecover: (WorkshopDownloadTaskRecord) -> Boolean = { true },
    ): List<WorkshopDownloadTaskRecord> = withStoreLock {
        val recovered = ArrayList<WorkshopDownloadTaskRecord>()
        val tasks = loadUnlocked().map { task ->
            if ((task.status.isRunningDownload() || task.status.isStoppingDownload()) && shouldRecover(task)) {
                task.copy(
                    status = WorkshopDownloadTaskStatus.Paused,
                    message = "下载已暂停，可继续",
                    preservePartialDownload = true,
                ).also(recovered::add)
            } else {
                task
            }
        }
        if (recovered.isNotEmpty()) saveUnlocked(tasks)
        return@withStoreLock recovered
    }

    fun recoverLauncherVisibleInterruptedTasksWithResult(
        shouldRecover: (WorkshopDownloadTaskRecord) -> Boolean = { true },
    ): List<WorkshopDownloadTaskRecord> = withStoreLock {
        val recoverableVisibleTaskIds = loadLauncherVisibleUnlocked()
            .filter { task ->
                (task.status.isRunningDownload() || task.status.isStoppingDownload()) &&
                    shouldRecover(task)
            }
            .mapTo(LinkedHashSet()) { task -> task.publishedFileId }
        if (recoverableVisibleTaskIds.isEmpty()) {
            return@withStoreLock emptyList()
        }

        val recovered = ArrayList<WorkshopDownloadTaskRecord>()
        val tasks = loadUnlocked().map { task ->
            if (task.publishedFileId in recoverableVisibleTaskIds &&
                (task.status.isRunningDownload() || task.status.isStoppingDownload()) &&
                shouldRecover(task)
            ) {
                task.copy(
                    status = WorkshopDownloadTaskStatus.Paused,
                    message = "下载已暂停，可继续",
                    preservePartialDownload = true,
                ).also(recovered::add)
            } else {
                task
            }
        }
        if (recovered.isNotEmpty()) saveUnlocked(tasks)
        return@withStoreLock recovered
    }

    private fun loadUnlocked(): List<WorkshopDownloadTaskRecord> {
        cachedTasks(file)?.let { return it }
        val tasks = WorkshopJsonFileStore.readJsonOrDefault(file, emptyList<WorkshopDownloadTaskRecord>()) { text ->
            val root = JSONObject(text)
            val array = root.optJSONArray("tasks") ?: return@readJsonOrDefault emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(item.toTask(item.taskStatus()))
                }
            }
        }
        updateTaskCache(file, tasks)
        return tasks
    }

    private fun loadUnlocked(
        shouldInclude: (WorkshopDownloadTaskRecord) -> Boolean = { true },
    ): List<WorkshopDownloadTaskRecord> {
        return WorkshopJsonFileStore.readJsonOrDefault(file, emptyList()) { text ->
            val root = JSONObject(text)
            val array = root.optJSONArray("tasks") ?: return@readJsonOrDefault emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val status = item.taskStatus()
                    val task = item.toTask(status)
                    if (shouldInclude(task)) {
                        add(task)
                    }
                }
            }
        }
    }

    private fun loadLauncherVisibleUnlocked(): List<WorkshopDownloadTaskRecord> {
        cachedLauncherVisibleTasks(file)?.let { return it }
        val tasks = WorkshopJsonFileStore.readFileOrDefault(file, emptyList()) { source ->
            source.inputStream().buffered().reader(StandardCharsets.UTF_8).use { input ->
                JsonReader(input).use { reader ->
                    val tasks = ArrayList<WorkshopDownloadTaskRecord>()
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "tasks" -> {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    readLauncherVisibleTask(reader)?.let(tasks::add)
                                }
                                reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    tasks
                }
            }
        }
        updateLauncherVisibleTaskCache(file, tasks)
        return tasks
    }

    private fun saveUnlocked(tasks: List<WorkshopDownloadTaskRecord>) {
        val array = JSONArray()
        tasks.forEach { array.put(it.toJson()) }
        WorkshopJsonFileStore.writeAtomically(file, JSONObject().put("tasks", array).toString(2))
        updateTaskCache(file, tasks)
        cachedLauncherVisibleTasks = null
    }

    private fun <T> withStoreLock(block: () -> T): T = WorkshopJsonFileStore.withFileLock(file, lock, block)

    private fun deletedMarkerFile(publishedFileId: ULong): File = File(file.parentFile, "deleted_$publishedFileId")

    companion object {
        private val lock = Any()
        private var cachedTasks: CachedWorkshopDownloadTasks? = null
        private var cachedLauncherVisibleTasks: CachedWorkshopDownloadTasks? = null

        private fun cachedTasks(file: File): List<WorkshopDownloadTaskRecord>? {
            val signature = file.cacheSignature() ?: run {
                cachedTasks = null
                return null
            }
            return cachedTasks
                ?.takeIf { it.signature == signature }
                ?.tasks
        }

        private fun cachedLauncherVisibleTasks(file: File): List<WorkshopDownloadTaskRecord>? {
            val signature = file.cacheSignature() ?: run {
                cachedLauncherVisibleTasks = null
                return null
            }
            return cachedLauncherVisibleTasks
                ?.takeIf { it.signature == signature }
                ?.tasks
        }

        private fun updateTaskCache(file: File, tasks: List<WorkshopDownloadTaskRecord>) {
            val signature = file.cacheSignature() ?: run {
                cachedTasks = null
                return
            }
            cachedTasks = CachedWorkshopDownloadTasks(signature, tasks)
        }

        private fun updateLauncherVisibleTaskCache(file: File, tasks: List<WorkshopDownloadTaskRecord>) {
            val signature = file.cacheSignature() ?: run {
                cachedLauncherVisibleTasks = null
                return
            }
            cachedLauncherVisibleTasks = CachedWorkshopDownloadTasks(signature, tasks)
        }
    }
}

private data class CachedWorkshopDownloadTasks(
    val signature: WorkshopFileCacheSignature,
    val tasks: List<WorkshopDownloadTaskRecord>,
)

private fun readLauncherVisibleTask(reader: JsonReader): WorkshopDownloadTaskRecord? {
    var item: JSONObject? = null
    var status: WorkshopDownloadTaskStatus? = null
    var include = true

    reader.beginObject()
    while (reader.hasNext()) {
        val name = reader.nextName()
        if (!include) {
            reader.skipValue()
            continue
        }
        when (name) {
            "status" -> {
                val statusText = reader.nextString()
                status = runCatching { WorkshopDownloadTaskStatus.valueOf(statusText) }
                    .getOrDefault(WorkshopDownloadTaskStatus.Paused)
                if (!status.shouldShowOnLauncherCards()) {
                    include = false
                } else {
                    item = (item ?: JSONObject()).put(name, statusText)
                }
            }
            "downloadLog" -> reader.skipValue()
            else -> {
                val value = reader.readJsonValue()
                item = (item ?: JSONObject()).put(name, value)
            }
        }
    }
    reader.endObject()

    val taskStatus = status ?: WorkshopDownloadTaskStatus.Paused
    if (!include || !taskStatus.shouldShowOnLauncherCards()) {
        return null
    }
    return item?.toTask(taskStatus)
}

private fun JsonReader.readJsonValue(): Any? {
    return when (peek()) {
        android.util.JsonToken.NULL -> {
            nextNull()
            JSONObject.NULL
        }
        android.util.JsonToken.BOOLEAN -> nextBoolean()
        android.util.JsonToken.NUMBER -> readJsonNumber(nextString())
        android.util.JsonToken.STRING -> nextString()
        android.util.JsonToken.BEGIN_ARRAY -> {
            val array = JSONArray()
            beginArray()
            while (hasNext()) {
                array.put(readJsonValue())
            }
            endArray()
            array
        }
        android.util.JsonToken.BEGIN_OBJECT -> {
            val obj = JSONObject()
            beginObject()
            while (hasNext()) {
                obj.put(nextName(), readJsonValue())
            }
            endObject()
            obj
        }
        else -> {
            skipValue()
            JSONObject.NULL
        }
    }
}

private fun readJsonNumber(value: String): Any {
    return value.toLongOrNull() ?: value.toDoubleOrNull() ?: value
}

fun WorkshopDownloadTaskStatus.isRunningDownload(): Boolean = when (this) {
    WorkshopDownloadTaskStatus.Resolving,
    WorkshopDownloadTaskStatus.Downloading -> true
    WorkshopDownloadTaskStatus.Queued,
    WorkshopDownloadTaskStatus.Pausing,
    WorkshopDownloadTaskStatus.Cancelling,
    WorkshopDownloadTaskStatus.Paused,
    WorkshopDownloadTaskStatus.Completed,
    WorkshopDownloadTaskStatus.Failed,
    WorkshopDownloadTaskStatus.Cancelled -> false
}

fun WorkshopDownloadTaskStatus.isStoppingDownload(): Boolean = when (this) {
    WorkshopDownloadTaskStatus.Pausing,
    WorkshopDownloadTaskStatus.Cancelling -> true
    else -> false
}

fun WorkshopDownloadTaskStatus.isActiveDownload(): Boolean = when (this) {
    WorkshopDownloadTaskStatus.Queued,
    WorkshopDownloadTaskStatus.Resolving,
    WorkshopDownloadTaskStatus.Downloading,
    WorkshopDownloadTaskStatus.Pausing,
    WorkshopDownloadTaskStatus.Cancelling -> true
    else -> false
}

fun WorkshopDownloadTaskStatus.shouldShowOnLauncherCards(): Boolean = when (this) {
    WorkshopDownloadTaskStatus.Queued,
    WorkshopDownloadTaskStatus.Resolving,
    WorkshopDownloadTaskStatus.Downloading,
    WorkshopDownloadTaskStatus.Pausing,
    WorkshopDownloadTaskStatus.Cancelling,
    WorkshopDownloadTaskStatus.Paused,
    WorkshopDownloadTaskStatus.Failed -> true
    WorkshopDownloadTaskStatus.Completed,
    WorkshopDownloadTaskStatus.Cancelled -> false
}

private fun WorkshopDownloadTaskRecord.toJson(): JSONObject = JSONObject()
    .put("publishedFileId", publishedFileId.toString())
    .put("title", title)
    .put("status", status.name)
    .put("message", message)
    .put("updatedAtMillis", updatedAtMillis)
    .put("previewUrl", previewUrl)
    .put("description", description)
    .put("authorName", authorName)
    .put("fileSizeBytes", fileSizeBytes)
    .put("progressPercent", progressPercent ?: JSONObject.NULL)
    .put("downloadedBytes", downloadedBytes)
    .put("totalBytes", totalBytes ?: JSONObject.NULL)
    .put("completedFiles", completedFiles ?: JSONObject.NULL)
    .put("totalFiles", totalFiles ?: JSONObject.NULL)
    .put("completedChunks", completedChunks ?: JSONObject.NULL)
    .put("totalChunks", totalChunks ?: JSONObject.NULL)
    .put("errorClass", errorClass)
    .put("errorMessage", errorMessage)
    .put("errorStackTrace", errorStackTrace)
    .put("downloadLog", downloadLog)
    .put("preservePartialDownload", preservePartialDownload)
    .put("forceAutoImport", forceAutoImport)
    .put("appId", details.summary.appId.toString())
    .put("updatedAtMillisRemote", details.summary.updatedAtMillis)
    .put("downloadCount", details.summary.downloadCount)
    .put("fileUrl", details.fileUrl.orEmpty())
    .put("hcontentFile", details.hcontentFile?.toString().orEmpty())
    .put("depotId", details.depotId?.toString().orEmpty())
    .put("jsonMetadata", details.jsonMetadata)
    .put("fullDescriptionUnavailable", details.fullDescriptionUnavailable)
    .put("dependencies", details.dependencies.toJsonArray())

private fun JSONObject.toTask(
    status: WorkshopDownloadTaskStatus = taskStatus(),
): WorkshopDownloadTaskRecord {
    val publishedFileId = optString("publishedFileId").toULongOrNull() ?: 0u
    val summary = WorkshopItemSummary(
        appId = optString("appId").toUIntOrNull() ?: 646570u,
        publishedFileId = publishedFileId,
        title = optString("title"),
        previewUrl = optString("previewUrl"),
        description = optString("description"),
        authorName = optString("authorName"),
        fileSizeBytes = optLong("fileSizeBytes"),
        updatedAtMillis = optLong("updatedAtMillisRemote"),
        downloadCount = optLong("downloadCount"),
    )
    val details = WorkshopItemDetails(
        summary = summary,
        fileUrl = optString("fileUrl").takeIf { it.isNotBlank() },
        hcontentFile = optString("hcontentFile").toULongOrNull(),
        depotId = optString("depotId").toUIntOrNull(),
        jsonMetadata = optString("jsonMetadata"),
        fullDescriptionUnavailable = optBoolean("fullDescriptionUnavailable", false),
        dependencies = optJSONArray("dependencies").toWorkshopSummaries(),
    )
    return WorkshopDownloadTaskRecord(
        publishedFileId = publishedFileId,
        title = summary.title,
        status = status,
        message = optString("message"),
        updatedAtMillis = optLong("updatedAtMillis"),
        details = details,
        previewUrl = summary.previewUrl,
        description = summary.description,
        authorName = summary.authorName,
        fileSizeBytes = summary.fileSizeBytes,
        progressPercent = optionalInt("progressPercent"),
        downloadedBytes = optLong("downloadedBytes"),
        totalBytes = optionalLong("totalBytes"),
        completedFiles = optionalInt("completedFiles"),
        totalFiles = optionalInt("totalFiles"),
        completedChunks = optionalInt("completedChunks"),
        totalChunks = optionalInt("totalChunks"),
        errorClass = optString("errorClass"),
        errorMessage = optString("errorMessage"),
        errorStackTrace = optString("errorStackTrace"),
        downloadLog = optString("downloadLog"),
        preservePartialDownload = optBoolean("preservePartialDownload", false),
        forceAutoImport = optBoolean("forceAutoImport", false),
    )
}

private fun JSONObject.taskStatus(): WorkshopDownloadTaskStatus {
    return runCatching { WorkshopDownloadTaskStatus.valueOf(optString("status")) }
        .getOrDefault(WorkshopDownloadTaskStatus.Paused)
}

private fun downloadLogTimestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

private fun appendBoundedDownloadLog(currentLog: String, line: String): String {
    val combined = listOf(currentLog, line).filter(String::isNotBlank).joinToString("\n")
    if (combined.length <= MAX_DOWNLOAD_LOG_CHARS) return combined
    val tailSize = (MAX_DOWNLOAD_LOG_CHARS - DOWNLOAD_LOG_TRUNCATED_MARKER.length)
        .coerceAtLeast(0)
    return DOWNLOAD_LOG_TRUNCATED_MARKER + combined.takeLast(tailSize)
}

private const val MAX_DOWNLOAD_LOG_CHARS = 256 * 1024
private const val DOWNLOAD_LOG_TRUNCATED_MARKER = "...(older download log truncated)...\n"

private fun JSONObject.optionalInt(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
private fun JSONObject.optionalLong(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null

private fun List<WorkshopItemSummary>.toJsonArray(): JSONArray = JSONArray().also { array ->
    forEach { item ->
        array.put(
            JSONObject()
                .put("publishedFileId", item.publishedFileId.toString())
                .put("appId", item.appId.toString())
                .put("title", item.title)
                .put("previewUrl", item.previewUrl)
                .put("description", item.description)
                .put("authorName", item.authorName)
                .put("fileSizeBytes", item.fileSizeBytes)
                .put("updatedAtMillis", item.updatedAtMillis)
                .put("downloadCount", item.downloadCount)
        )
    }
}

private fun JSONArray?.toWorkshopSummaries(): List<WorkshopItemSummary> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val publishedFileId = item.optString("publishedFileId").toULongOrNull() ?: continue
            add(
                WorkshopItemSummary(
                    publishedFileId = publishedFileId,
                    appId = item.optString("appId").toUIntOrNull() ?: 646570u,
                    title = item.optString("title"),
                    previewUrl = item.optString("previewUrl"),
                    description = item.optString("description"),
                    authorName = item.optString("authorName"),
                    fileSizeBytes = item.optLong("fileSizeBytes"),
                    updatedAtMillis = item.optLong("updatedAtMillis"),
                    downloadCount = item.optLong("downloadCount"),
                )
            )
        }
    }
}
