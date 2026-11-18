package io.stamethyst.backend.llm

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.output.FinishReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AgentThinkingContentTest {
    @Test fun tagsCanSplitAtEveryBoundaryIncludingOneCharacterChunks() {
        val content = "before<think>private\n推理</think>answer<think>more</think>end"
        val chunks = (0..content.length).map { listOf(content.take(it), content.drop(it)) } +
            listOf(content.map { it.toString() })
        chunks.forEach { parts ->
            assertEquals("beforeanswerend" to "private\n推理more", parse(parts))
        }
    }

    @Test fun ordinaryTextAndIncompleteOpeningTagsAreNotLost() {
        listOf("", "normal answer", "a < b", "<thi", "<thinking>not a tag</thinking>", "unmatched </think>")
            .forEach { assertEquals(it to "", parse(it.map { char -> char.toString() })) }
    }

    @Test fun unclosedThinkingNeverBecomesVisibleText() {
        assertEquals("before" to "private</thi", parse(listOf("before<think>private</thi")))
        assertEquals("" to "", parse(listOf("<think>")))
    }

    @Test fun manyBlocksInOneChunkAreProcessedWithoutLosingText() {
        assertEquals("y".repeat(20_000) to "x".repeat(20_000),
            parse(listOf("<think>x</think>y".repeat(20_000))))
    }

    @Test fun completedResponseMergesNativeReasoningAndPreservesMetadataAndAttributes() {
        val message = AiMessage.builder().text("<think>inline</think>answer")
            .thinking("native").attributes(mapOf("key" to "value")).build()
        val response = ChatResponse.builder().aiMessage(message).finishReason(FinishReason.STOP).build()
        val parsed = parseAgentThinkingContent(response)
        assertEquals("answer", parsed.aiMessage().text())
        assertEquals("native\ninline", parsed.aiMessage().thinking())
        assertEquals(message.attributes(), parsed.aiMessage().attributes())
        assertSame(response.metadata(), parsed.metadata())
    }

    @Test fun responsesWithoutInlineThinkingAreUnchanged() {
        val response = ChatResponse.builder().aiMessage(AiMessage.from("answer")).build()
        assertSame(response, parseAgentThinkingContent(response))
    }

    private fun parse(chunks: List<String>): Pair<String, String> {
        val text = StringBuilder()
        val thinking = StringBuilder()
        val parser = AgentThinkingContentParser({ text.append(it) }, { thinking.append(it) })
        chunks.forEach(parser::append)
        parser.finish()
        return text.toString() to thinking.toString()
    }
}
