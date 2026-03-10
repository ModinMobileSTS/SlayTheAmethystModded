package io.stamethyst.backend.feedback

import android.app.Activity
import android.os.Build
import io.stamethyst.BuildConfig
import io.stamethyst.ui.preferences.LauncherPreferences
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object FeedbackConversationService {
    private const val MULTIPART_LINE_END = "\r\n"
    private const val RESPONSE_PREVIEW_LIMIT = 320

    fun postMessage(
        host: Activity,
        issueNumber: Long,
        messageText: String,
        screenshots: List<FeedbackScreenshotAttachment>
    ): FeedbackPostedComment {
        val endpoint = "${BuildConfig.FEEDBACK_BASE_URL.trim().trimEnd('/')}/api/feedback-issues/message"
        val boundary = "----StsFeedbackMessage${System.currentTimeMillis()}"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 90_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val apiKey = BuildConfig.FEEDBACK_API_KEY.trim()
            if (apiKey.isNotEmpty()) {
                setRequestProperty("X-Feedback-Key", apiKey)
            }
        }

        val playerName = LauncherPreferences.readPlayerName(host)
        val deviceLabel = buildString {
            append(Build.MANUFACTURER.orEmpty().trim())
            if (!Build.MODEL.isNullOrBlank()) {
                append(' ')
                append(Build.MODEL.trim())
            }
        }.trim().ifEmpty { "Android Device" }

        connection.outputStream.use { rawOutput ->
            DataOutputStream(BufferedOutputStream(rawOutput)).use { output ->
                writeTextPart(output, boundary, "issue_number", issueNumber.toString())
                writeTextPart(output, boundary, "message_text", messageText.trim())
                writeTextPart(output, boundary, "player_name", playerName)
                writeTextPart(output, boundary, "app_version", BuildConfig.VERSION_NAME)
                writeTextPart(output, boundary, "device_label", deviceLabel)
                screenshots.forEach { screenshot ->
                    writeFilePart(
                        output = output,
                        boundary = boundary,
                        fieldName = "screenshots",
                        sourceFile = screenshot.file,
                        contentType = guessContentType(screenshot.displayName)
                    )
                }
                output.writeBytes("--$boundary--$MULTIPART_LINE_END")
                output.flush()
            }
        }

        val responseCode = connection.responseCode
        val responseText = readResponseText(connection)
        if (responseCode !in 200..299) {
            throw IOException(
                "发送消息失败 ($responseCode): ${summarizeResponseText(responseText)}"
            )
        }

        val response = parseJsonObject(responseText)
            ?: throw IOException("云函数返回了无效响应。")
        return FeedbackPostedComment(
            commentId = response.optLong("commentId"),
            commentUrl = response.optString("commentUrl").trim().ifEmpty { null },
            createdAtMs = parseInstantMillis(
                response.optString("createdAt").trim().ifEmpty { null }
            ),
            body = messageText.trim(),
            attachments = parseAttachments(response.optJSONArray("attachments")),
            playerName = playerName.ifBlank { "我" }
        )
    }

    fun updateIssueState(
        issueNumber: Long,
        targetState: String
    ): FeedbackIssueStateUpdateResult {
        val endpoint = "${BuildConfig.FEEDBACK_BASE_URL.trim().trimEnd('/')}/api/feedback-issues/state"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 60_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            val apiKey = BuildConfig.FEEDBACK_API_KEY.trim()
            if (apiKey.isNotEmpty()) {
                setRequestProperty("X-Feedback-Key", apiKey)
            }
        }

        val payload = JSONObject().apply {
            put("issue_number", issueNumber)
            put("target_state", targetState)
        }.toString()

        connection.outputStream.use { output ->
            output.write(payload.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }

        val responseCode = connection.responseCode
        val responseText = readResponseText(connection)
        if (responseCode !in 200..299) {
            throw IOException(
                "更新议题状态失败 ($responseCode): ${summarizeResponseText(responseText)}"
            )
        }
        val response = parseJsonObject(responseText)
            ?: throw IOException("云函数返回了无效响应。")
        return FeedbackIssueStateUpdateResult(
            issueNumber = response.optLong("issueNumber", issueNumber),
            issueUrl = response.optString("issueUrl").trim().ifEmpty { null },
            state = response.optString("state").trim().ifEmpty { targetState },
            updatedAtMs = parseInstantMillis(
                response.optString("updatedAt").trim().ifEmpty { null }
            )
        )
    }

    private fun guessContentType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".webp") -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun parseAttachments(array: JSONArray?): List<FeedbackThreadAttachment> {
        if (array == null) {
            return emptyList()
        }
        val attachments = ArrayList<FeedbackThreadAttachment>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("url").trim()
            if (url.isBlank()) {
                continue
            }
            attachments += FeedbackThreadAttachment(
                name = item.optString("name").trim(),
                url = url,
                mimeType = item.optString("mimeType").trim()
            )
        }
        return attachments
    }

    private fun writeTextPart(
        output: DataOutputStream,
        boundary: String,
        fieldName: String,
        value: String
    ) {
        output.writeBytes("--$boundary$MULTIPART_LINE_END")
        output.writeBytes("Content-Disposition: form-data; name=\"$fieldName\"$MULTIPART_LINE_END")
        output.writeBytes("Content-Type: text/plain; charset=UTF-8$MULTIPART_LINE_END")
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

    private fun parseInstantMillis(value: String?): Long {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return System.currentTimeMillis()
        }
        return runCatching { Instant.parse(normalized).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() }
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
}
