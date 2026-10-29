package io.stamethyst.ui.aimod

import kotlinx.serialization.Serializable
import io.stamethyst.backend.llm.LlmReasoningEffort
import androidx.compose.runtime.mutableStateListOf

/** UI-only admissions: visible before storage runs, without inventing a database revision. */
internal class AiPendingInbox {
    private data class Admission(val sessionId: String, val item: AiInboxItem)
    private val admissions = mutableStateListOf<Admission>()

    fun add(sessionId: String, item: AiInboxItem) {
        admissions += Admission(sessionId, item)
    }

    fun complete(sessionId: String, itemId: String) {
        admissions.removeAll { it.sessionId == sessionId && it.item.id == itemId }
    }

    fun itemsFor(sessionId: String, stored: List<AiInboxItem>): List<AiInboxItem> {
        val storedIds = stored.mapTo(HashSet()) { it.id }
        return stored + admissions.filter { it.sessionId == sessionId && it.item.id !in storedIds }.map { it.item }
    }
}

@Serializable
internal enum class AiInboxDelivery { QUEUE, STEER }

@Serializable
internal data class AiInboxItem(
    val id: String,
    val text: String,
    val attachments: List<AiAttachment> = emptyList(),
    val delivery: AiInboxDelivery = AiInboxDelivery.QUEUE,
    val modelName: String? = null,
    val reasoningEffort: LlmReasoningEffort? = null,
)

internal data class AiDeliveredInput(
    val message: AiEditorMessage,
    val assistantMessageId: Long,
    val modelName: String? = null,
    val reasoningEffort: LlmReasoningEffort? = null,
)

/** Steers overtake queued work; queue inputs are admitted one at a time at idle. */
internal fun AiEditorSession.nextInboxItem(idle: Boolean): AiInboxItem? =
    inbox.firstOrNull { it.delivery == AiInboxDelivery.STEER }
        ?: inbox.firstOrNull().takeIf { idle }

internal fun AiEditorSession.deliverInbox(item: AiInboxItem, modelName: String): AiEditorSession {
    if (inbox.none { it.id == item.id }) return this
    val id = (messages.maxOfOrNull { it.id } ?: 0L) + 1L
    return copy(
        inbox = inbox.filterNot { it.id == item.id },
        messages = messages.map { if (it.streaming) it.copy(streaming = false) else it } +
            AiEditorMessage(id, true, item.text, item.attachments) +
            AiEditorMessage(id + 1, false, "", streaming = true, modelName = item.modelName ?: modelName),
    )
}
