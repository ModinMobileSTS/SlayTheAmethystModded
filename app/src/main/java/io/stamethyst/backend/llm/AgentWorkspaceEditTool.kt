package io.stamethyst.backend.llm

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Exact, single-match edits; shares the write/delete tools' canonical patch-source boundary. */
class AgentWorkspaceEditTool(
    private val workspaceRoot: File,
    private val maxBytes: Long = MAX_WORKSPACE_TEXT_BYTES,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("edit_agent_workspace_file")
        .description(
            "Prefer this tool for small changes to existing UTF-8 files under patch_source/<patch_id>/. " +
                "Replace exactly one literal old_text with new_text (empty new_text deletes it). " +
                "Empty or non-unique old_text is rejected. Use read_agent_workspace_file's sha256 as " +
                "expected_sha256 to reject stale edits; utf8_chars reads preserve exact text and line endings. " +
                "Line-number prefixes from normal reads are not file content. No newline normalization is done. " +
                "Returns only a short summary and the new sha256. Use write_agent_workspace_file to create files.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("path", "Workspace-relative path of an existing file under patch_source/<patch_id>/.")
                .addStringProperty("old_text", "Nonempty literal text that must occur exactly once; include context to disambiguate.")
                .addStringProperty("new_text", "Literal replacement text, preserving the intended line endings; may be empty.")
                .addStringProperty("expected_sha256", "Optional whole-file SHA-256 from the latest read or successful edit.")
                .required("path", "old_text", "new_text")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety = AgentToolSafety.WORKSPACE_WRITE
    override val concurrency = AgentToolConcurrency.EXCLUSIVE

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments", "Provide path, old_text and new_text as JSON strings.")
        val path = json.string("path")?.takeIf(String::isNotBlank)
            ?: return failure("invalid_arguments", "Provide a nonempty workspace-relative path.")
        val oldText = json.string("old_text")
            ?: return failure("invalid_arguments", "Provide old_text as a JSON string.")
        val newText = json.string("new_text")
            ?: return failure("invalid_arguments", "Provide new_text as a JSON string; use an empty string to delete text.")
        if (oldText.isEmpty()) return failure("empty_old_text", "Read the file and provide a nonempty, unique text span.")
        val expectedHash = if ("expected_sha256" in json) {
            json.string("expected_sha256")?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
                ?: return failure("invalid_sha256", "Use the complete 64-character sha256 returned by a text read or edit.")
        } else null
        if (oldText.length > maxBytes || newText.length > maxBytes) return tooLarge()
        val (oldBytes, replacement) = runCatching {
            encodeWorkspaceUtf8(oldText) to encodeWorkspaceUtf8(newText)
        }.getOrElse {
            return failure("invalid_text", "old_text and new_text must be valid Unicode text without NUL characters.")
        }
        val target = resolvePatchSourceFile(workspaceRoot, path)
            ?: return failure("path_outside_patch_source", "Use a path under patch_source/<patch_id>/; source/ and escaping symlinks are read-only.")
        if (!target.isFile) return failure("file_not_found", "Edit requires an existing file; use write_agent_workspace_file to create it.")
        if (target.length() > maxBytes) return tooLarge()
        val original = runCatching { target.readBytes() }.getOrElse {
            return failure("read_failed", "Check the file path and access, then read it again before editing.")
        }
        if (original.size > maxBytes) return tooLarge()
        val hash = workspaceTextSha256(original)
        if (expectedHash != null && !expectedHash.equals(hash, ignoreCase = true)) return conflict()
        val text = runCatching { decodeWorkspaceUtf8(original) }.getOrElse {
            return failure("invalid_text", "The file is not valid UTF-8 text without NUL characters; use the binary read/write tools if intended.")
        }
        val start = text.indexOf(oldText)
        if (start < 0) return failure("text_not_found", "Read the latest file with encoding=utf8_chars and copy an exact span including whitespace.")
        // Start one character later to detect overlapping matches too (e.g. 'aa' in 'aaa').
        if (text.indexOf(oldText, start + 1) >= 0) {
            return failure("multiple_matches", "Include more surrounding text in old_text so it matches exactly once.")
        }
        if (oldText == newText) return failure("no_change", "old_text and new_text are identical; provide a changed replacement.")
        if (original.size.toLong() - oldBytes.size + replacement.size > maxBytes) return tooLarge()
        // Valid UTF-8 round-trips exactly: BOM, CRLF, mixed newlines and final-newline absence survive.
        val updated = encodeWorkspaceUtf8(text.replaceRange(start, start + oldText.length, newText))
        var temporary: File? = null
        try {
            temporary = Files.createTempFile(target.parentFile.toPath(), ".agent-edit-", ".tmp").toFile()
            temporary.writeBytes(updated)
            // Recheck both the boundary and bytes immediately before the atomic replacement. The
            // scheduler already excludes other agent tools; this also catches changes during IO.
            if (resolvePatchSourceFile(workspaceRoot, path) != target || !target.readBytes().contentEquals(original)) {
                return conflict()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.io.IOException) {
            return failure("write_failed", "Atomic replacement failed; read the file again and check available storage and access before retrying.")
        } finally {
            temporary?.delete()
        }
        return buildJsonObject {
            put("status", JsonPrimitive("edited"))
            put("path", JsonPrimitive(path))
            put("replacements", JsonPrimitive(1))
            put("bytes", JsonPrimitive(updated.size))
            put("sha256", JsonPrimitive(workspaceTextSha256(updated)))
        }.toString()
    }

    private fun conflict() = failure("file_conflict", "The file changed. Read it again and rebuild the edit using the new sha256; do not retry with the stale text/hash.")
    private fun tooLarge() = failure("file_too_large", "Text edits are limited to $maxBytes bytes for both the input and result.")
    private fun failure(code: String, hint: String) = buildJsonObject {
        put("error", JsonPrimitive(code))
        put("hint", JsonPrimitive(hint))
    }.toString()

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

internal const val MAX_WORKSPACE_TEXT_BYTES = 16L * 1024L * 1024L

/** Hash the same raw bytes used for text reads, never the line-numbered or paged output. */
internal fun workspaceTextSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun decodeWorkspaceUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString().also { require('\u0000' !in it) }

private fun encodeWorkspaceUtf8(text: String): ByteArray {
    require('\u0000' !in text)
    val buffer = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(text))
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}
