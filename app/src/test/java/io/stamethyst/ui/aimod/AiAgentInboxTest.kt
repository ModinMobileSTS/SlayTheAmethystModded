package io.stamethyst.ui.aimod

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class AiAgentInboxTest {
    @Test fun queuedSettingsAndAttachmentsSurviveSerializationAndDelivery() {
        val item = AiInboxItem("configured", "next", listOf(AiAttachment("test.txt", "payload")),
            modelName = "next-model", reasoningEffort = io.stamethyst.backend.llm.LlmReasoningEffort.HIGH)
        val restored = Json.decodeFromString<AiInboxItem>(Json.encodeToString(item))
        assertEquals(item, restored)
        val delivered = AiEditorSession("test", inbox = listOf(restored)).deliverInbox(restored, "old-model")
        assertEquals("next-model", delivered.messages.last().modelName)
        assertEquals(item.attachments, delivered.messages.first().attachments)
    }

    @Test fun legacyInboxKeepsAttachmentsAndDefaultsSettingsToNull() {
        val item = Json.decodeFromString<AiInboxItem>("""{"id":"old","text":"queued","attachments":[{"name":"a.txt","content":"a"}]}""")
        assertNull(item.modelName)
        assertNull(item.reasoningEffort)
        assertEquals("a.txt", item.attachments.single().name)
    }

    private val queued = AiInboxItem("q", "next task")
    private val steer = AiInboxItem("s", "change direction", delivery = AiInboxDelivery.STEER)

    @Test fun admissionIsVisibleBeforeStorageAndSurvivesUnchangedPolls() {
        val pending = AiPendingInbox()
        val session = AiEditorSession("test", revision = 7)
        pending.add(session.id, queued)
        assertEquals(listOf(queued), pending.itemsFor(session.id, session.inbox))
        assertEquals(listOf(queued), pending.itemsFor(session.id, session.copy().inbox))
        assertEquals(7L, session.revision)
        assertTrue(pending.itemsFor("other", emptyList()).isEmpty())
    }

    @Test fun committedPollDoesNotDuplicateAdmissionOrOverwriteItsEdits() {
        val pending = AiPendingInbox()
        pending.add("test", queued)
        val edited = queued.copy(text = "edited", delivery = AiInboxDelivery.STEER)
        assertEquals(listOf(edited), pending.itemsFor("test", listOf(edited)))
        pending.complete("test", queued.id)
        assertEquals(listOf(edited), pending.itemsFor("test", listOf(edited)))
        // A newer poll may have already delivered the item when the write callback arrives.
        assertTrue(pending.itemsFor("test", emptyList()).isEmpty())
    }

    @Test fun completingOneAdmissionKeepsOtherMessagesAndConversations() {
        val pending = AiPendingInbox()
        pending.add("test", queued)
        pending.add("test", steer)
        pending.add("other", queued)
        assertEquals(listOf(queued, steer), pending.itemsFor("test", emptyList()))
        pending.complete("test", queued.id)
        assertEquals(listOf(steer), pending.itemsFor("test", emptyList()))
        assertEquals(listOf(queued), pending.itemsFor("other", emptyList()))
    }

    @Test fun queueWaitsUntilIdleAndSteerOvertakesQueue() {
        val session = AiEditorSession("test", inbox = listOf(queued, steer))
        assertEquals(steer, session.nextInboxItem(false))
        assertEquals(steer, session.nextInboxItem(true))
        val remaining = session.copy(inbox = listOf(queued))
        assertNull(remaining.nextInboxItem(false))
        assertEquals(queued, remaining.nextInboxItem(true))
    }

    @Test fun deliveryClosesPreviousOutputAndAppendsUserAndAssistantAtomically() {
        val attachment = AiAttachment("note.txt", "important")
        val input = steer.copy(attachments = listOf(attachment))
        val session = AiEditorSession("test", messages = listOf(
            AiEditorMessage(1, true, "original"),
            AiEditorMessage(2, false, "working", streaming = true),
        ), inbox = listOf(queued, input))
        val next = session.deliverInbox(input, "model")
        assertEquals(listOf(queued), next.inbox)
        assertFalse(next.messages[1].streaming)
        assertEquals(listOf(attachment), next.messages[2].attachments)
        assertTrue(next.messages[2].fromUser)
        assertEquals(4L, next.messages.last().id)
        assertTrue(next.messages.last().streaming)
        assertEquals(next, next.deliverInbox(input, "model"))
    }

    @Test fun inboxRoundTripsAndOldSessionsDefaultToEmpty() {
        val session = AiEditorSession("test", inbox = listOf(queued, steer))
        assertEquals(session, Json.decodeFromString<AiEditorSession>(Json.encodeToString(session)))
        assertTrue(Json.decodeFromString<AiEditorSession>("""{"id":"old"}""").inbox.isEmpty())
    }
}
