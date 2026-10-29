package io.stamethyst.ui.aimod

/** Merge stream deltas in linear time, including histories written by older versions. */
internal fun coalesceAiParts(parts: List<AiMessagePart>): List<AiMessagePart> {
    if ((1 until parts.size).none { parts[it].kind in setOf(AiMessagePartKind.TEXT, AiMessagePartKind.THINKING) && parts[it].kind == parts[it - 1].kind }) return parts
    return buildList<AiMessagePart> {
        var kind: String? = null
        val text = StringBuilder()
        fun flush() {
            kind?.let { add(AiMessagePart(it, text.toString())) }
            text.setLength(0)
            kind = null
        }
        parts.forEach { part ->
            if (part.kind !in setOf(AiMessagePartKind.TEXT, AiMessagePartKind.THINKING)) {
                flush()
                add(part)
            } else {
                if (kind != part.kind) flush()
                kind = part.kind
                text.append(part.content)
            }
        }
        flush()
    }
}

/** Bounded text layout units. Reopen fenced code when a code block crosses a unit boundary. */
internal fun splitAiMarkdown(text: String, limit: Int = 2048, preserveFences: Boolean = true): List<String> {
    require(limit >= 16)
    if (text.length <= limit) return listOf(text)
    return buildList {
        val chunk = StringBuilder()
        var fence: String? = null
        var fenceMarker: String? = null
        fun flush() {
            if (chunk.isEmpty()) return
            add(chunk.toString() + (fenceMarker?.let { "\n$it" } ?: ""))
            chunk.setLength(0)
            fence?.let { chunk.append(it).append('\n') }
        }
        text.lineSequence().forEach { line ->
            val trimmed = line.trimStart()
            val marker = when {
                !preserveFences -> null
                trimmed.startsWith("```") -> trimmed.takeWhile { it == '`' }
                trimmed.startsWith("~~~") -> trimmed.takeWhile { it == '~' }
                else -> null
            }
            if (chunk.length + line.length > limit && chunk.isNotEmpty()) flush()
            if (marker != null && fence == null) {
                fence = line
                fenceMarker = marker
            } else if (marker != null && marker.first() == fenceMarker?.first() && marker.length >= fenceMarker!!.length) {
                fence = null
                fenceMarker = null
            }
            // A single minified line must not create an unbounded Text layout either.
            var start = 0
            while (line.length - start > limit) {
                var end = start + limit
                if (line[end - 1].isHighSurrogate() && line[end].isLowSurrogate()) end--
                chunk.append(line, start, end)
                flush()
                start = end
            }
            chunk.append(line, start, line.length).append('\n')
        }
        if (chunk.isNotEmpty()) add(chunk.toString().trimEnd('\n'))
    }
}

internal data class AiTranscriptRow(
    val key: String,
    val message: AiEditorMessage,
    val footer: Boolean,
    val original: AiEditorMessage,
)

/**
 * Copies [incoming] into [current] and keeps the instances that did not change. The store returns
 * the previous instance for every message whose `changed_revision` did not move, so identity is the
 * correct comparison here; `!=` would walk every stored part (tens of thousands in a legacy
 * conversation) on the main thread and find nothing new.
 *
 * Returns true when the list changed, which is what bumps the transcript revision.
 */
internal fun applyIncomingMessages(
    current: MutableList<AiEditorMessage>,
    incoming: List<AiEditorMessage>,
): Boolean {
    var changed = false
    incoming.forEachIndexed { position, message ->
        if (position >= current.size) {
            current.add(message)
            changed = true
        } else if (current[position] !== message) {
            current[position] = message
            changed = true
        }
    }
    while (current.size > incoming.size) {
        current.removeAt(current.lastIndex)
        changed = true
    }
    return changed
}

/**
 * Keeps the previous row instances for rows whose rendered content did not change. A poll hands
 * the UI a freshly deserialized message, so without this every row of the streaming message would
 * be a new object and Compose would recompose all of them on each poll. Only the rows that actually
 * grew (the streaming tail, and anything split differently after it) are replaced.
 */
internal fun reuseTranscriptRows(
    previous: List<AiTranscriptRow>,
    next: List<AiTranscriptRow>,
): List<AiTranscriptRow> {
    if (previous.isEmpty()) return next
    val byKey = HashMap<String, AiTranscriptRow>(previous.size * 2)
    previous.forEach { byKey[it.key] = it }
    var reused = false
    val merged = next.map { row ->
        val old = byKey[row.key]
        if (old != null && sameRenderedRow(old, row)) {
            reused = true
            old
        } else {
            row
        }
    }
    return if (reused) merged else next
}

/**
 * Compares only what a row renders. `original` is excluded on purpose: it holds the whole message
 * (tens of thousands of parts in legacy conversations) and is never read by the row's UI.
 */
private fun sameRenderedRow(previous: AiTranscriptRow, next: AiTranscriptRow): Boolean {
    if (previous.footer != next.footer) return false
    val a = previous.message
    val b = next.message
    return a.id == b.id &&
        a.fromUser == b.fromUser &&
        a.notice == b.notice &&
        a.streaming == b.streaming &&
        a.failed == b.failed &&
        a.errorMessage == b.errorMessage &&
        a.retryMessage == b.retryMessage &&
        a.modelName == b.modelName &&
        a.elapsedMs == b.elapsedMs &&
        a.contextTokens == b.contextTokens &&
        a.text == b.text &&
        a.thinking == b.thinking &&
        a.parts == b.parts &&
        a.tools == b.tools &&
        a.attachments == b.attachments
}

/** Each row is independently lazy; one agent turn may contain thousands of tool/text blocks. */
internal fun buildAiTranscriptRows(message: AiEditorMessage): List<AiTranscriptRow> {
    val rows = mutableListOf<AiTranscriptRow>()
    val tools = message.tools.associateBy { it.id }
    fun add(part: AiMessagePart) {
        val chunks = if (part.kind == AiMessagePartKind.TEXT) splitAiMarkdown(part.content,
            preserveFences = !message.fromUser && !message.notice) else listOf(part.content)
        chunks.forEach { content ->
            val first = rows.isEmpty()
            rows += AiTranscriptRow("${message.id}:${rows.size}", message.copy(
                text = if (message.fromUser || message.notice) content else "",
                thinking = "", parts = if (message.fromUser || message.notice) emptyList() else listOf(part.copy(content = content)),
                tools = if (part.kind == AiMessagePartKind.TOOL) listOfNotNull(tools[part.toolId]) else emptyList(),
                attachments = if (first) message.attachments else emptyList(),
                failed = false,
                executionSteps = emptyList(),
            ), false, message)
        }
    }
    if (message.fromUser || message.notice) add(AiMessagePart(AiMessagePartKind.TEXT, message.text))
    else if (message.parts.isNotEmpty()) coalesceAiParts(message.parts).forEach(::add)
    else {
        message.tools.forEach { tool ->
            if (tool.precedingText.isNotBlank()) add(AiMessagePart(AiMessagePartKind.TEXT, tool.precedingText))
            add(AiMessagePart(AiMessagePartKind.TOOL, toolId = tool.id))
        }
        if (message.thinking.isNotBlank()) add(AiMessagePart(AiMessagePartKind.THINKING, message.thinking))
        if (message.text.isNotBlank()) add(AiMessagePart(AiMessagePartKind.TEXT, message.text))
    }
    val compactionOnly = message.parts.isNotEmpty() && message.parts.all {
        it.kind == AiMessagePartKind.COMPACTION || it.kind == AiMessagePartKind.COMPACTION_REQUEST
    }
    if (!message.notice && !compactionOnly) rows += AiTranscriptRow("${message.id}:footer", message.copy(text = "", thinking = "", parts = emptyList(), tools = emptyList(), attachments = emptyList(), executionSteps = emptyList()), true, message)
    return rows
}
