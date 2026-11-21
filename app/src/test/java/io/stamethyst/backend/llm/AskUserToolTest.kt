package io.stamethyst.backend.llm

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AskUserToolTest {
    @Test fun replyIsValidatedAndWakesOnlyTheMatchingExecution() = withWorker { worker ->
        AgentRequestControl().use { control ->
            AgentRequestControl().use { other ->
                val result = worker.submit<String> { AskUserTool(control.userQuestions, control::checkActive).execute(ARGUMENTS) }
                val pending = awaitQuestion(control)
                assertFalse(result.isDone)
                assertFalse(other.userQuestions.dismiss(pending.id))
                assertFalse(other.userQuestions.reply(pending.id, listOf(listOf("A"), listOf("B"))))
                assertFalse(control.userQuestions.reply("stale", listOf(listOf("A"), listOf("B"))))
                assertFalse(control.userQuestions.reply(pending.id, listOf(listOf("A"))))
                assertFalse(control.userQuestions.reply(pending.id, listOf(listOf("A", "another"), listOf("B"))))
                assertFalse(control.userQuestions.reply(pending.id, listOf(listOf("A"), listOf("B", "B"))))
                assertFalse(control.userQuestions.reply(pending.id, listOf(listOf(" "), listOf("B"))))
                val reply = listOf(listOf("A"), listOf("B", " 自定义答案 "))
                assertTrue(control.userQuestions.reply(pending.id, reply))
                assertFalse(control.userQuestions.reply(pending.id, reply))
                assertEquals("""{"answers":[["A"],["B","自定义答案"]]}""", result.get(3, TimeUnit.SECONDS))
                assertNull(control.userQuestions.pending.value)
            }
        }
    }

    @Test fun dismissAndStopHaveDistinctOutcomesAndClearThePendingRequest() = withWorker { worker ->
        listOf(true, false).forEach { dismiss ->
            AgentRequestControl().use { control ->
                val result = worker.submit<String> { AskUserTool(control.userQuestions, control::checkActive).execute(ARGUMENTS) }
                val pending = awaitQuestion(control)
                if (dismiss) assertTrue(control.userQuestions.dismiss(pending.id)) else control.cancel()
                val failure = assertThrows(ExecutionException::class.java) { result.get(3, TimeUnit.SECONDS) }.cause
                assertTrue(failure is CancellationException)
                assertEquals(dismiss, failure is AgentQuestionDismissed)
                assertNull(control.userQuestions.pending.value)
                assertFalse(control.userQuestions.dismiss(pending.id))
            }
        }
    }

    @Test fun malformedQuestionsNeverOpenTheDockAndCustomOnlyIsAllowed() = withWorker { worker ->
        AgentRequestControl().use { control ->
            val tool = AskUserTool(control.userQuestions, control::checkActive)
            assertThrows(IllegalArgumentException::class.java) { tool.execute("""{"questions":[]}""") }
            assertThrows(IllegalArgumentException::class.java) {
                tool.execute("""{"questions":[{"question":"   ","header":"Direction","options":[]}]}""")
            }
            assertThrows(IllegalArgumentException::class.java) {
                tool.execute("""{"questions":[{"question":"Which?","header":"Direction","options":[
                    {"label":"A","description":"One"},{"label":" A ","description":"Two"}]}]}""")
            }
            assertNull(control.userQuestions.pending.value)
            val result = worker.submit<String> {
                tool.execute("""{"questions":[{"question":"What now?","header":"Direction","options":[]}]}""")
            }
            val pending = awaitQuestion(control)
            assertTrue(control.userQuestions.reply(pending.id, listOf(listOf("continue manually"))))
            assertEquals("""{"answers":[["continue manually"]]}""", result.get(3, TimeUnit.SECONDS))
        }
    }

    @Test fun mixedBatchesRunNeitherTheQuestionNorASiblingWriteRegardlessOfOrder() {
        AgentRequestControl().use { control ->
            val write = object : AgentTool {
                override val specification = ToolSpecification.builder().name("write").description("test").build()
                override val safety = AgentToolSafety.WORKSPACE_WRITE
                override fun execute(arguments: String): String = error("A write ran before the user's decision")
            }
            val registry = AgentToolRegistry(listOf(write, AskUserTool(control.userQuestions, control::checkActive)),
                policyGate = AgentToolPolicyGate(setOf(AgentToolSafety.READ_ONLY, AgentToolSafety.WORKSPACE_WRITE)))
            val sideEffect = ToolExecutionRequest.builder().id("write-1").name("write").arguments("{}").build()
            val question = askUserCall()
            listOf(listOf(sideEffect, question), listOf(question, sideEffect)).forEach { batch ->
                val errors = mutableListOf<String>()
                AgentToolScheduler(registry).execute(batch) { _, output -> errors.add(output.text) }
                assertEquals(List(2) { """{"error":"ask_user_must_be_alone"}""" }, errors)
                assertNull(control.userQuestions.pending.value)
            }
        }
    }

    @Test fun unansweredQuestionInRecoveredHistoryNeverAssumesAnAnswer() {
        val recovered = AgentContextState(messages = listOf(
            AgentContextMessage(1, "assistant", "", calls = listOf(AgentContextToolCall("q1", ASK_USER, ARGUMENTS))),
        )).closeInterruptedTools()
        assertEquals("q1", recovered.messages.last().callId)
        assertEquals("No user answer was recorded. Do not assume a choice.", recovered.messages.last().text)
    }

    companion object {
        const val ARGUMENTS = """{"questions":[{"header":"Direction","question":"Which way?","options":[{"label":"A","description":"First"}]},{"header":"Preferences","question":"Which extras?","multiple":true,"options":[{"label":"B","description":"Second"}]}]}"""

        internal fun askUserCall(): ToolExecutionRequest =
            ToolExecutionRequest.builder().id("q1").name(ASK_USER).arguments(ARGUMENTS).build()

        internal fun awaitQuestion(control: AgentRequestControl) = runBlocking {
            withTimeout(3_000) { control.userQuestions.pending.filterNotNull().first() }
        }

        internal fun withWorker(test: (java.util.concurrent.ExecutorService) -> Unit) {
            val worker = Executors.newSingleThreadExecutor()
            try { test(worker) } finally { worker.shutdownNow() }
        }
    }
}
