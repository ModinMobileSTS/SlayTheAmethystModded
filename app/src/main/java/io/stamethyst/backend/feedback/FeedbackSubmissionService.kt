package io.stamethyst.backend.feedback

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.stamethyst.BuildConfig
import io.stamethyst.backend.crash.ProcessExitInfoCapture
import io.stamethyst.backend.diag.CrashArchiveContext
import io.stamethyst.backend.diag.DiagnosticsArchiveBuilder
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.config.RuntimePaths
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private data class FeedbackEnvironmentSnapshot(
    val packageName: String,
    val versionName: String,
    val versionCode: String,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val hardware: String,
    val androidRelease: String,
    val androidSdkInt: Int,
    val securityPatch: String,
    val supportedAbis: List<String>,
    val cpuModel: String,
    val cpuArch: String,
    val availableMemoryBytes: Long,
    val totalMemoryBytes: Long
)

private data class FeedbackEnabledModSnapshot(
    val modId: String,
    val manifestModId: String,
    val name: String,
    val version: String,
    val required: Boolean,
    val enabled: Boolean,
    val storagePath: String
)

private data class PreparedFeedbackSubmission(
    val issueTitle: String,
    val issueBody: String,
    val requestJson: String,
    val archiveFile: File
)

object FeedbackSubmissionService {
    private const val MULTIPART_LINE_END = "\r\n"
    private const val FEEDBACK_ARCHIVE_DIR = "feedback"
    private const val RESPONSE_PREVIEW_LIMIT = 320

    fun submit(host: Activity, draft: FeedbackSubmissionDraft): FeedbackUploadResult {
        val prepared = prepareSubmission(host, draft)
        try {
            return uploadPreparedSubmission(prepared)
        } finally {
            prepared.archiveFile.delete()
        }
    }

    private fun prepareSubmission(
        host: Activity,
        draft: FeedbackSubmissionDraft
    ): PreparedFeedbackSubmission {
        val environment = collectEnvironmentSnapshot(host)
        val enabledMods = collectEnabledMods(host)
        val logSummary = FeedbackLogAnalyzer.summarizeLatestLog(RuntimePaths.latestLog(host))
        val issueTitle = buildIssueTitle(draft)
        val issueBody = buildIssueBody(
            draft = draft,
            environment = environment,
            enabledMods = enabledMods,
            logSummary = logSummary
        )
        val requestJson = buildRequestJson(
            draft = draft,
            environment = environment,
            enabledMods = enabledMods,
            issueTitle = issueTitle,
            issueBody = issueBody,
            logSummary = logSummary
        )
        val archiveFile = buildFeedbackArchive(
            host = host,
            draft = draft,
            issueTitle = issueTitle,
            issueBody = issueBody,
            requestJson = requestJson,
            enabledMods = enabledMods,
            logSummary = logSummary
        )
        return PreparedFeedbackSubmission(
            issueTitle = issueTitle,
            issueBody = issueBody,
            requestJson = requestJson,
            archiveFile = archiveFile
        )
    }

    private fun uploadPreparedSubmission(
        prepared: PreparedFeedbackSubmission
    ): FeedbackUploadResult {
        val endpoint = BuildConfig.FEEDBACK_ENDPOINT.trim()
        if (endpoint.isEmpty()) {
            throw IOException(
                "Feedback endpoint not configured in this build."
            )
        }

        val boundary = "----StsFeedback${System.currentTimeMillis()}"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 90_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary"
            )
            val apiKey = BuildConfig.FEEDBACK_API_KEY.trim()
            if (apiKey.isNotEmpty()) {
                setRequestProperty("X-Feedback-Key", apiKey)
            }
        }

        connection.outputStream.use { rawOutput ->
            DataOutputStream(BufferedOutputStream(rawOutput)).use { output ->
                writeTextPart(output, boundary, "payload_json", prepared.requestJson, "application/json; charset=UTF-8")
                writeTextPart(output, boundary, "issue_title", prepared.issueTitle, "text/plain; charset=UTF-8")
                writeTextPart(output, boundary, "issue_body", prepared.issueBody, "text/markdown; charset=UTF-8")
                writeFilePart(output, boundary, "bundle", prepared.archiveFile, "application/zip")
                output.writeBytes("--$boundary--$MULTIPART_LINE_END")
                output.flush()
            }
        }

        val responseCode = connection.responseCode
        val responseText = readResponseText(connection)
        if (responseCode !in 200..299) {
            throw IOException(
                "Feedback upload failed ($responseCode): ${summarizeResponseText(responseText)}"
            )
        }

        val responseObject = parseJsonObject(responseText)
        return FeedbackUploadResult(
            issueUrl = resolveIssueUrl(responseObject),
            issueNumber = resolveIssueNumber(responseObject),
            rawResponse = responseText
        )
    }

    private fun buildIssueTitle(draft: FeedbackSubmissionDraft): String {
        val prefix = when (draft.category) {
            FeedbackCategory.FEATURE_REQUEST -> "[${draft.category.issuePrefix}]"
            FeedbackCategory.LAUNCHER_BUG -> "[${draft.category.issuePrefix}]"
            FeedbackCategory.GAME_BUG -> {
                val issueType = draft.gameIssueType
                if (issueType == null) {
                    "[${draft.category.issuePrefix}]"
                } else {
                    "[${draft.category.issuePrefix}][${issueType.issuePrefix}]"
                }
            }
        }
        val normalizedSummary = draft.summary.trim().replace('\n', ' ')
        return "$prefix $normalizedSummary".take(120)
    }

    private fun buildIssueBody(
        draft: FeedbackSubmissionDraft,
        environment: FeedbackEnvironmentSnapshot,
        enabledMods: List<FeedbackEnabledModSnapshot>,
        logSummary: FeedbackLogSummary
    ): String {
        return buildString {
            append("## 概要\n")
            append(draft.summary.trim()).append("\n\n")
            append("## 反馈类型\n")
            append("- 类型：").append(draft.category.displayName).append('\n')
            if (draft.category == FeedbackCategory.GAME_BUG) {
                append("- 问题表现：").append(draft.gameIssueType?.displayName ?: "未选择").append('\n')
                append("- 是否为最近一次运行复现：")
                    .append(
                        when (draft.reproducedOnLastRun) {
                            true -> "是"
                            false -> "否（用户仍选择继续提交）"
                            null -> "未填写"
                        }
                    )
                    .append('\n')
                append("- 怀疑模组：").append(resolveSuspectedModsLabel(draft)).append('\n')
            }
            append('\n')
            append("## 详细描述\n")
            append(draft.detail.trim()).append("\n\n")
            append("## 复现步骤\n")
            if (draft.reproductionSteps.isBlank()) {
                append("(未提供)\n\n")
            } else {
                append(draft.reproductionSteps.trim()).append("\n\n")
            }
            append("## 环境信息\n")
            append("- 启动器版本：").append(environment.versionName).append(" (").append(environment.versionCode).append(")\n")
            append("- Android：").append(environment.androidRelease).append(" / SDK ").append(environment.androidSdkInt).append('\n')
            append("- 设备：").append(listOf(environment.manufacturer, environment.model).joinToString(" ")).append('\n')
            append("- CPU：").append(environment.cpuModel).append('\n')
            append("- CPU 架构：").append(environment.cpuArch).append('\n')
            append("- 内存：").append(formatBytes(environment.availableMemoryBytes)).append(" 可用 / ").append(formatBytes(environment.totalMemoryBytes)).append(" 总量\n")
            append("- ABI：").append(environment.supportedAbis.joinToString(", ").ifBlank { "unknown" }).append('\n')
            draft.submissionStatus?.let { status ->
                append("- 状态：").append(status).append('\n')
            }
            append('\n')
            append("## 启用模组快照\n")
            if (enabledMods.isEmpty()) {
                append("(当前没有启用模组)\n\n")
            } else {
                enabledMods.forEach { mod ->
                    append("- ")
                        .append(mod.name.ifBlank { mod.manifestModId.ifBlank { mod.modId } })
                        .append(" | modid=")
                        .append(mod.manifestModId.ifBlank { mod.modId.ifBlank { "unknown" } })
                        .append(" | version=")
                        .append(mod.version.ifBlank { "unknown" })
                        .append('\n')
                }
                append('\n')
            }
            append("## latest.log 关键行\n")
            if (logSummary.interestingLines.isEmpty()) {
                append("(未解析到明显异常关键字，完整日志见附件压缩包)\n\n")
            } else {
                logSummary.interestingLines.forEach { line ->
                    append("- ").append(line).append('\n')
                }
                append('\n')
            }
            append("## 附件\n")
            append("- 诊断压缩包：已上传 multipart bundle\n")
            append("- 截图数量：").append(draft.screenshots.size).append('\n')
        }
    }

    private fun buildRequestJson(
        draft: FeedbackSubmissionDraft,
        environment: FeedbackEnvironmentSnapshot,
        enabledMods: List<FeedbackEnabledModSnapshot>,
        issueTitle: String,
        issueBody: String,
        logSummary: FeedbackLogSummary
    ): String {
        val root = JSONObject()
        root.put("source", "slay-the-amethyst-android")
        root.put("submittedAt", System.currentTimeMillis())
        root.put("issue", JSONObject().apply {
            put("title", issueTitle)
            put("body", issueBody)
        })
        root.put("feedback", JSONObject().apply {
            put("category", draft.category.name)
            put("categoryLabel", draft.category.displayName)
            put("summary", draft.summary.trim())
            put("detail", draft.detail.trim())
            put("reproductionSteps", draft.reproductionSteps.trim())
            put("email", draft.email?.trim().orEmpty())
            put("notifyByEmail", draft.emailNotificationsEnabled)
            put("reproducedOnLastRun", draft.reproducedOnLastRun)
            put("gameIssueType", draft.gameIssueType?.name ?: "")
            put("gameIssueTypeLabel", draft.gameIssueType?.displayName ?: "")
            put("suspectUnknown", draft.suspectUnknown)
            put(
                "suspectedMods",
                JSONArray().apply {
                    draft.suspectedMods.forEach { mod ->
                        put(JSONObject().apply {
                            put("key", mod.key)
                            put("modId", mod.modId)
                            put("manifestModId", mod.manifestModId)
                            put("name", mod.name)
                            put("version", mod.version)
                            put("required", mod.required)
                            put("storagePath", mod.storagePath)
                        })
                    }
                }
            )
            put(
                "screenshots",
                JSONArray().apply {
                    draft.screenshots.forEach { attachment ->
                        put(JSONObject().apply {
                            put("name", attachment.displayName)
                            put("sizeBytes", attachment.file.length())
                        })
                    }
                }
            )
        })
        root.put("environment", JSONObject().apply {
            put("packageName", environment.packageName)
            put("versionName", environment.versionName)
            put("versionCode", environment.versionCode)
            put("manufacturer", environment.manufacturer)
            put("brand", environment.brand)
            put("model", environment.model)
            put("device", environment.device)
            put("product", environment.product)
            put("hardware", environment.hardware)
            put("androidRelease", environment.androidRelease)
            put("androidSdkInt", environment.androidSdkInt)
            put("securityPatch", environment.securityPatch)
            put("supportedAbis", JSONArray(environment.supportedAbis))
            put("cpuModel", environment.cpuModel)
            put("cpuArch", environment.cpuArch)
            put("availableMemoryBytes", environment.availableMemoryBytes)
            put("totalMemoryBytes", environment.totalMemoryBytes)
            draft.submissionStatus?.let { status ->
                put("status", status)
            }
        })
        root.put(
            "enabledMods",
            JSONArray().apply {
                enabledMods.forEach { mod ->
                    put(JSONObject().apply {
                        put("modId", mod.modId)
                        put("manifestModId", mod.manifestModId)
                        put("name", mod.name)
                        put("version", mod.version)
                        put("required", mod.required)
                        put("enabled", mod.enabled)
                        put("storagePath", mod.storagePath)
                    })
                }
            }
        )
        root.put("latestLogSummary", JSONObject().apply {
            put("interestingLines", JSONArray(logSummary.interestingLines))
            put("tailLines", JSONArray(logSummary.tailLines))
        })
        return root.toString(2)
    }

    private fun buildFeedbackArchive(
        host: Activity,
        draft: FeedbackSubmissionDraft,
        issueTitle: String,
        issueBody: String,
        requestJson: String,
        enabledMods: List<FeedbackEnabledModSnapshot>,
        logSummary: FeedbackLogSummary
    ): File {
        val baseArchiveFile = createBaseDiagnosticsArchive(host, draft)
        val archiveFile = allocateFeedbackArchiveFile(host)
        val writtenEntries = LinkedHashSet<String>()

        FileOutputStream(archiveFile, false).use { output ->
            ZipOutputStream(BufferedOutputStream(output)).use { zipOutput ->
                ZipInputStream(BufferedInputStream(FileInputStream(baseArchiveFile))).use { zipInput ->
                    while (true) {
                        val entry = zipInput.nextEntry ?: break
                        val entryName = entry.name
                        if (!writtenEntries.add(entryName)) {
                            continue
                        }
                        val newEntry = ZipEntry(entryName)
                        if (entry.time > 0L) {
                            newEntry.time = entry.time
                        }
                        zipOutput.putNextEntry(newEntry)
                        copyStream(zipInput, zipOutput)
                        zipOutput.closeEntry()
                    }
                }

                writeTextEntry(zipOutput, writtenEntries, "sts/feedback/issue_title.txt", issueTitle)
                writeTextEntry(zipOutput, writtenEntries, "sts/feedback/issue_body.md", issueBody)
                writeTextEntry(zipOutput, writtenEntries, "sts/feedback/request.json", requestJson)
                writeTextEntry(
                    zipOutput,
                    writtenEntries,
                    "sts/feedback/enabled_mods.txt",
                    buildEnabledModsText(enabledMods)
                )
                writeTextEntry(
                    zipOutput,
                    writtenEntries,
                    "sts/feedback/latest_log_summary.txt",
                    buildLogSummaryText(logSummary)
                )
                draft.screenshots.forEach { attachment ->
                    val entryName = buildUniqueEntryName(
                        writtenEntries = writtenEntries,
                        baseEntryName = "sts/feedback/screenshots/${sanitizeFileName(attachment.displayName)}"
                    )
                    writeFileEntry(zipOutput, entryName, attachment.file)
                }
            }
        }

        baseArchiveFile.delete()
        return archiveFile
    }

    private fun createBaseDiagnosticsArchive(
        host: Activity,
        draft: FeedbackSubmissionDraft
    ): File {
        val crashContext = if (
            draft.category == FeedbackCategory.GAME_BUG &&
            draft.gameIssueType == GameIssueType.CRASH
        ) {
            buildCrashArchiveContext(host)
        } else {
            null
        }
        return if (crashContext != null) {
            DiagnosticsArchiveBuilder.createCrashShareArchive(host, crashContext).archiveFile
        } else {
            DiagnosticsArchiveBuilder.createJvmLogShareArchive(host).archiveFile
        }
    }

    private fun buildCrashArchiveContext(context: Context): CrashArchiveContext? {
        val exitSummary = ProcessExitInfoCapture.peekLatestInterestingProcessExitInfo(context) ?: return null
        val detail = buildString {
            append(exitSummary.reasonName)
            if (exitSummary.description.isNotBlank()) {
                append(": ").append(exitSummary.description)
            }
        }
        return CrashArchiveContext(
            code = exitSummary.status,
            isSignal = exitSummary.isSignal,
            detail = detail
        )
    }

    private fun allocateFeedbackArchiveFile(context: Context): File {
        val dir = File(context.cacheDir, FEEDBACK_ARCHIVE_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Failed to create feedback cache dir: ${dir.absolutePath}")
        }
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        val archiveFile = File(dir, "sts-feedback-report-${formatter.format(Date())}.zip")
        if (archiveFile.exists() && !archiveFile.delete()) {
            throw IOException("Failed to replace feedback archive: ${archiveFile.absolutePath}")
        }
        return archiveFile
    }

    private fun collectEnvironmentSnapshot(host: Activity): FeedbackEnvironmentSnapshot {
        val launcherVersion = resolveLauncherVersion(host)
        val activityManager = host.getSystemService(Activity.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        val availableMemoryBytes: Long
        val totalMemoryBytes: Long
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo)
            availableMemoryBytes = memoryInfo.availMem
            totalMemoryBytes = memoryInfo.totalMem
        } else {
            availableMemoryBytes = 0L
            totalMemoryBytes = 0L
        }
        return FeedbackEnvironmentSnapshot(
            packageName = host.packageName,
            versionName = launcherVersion.first,
            versionCode = launcherVersion.second,
            manufacturer = normalizeInfoValue(Build.MANUFACTURER),
            brand = normalizeInfoValue(Build.BRAND),
            model = normalizeInfoValue(Build.MODEL),
            device = normalizeInfoValue(Build.DEVICE),
            product = normalizeInfoValue(Build.PRODUCT),
            hardware = normalizeInfoValue(Build.HARDWARE),
            androidRelease = normalizeInfoValue(Build.VERSION.RELEASE),
            androidSdkInt = Build.VERSION.SDK_INT,
            securityPatch = normalizeInfoValue(Build.VERSION.SECURITY_PATCH),
            supportedAbis = Build.SUPPORTED_ABIS.map { it.trim() }.filter { it.isNotEmpty() },
            cpuModel = resolveCpuModel(),
            cpuArch = resolveCpuArch(),
            availableMemoryBytes = availableMemoryBytes,
            totalMemoryBytes = totalMemoryBytes
        )
    }

    private fun collectEnabledMods(host: Activity): List<FeedbackEnabledModSnapshot> {
        return ModManager.listInstalledMods(host)
            .asSequence()
            .filter { it.enabled }
            .map { mod ->
                FeedbackEnabledModSnapshot(
                    modId = mod.modId,
                    manifestModId = mod.manifestModId,
                    name = mod.name,
                    version = mod.version,
                    required = mod.required,
                    enabled = mod.enabled,
                    storagePath = mod.jarFile.absolutePath
                )
            }
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun resolveLauncherVersion(context: Context): Pair<String, String> {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val versionName = normalizeInfoValue(packageInfo.versionName)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                packageInfo.versionCode.toString()
            }
            versionName to versionCode
        } catch (_: Throwable) {
            "unknown" to "unknown"
        }
    }

    private fun normalizeInfoValue(value: String?): String {
        return value?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
    }

    private fun resolveCpuModel(): String {
        val fromProcCpuInfo = readCpuModelFromProcCpuInfo()
        if (!fromProcCpuInfo.isNullOrBlank()) {
            return fromProcCpuInfo
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val socModel = normalizeInfoValue(Build.SOC_MODEL)
            if (socModel != "unknown") {
                return socModel
            }
        }
        val hardware = normalizeInfoValue(Build.HARDWARE)
        if (hardware != "unknown") {
            return hardware
        }
        val model = normalizeInfoValue(Build.MODEL)
        if (model != "unknown") {
            return model
        }
        return "unknown"
    }

    private fun readCpuModelFromProcCpuInfo(): String? {
        val cpuInfoFile = File("/proc/cpuinfo")
        if (!cpuInfoFile.isFile) {
            return null
        }

        var hardware: String? = null
        var modelName: String? = null
        var processor: String? = null
        var cpuModel: String? = null
        return try {
            cpuInfoFile.forEachLine { rawLine ->
                val separator = rawLine.indexOf(':')
                if (separator <= 0) {
                    return@forEachLine
                }
                val key = rawLine.substring(0, separator).trim().lowercase(Locale.ROOT)
                val value = rawLine.substring(separator + 1).trim()
                if (value.isEmpty()) {
                    return@forEachLine
                }
                when (key) {
                    "hardware" -> if (hardware.isNullOrBlank()) hardware = value
                    "model name" -> if (modelName.isNullOrBlank()) modelName = value
                    "processor" -> if (processor.isNullOrBlank()) processor = value
                    "cpu model" -> if (cpuModel.isNullOrBlank()) cpuModel = value
                }
            }
            hardware ?: modelName ?: processor ?: cpuModel
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveCpuArch(): String {
        val supportedAbis = Build.SUPPORTED_ABIS
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val abiText = supportedAbis.joinToString(", ")
        val osArch = normalizeInfoValue(System.getProperty("os.arch"))
        return when {
            abiText.isNotEmpty() && osArch != "unknown" -> "$osArch (ABI: $abiText)"
            abiText.isNotEmpty() -> abiText
            osArch != "unknown" -> osArch
            else -> "unknown"
        }
    }

    private fun buildEnabledModsText(enabledMods: List<FeedbackEnabledModSnapshot>): String {
        if (enabledMods.isEmpty()) {
            return "(当前没有启用模组)\n"
        }
        return buildString {
            enabledMods.forEach { mod ->
                append("- ")
                    .append(mod.name.ifBlank { mod.manifestModId.ifBlank { mod.modId } })
                    .append(" | modid=")
                    .append(mod.manifestModId.ifBlank { mod.modId.ifBlank { "unknown" } })
                    .append(" | version=")
                    .append(mod.version.ifBlank { "unknown" })
                    .append(" | required=")
                    .append(mod.required)
                    .append('\n')
            }
        }
    }

    private fun buildLogSummaryText(logSummary: FeedbackLogSummary): String {
        return buildString {
            append("Interesting lines:\n")
            if (logSummary.interestingLines.isEmpty()) {
                append("(none)\n")
            } else {
                logSummary.interestingLines.forEach { line ->
                    append("- ").append(line).append('\n')
                }
            }
            append('\n')
            append("Tail lines:\n")
            if (logSummary.tailLines.isEmpty()) {
                append("(none)\n")
            } else {
                logSummary.tailLines.forEach { line ->
                    append(line).append('\n')
                }
            }
        }
    }

    private fun resolveSuspectedModsLabel(draft: FeedbackSubmissionDraft): String {
        if (draft.suspectUnknown) {
            return "不确定"
        }
        if (draft.suspectedMods.isEmpty()) {
            return "(未选择)"
        }
        return draft.suspectedMods.joinToString(", ") { mod ->
            mod.name.ifBlank { mod.manifestModId.ifBlank { mod.modId.ifBlank { "unknown" } } }
        }
    }

    private fun writeTextPart(
        output: DataOutputStream,
        boundary: String,
        fieldName: String,
        value: String,
        contentType: String
    ) {
        output.writeBytes("--$boundary$MULTIPART_LINE_END")
        output.writeBytes("Content-Disposition: form-data; name=\"$fieldName\"$MULTIPART_LINE_END")
        output.writeBytes("Content-Type: $contentType$MULTIPART_LINE_END")
        output.writeBytes(MULTIPART_LINE_END)
        output.write(value.toByteArray(StandardCharsets.UTF_8))
        output.writeBytes(MULTIPART_LINE_END)
    }

    private fun writeFilePart(
        output: DataOutputStream,
        boundary: String,
        fieldName: String,
        sourceFile: File,
        contentType: String
    ) {
        output.writeBytes("--$boundary$MULTIPART_LINE_END")
        output.writeBytes(
            "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"${sourceFile.name}\"$MULTIPART_LINE_END"
        )
        output.writeBytes("Content-Type: $contentType$MULTIPART_LINE_END")
        output.writeBytes(MULTIPART_LINE_END)
        FileInputStream(sourceFile).use { input ->
            copyStream(input, output)
        }
        output.writeBytes(MULTIPART_LINE_END)
    }

    private fun readResponseText(connection: HttpURLConnection): String {
        val stream = try {
            if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
        } catch (_: Throwable) {
            connection.errorStream
        } ?: return ""
        stream.use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                BufferedReader(reader).use { buffered ->
                    return buffered.readText()
                }
            }
        }
    }

    private fun summarizeResponseText(responseText: String): String {
        val normalized = responseText.trim()
        if (normalized.isEmpty()) {
            return "empty response"
        }
        return if (normalized.length > RESPONSE_PREVIEW_LIMIT) {
            normalized.take(RESPONSE_PREVIEW_LIMIT) + "..."
        } else {
            normalized
        }
    }

    private fun parseJsonObject(text: String): JSONObject? {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return null
        }
        return try {
            JSONTokener(normalized).nextValue() as? JSONObject
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveIssueUrl(responseObject: JSONObject?): String? {
        if (responseObject == null) {
            return null
        }
        val directCandidates = listOf(
            responseObject.optString("issueUrl"),
            responseObject.optString("issue_url"),
            responseObject.optString("html_url"),
            responseObject.optString("url")
        )
        directCandidates.firstOrNull { it.isNotBlank() }?.let { return it }
        val issueObject = responseObject.optJSONObject("issue") ?: return null
        return listOf(
            issueObject.optString("issueUrl"),
            issueObject.optString("issue_url"),
            issueObject.optString("html_url"),
            issueObject.optString("url")
        ).firstOrNull { it.isNotBlank() }
    }

    private fun resolveIssueNumber(responseObject: JSONObject?): Long? {
        if (responseObject == null) {
            return null
        }
        val direct = listOf(
            responseObject.optLong("issueNumber", -1L),
            responseObject.optLong("issue_number", -1L),
            responseObject.optLong("number", -1L)
        ).firstOrNull { it > 0L }
        if (direct != null) {
            return direct
        }
        val issueObject = responseObject.optJSONObject("issue") ?: return null
        return listOf(
            issueObject.optLong("issueNumber", -1L),
            issueObject.optLong("issue_number", -1L),
            issueObject.optLong("number", -1L)
        ).firstOrNull { it > 0L }
    }

    private fun writeTextEntry(
        zipOutput: ZipOutputStream,
        writtenEntries: MutableSet<String>,
        entryName: String,
        content: String
    ) {
        if (!writtenEntries.add(entryName)) {
            return
        }
        zipOutput.putNextEntry(ZipEntry(entryName))
        zipOutput.write(content.toByteArray(StandardCharsets.UTF_8))
        zipOutput.closeEntry()
    }

    private fun writeFileEntry(
        zipOutput: ZipOutputStream,
        entryName: String,
        sourceFile: File
    ) {
        val entry = ZipEntry(entryName)
        if (sourceFile.lastModified() > 0L) {
            entry.time = sourceFile.lastModified()
        }
        zipOutput.putNextEntry(entry)
        FileInputStream(sourceFile).use { input ->
            copyStream(input, zipOutput)
        }
        zipOutput.closeEntry()
    }

    private fun buildUniqueEntryName(
        writtenEntries: MutableSet<String>,
        baseEntryName: String
    ): String {
        val dotIndex = baseEntryName.lastIndexOf('.')
        val prefix = if (dotIndex > 0) baseEntryName.substring(0, dotIndex) else baseEntryName
        val suffix = if (dotIndex > 0) baseEntryName.substring(dotIndex) else ""
        var candidate = baseEntryName
        var index = 2
        while (writtenEntries.contains(candidate)) {
            candidate = "$prefix-$index$suffix"
            index++
        }
        return candidate
    }

    private fun sanitizeFileName(name: String): String {
        val trimmed = name.trim().ifEmpty { "attachment.bin" }
        val sanitized = buildString(trimmed.length) {
            trimmed.forEach { ch ->
                if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') {
                    append(ch)
                } else {
                    append('_')
                }
            }
        }
        return sanitized.ifEmpty { "attachment.bin" }
    }

    private fun copyStream(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            if (read == 0) {
                continue
            }
            output.write(buffer, 0, read)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) {
            return "unknown"
        }
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${value.toLong()} ${units[unitIndex]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }
}
