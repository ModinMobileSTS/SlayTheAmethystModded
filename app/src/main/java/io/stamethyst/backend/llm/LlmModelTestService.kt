package io.stamethyst.backend.llm

import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest

/**
 * Sends a single non-streaming prompt through the configured OpenAI-compatible endpoint so the
 * settings screen can prove that the address, credentials, endpoint and model actually work.
 *
 * It reuses [AgentChatModelFactory] on purpose: the test must exercise the same client, timeouts
 * and request shape as the real agent, otherwise a passing test would not predict agent behaviour.
 * Reasoning is forced off so the probe returns quickly instead of spending its budget thinking.
 */
class LlmModelTestService {
    suspend fun test(
        config: OpenAiCompatibleModelConfig,
        prompt: String = DEFAULT_TEST_PROMPT,
    ): String {
        val model = AgentChatModelFactory.create(config.copy(reasoningEffort = LlmReasoningEffort.OFF))
        val request = ChatRequest.builder()
            .messages(UserMessage.from(prompt))
            .build()
        val response = model.chat(request)
        val text = response.aiMessage().text().orEmpty().trim()
        check(text.isNotEmpty()) { "The model returned an empty response." }
        return text
    }

    companion object {
        const val DEFAULT_TEST_PROMPT = "Reply with a short greeting to confirm the connection works."
    }
}
