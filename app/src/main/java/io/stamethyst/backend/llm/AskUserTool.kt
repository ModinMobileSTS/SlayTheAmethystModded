package io.stamethyst.backend.llm

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonArraySchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal const val ASK_USER = "ask_user"
internal const val MAX_CUSTOM_ANSWER_LENGTH = 300

@Serializable
internal data class AgentQuestionOption(val label: String, val description: String)

@Serializable
internal data class AgentQuestion(
    val header: String,
    val question: String,
    val options: List<AgentQuestionOption>,
    val multiple: Boolean = false,
)

internal data class AgentQuestionRequest(val id: String, val questions: List<AgentQuestion>)

internal class AgentQuestionDismissed : CancellationException("The user dismissed this question")

/** OpenCode V2's pending form lifecycle, scoped to one local agent execution. */
internal class AgentUserQuestions {
    private class Pending(val request: AgentQuestionRequest) {
        val answer = CompletableFuture<List<List<String>>?>()
    }

    private val lock = Any()
    private var active: Pending? = null
    private var closed = false
    private val mutablePending = MutableStateFlow<AgentQuestionRequest?>(null)
    val pending = mutablePending.asStateFlow()

    fun ask(questions: List<AgentQuestion>, checkActive: () -> Unit): List<List<String>> {
        checkActive()
        val entry = synchronized(lock) {
            if (closed) throw CancellationException("Agent execution cancelled")
            check(active == null) { "An agent question is already pending" }
            Pending(AgentQuestionRequest(UUID.randomUUID().toString(), questions)).also {
                active = it
                mutablePending.value = it.request
            }
        }
        try {
            while (true) {
                checkActive()
                val answer = try {
                    // Reply or cancellation wakes immediately. Recheck the execution budget while idle.
                    entry.answer.get(250, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    continue
                }
                checkActive()
                return answer ?: throw AgentQuestionDismissed()
            }
        } finally {
            synchronized(lock) {
                active = null
                mutablePending.value = null
            }
        }
    }

    fun reply(id: String, answers: List<List<String>>): Boolean = synchronized(lock) {
        val entry = active?.takeIf { !closed && it.request.id == id } ?: return false
        val normalized = answers.map { values -> values.map(String::trim) }
        val questions = entry.request.questions
        if (normalized.size != questions.size || questions.zip(normalized).any { (question, values) ->
                val labels = question.options.map { it.label }.toSet()
                values.isEmpty() || (!question.multiple && values.size != 1) ||
                    values.distinct().size != values.size || values.any(String::isBlank) ||
                    values.filterNot(labels::contains).let { custom ->
                        custom.size > 1 || custom.any { it.length > MAX_CUSTOM_ANSWER_LENGTH }
                    }
            }) return false
        entry.answer.complete(normalized)
    }

    fun dismiss(id: String): Boolean = synchronized(lock) {
        active?.takeIf { !closed && it.request.id == id }?.answer?.complete(null) ?: false
    }

    fun cancel() = synchronized(lock) {
        closed = true
        active?.answer?.cancel(false)
        mutablePending.value = null
    }
}

internal class AskUserTool(
    private val questions: AgentUserQuestions,
    private val checkActive: () -> Unit,
    private val onWait: () -> Unit = {},
    private val onReply: () -> Unit = {},
) : AgentTool {
    override val safety = AgentToolSafety.READ_ONLY
    override val specification = spec

    @Serializable
    private data class Input(val questions: List<AgentQuestion>)

    @Serializable
    private data class Output(val answers: List<List<String>>)

    override fun execute(arguments: String): String {
        require(arguments.length <= 65_536) { "ask_user arguments are too large" }
        val prompts = Json.decodeFromString<Input>(arguments).questions.map { question ->
            question.copy(header = question.header.trim(), question = question.question.trim(),
                options = question.options.map { it.copy(label = it.label.trim(), description = it.description.trim()) })
        }
        require(prompts.size in 1..8) { "ask_user requires 1–8 questions" }
        prompts.forEach { prompt ->
            require(prompt.header.length in 1..30 && prompt.question.length in 1..500) {
                "Each question requires a short header (up to 30 characters) and a prompt (up to 500 characters)"
            }
            require(prompt.options.size <= 8 && prompt.options.all { it.label.length in 1..80 && it.description.length <= 300 } &&
                prompt.options.map { it.label }.distinct().size == prompt.options.size) {
                "Provide up to 8 options with distinct, short labels and concise descriptions"
            }
        }
        checkActive()
        onWait()
        val answer = questions.ask(prompts, checkActive)
        onReply()
        return Json.encodeToString(Output(answer))
    }

    companion object {
        val spec: ToolSpecification = ToolSpecification.builder()
            .name(ASK_USER)
            .description(
                "Ask the user one or more questions when clarification or a decision is needed. " +
                    "Call this tool alone before taking actions that depend on the answer. " +
                    "Use the user's language. Put the recommended choice first and label it as recommended. " +
                    "Use multiple=true to allow multiple selections. The UI always adds a custom answer; do not add an Other option. " +
                    "Results contain an answers array in question order, each containing the selected labels and/or custom text.",
            )
            .parameters(JsonObjectSchema.builder()
                .addProperty("questions", JsonArraySchema.builder().items(JsonObjectSchema.builder()
                    .addStringProperty("header", "Short label (1–30 characters)")
                    .addStringProperty("question", "Complete question (1–500 characters)")
                    .addProperty("options", JsonArraySchema.builder().items(JsonObjectSchema.builder()
                        .addStringProperty("label", "Short, unique option label")
                        .addStringProperty("description", "Explanation of the option")
                        .required("label", "description").additionalProperties(false).build()).build())
                    .addBooleanProperty("multiple", "Allow selecting more than one option (default false)")
                    .required("header", "question", "options").additionalProperties(false).build()).build())
                .required("questions").additionalProperties(false).build())
            .build()
    }
}
