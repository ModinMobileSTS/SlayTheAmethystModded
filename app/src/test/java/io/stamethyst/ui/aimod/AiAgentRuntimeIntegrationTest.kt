package io.stamethyst.ui.aimod

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import io.stamethyst.backend.llm.*
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiAgentRuntimeIntegrationTest {
    @get:Rule val temporary = TemporaryFolder(
        File(if (File("src/main").isDirectory) "../agent-tmp" else "agent-tmp").apply { mkdirs() },
    )

    @Test fun executorSwitchesMainAndOffSummaryTogetherWithEachModelsFullBudget() {
        listOf(false, true).forEach { useStreaming ->
            val context = context(temporary.newFolder())
            val settings = LlmSettings(apiKey = "test", baseUrl = "https://local.invalid/v1", modelName = "wide",
                reasoningEffort = LlmReasoningEffort.HIGH)
            LlmSettingsRepository(context).set(settings)
            assertEquals("test", context.getSharedPreferences("llm_settings_fallback", Context.MODE_PRIVATE).getString("api_key", null))
            assertEquals("settings must survive repository reload", settings, LlmSettingsRepository(context).get())
            AiContextLimits.set(context, AiContextLimits.key(settings.baseUrl, "wide"), 200_000,
                AgentModelLimits(contextWindow = 60_000, maxOutputTokens = 1_000))
            AiContextLimits.set(context, AiContextLimits.key(settings.baseUrl, "small"), 200_000,
                AgentModelLimits(contextWindow = 32_000, maxOutputTokens = 700))
            val sent = mutableListOf<Triple<String, LlmReasoningEffort, Int>>()
            fun response(config: OpenAiCompatibleModelConfig, request: ChatRequest): ChatResponse {
                assertNotNull("bind on actual SDK thread", AgentRequestScope.current())
                sent += Triple(config.modelName, config.reasoningEffort, request.parameters().maxOutputTokens())
                val text = if (config.reasoningEffort == LlmReasoningEffort.OFF) "## Objective\nContinue safely" else "done"
                return ChatResponse.builder().aiMessage(AiMessage.from(text)).build()
            }
            lateinit var executor: AiModAgentExecutor
            fun manager(): AgentContextManager = AiModAgentExecutor::class.java.getDeclaredField("contextManager")
                .apply { isAccessible = true }.get(executor) as AgentContextManager
            var phase = 0
            val budgets = mutableListOf<Int>()
            val control = AgentRequestControl()
            executor = AiModAgentExecutor(context, File(context.filesDir, "parent.jar").path, "Parent", "parent",
                LlmReasoningEffort.HIGH, requestControl = control,
                modelFactory = { config -> object : ChatModel {
                    override fun doChat(request: ChatRequest) = response(config, request)
                } },
                streamingModelFactory = { config -> if (!useStreaming) null else object : StreamingChatModel {
                    override fun doChat(request: ChatRequest, handler: StreamingChatResponseHandler) {
                        handler.onCompleteResponse(response(config, request))
                    }
                } },
                receiveInbox = { idle ->
                    when {
                        !idle && phase == 0 -> {
                            phase = 1
                            AiDeliveredInput(AiEditorMessage(5, true, "use small"), 6, "small", LlmReasoningEffort.HIGH)
                        }
                        idle && phase == 1 -> {
                            budgets += manager().budget.inputLimit
                            manager().prepare(manual = true)
                            phase = 2
                            AiDeliveredInput(AiEditorMessage(7, true, "use wide"), 8, "wide", LlmReasoningEffort.HIGH)
                        }
                        idle && phase == 2 -> {
                            budgets += manager().budget.inputLimit
                            manager().prepare(manual = true)
                            phase = 3
                            null
                        }
                        else -> null
                    }
                },
            )
            val result = executor.execute(AiEditorSession("session", messages = listOf(
                AiEditorMessage(1, true, "start"), AiEditorMessage(2, false, "old work"),
                AiEditorMessage(3, true, "continue"), AiEditorMessage(4, false, ""),
            )), 4)
            assertEquals("done", result.text)
            assertEquals(listOf(31_300, 59_000), budgets)
            assertEquals(listOf(
                Triple("small", LlmReasoningEffort.HIGH, 700), Triple("small", LlmReasoningEffort.OFF, 700),
                Triple("wide", LlmReasoningEffort.HIGH, 1_000), Triple("wide", LlmReasoningEffort.OFF, 1_000),
            ), sent)
            assertEquals(200_000, AiContextLimits.explicitInputLimit(context, AiContextLimits.key(settings.baseUrl, "wide")))
            assertEquals(LlmReasoningEffort.HIGH, LlmSettingsRepository(context).get().reasoningEffort)
            assertThrows(CancellationException::class.java) { control.checkActive() }
        }
    }

    @Test fun serviceDestructionCancelsExecutionControlEvenWithoutWorkerInterruption() {
        val service = AiAgentExecutionService()
        AgentRequestControl().use { control ->
            AgentRequestControl().use { other ->
                val scope = control.newRequest(AgentRequestTimeoutPolicy(10_000))
                val unrelated = other.newRequest(AgentRequestTimeoutPolicy(10_000))
                AiAgentExecutionService::class.java.getDeclaredField("workerRequestControl").apply { isAccessible = true }.set(service, control)
                service.onDestroy()
                assertTrue(scope.stopReason is CancellationException)
                assertNull(unrelated.stopReason)
                unrelated.close()
            }
        }
    }

    @Test fun serviceStopTargetsOnlyTheMatchingExecutionAndCancelsBeforeWorkerReturns() {
        val service = AiAgentExecutionService()
        AgentRequestControl().use { control ->
            val scope = control.newRequest(AgentRequestTimeoutPolicy(10_000))
            AiAgentExecutionService::class.java.getDeclaredField("workerRequestControl").apply { isAccessible = true }.set(service, control)
            AiAgentExecutionService::class.java.getDeclaredField("workerJobId").apply { isAccessible = true }.set(service, "active")
            assertFalse(service.cancelRunningJob("other"))
            assertNull(scope.stopReason)
            assertTrue(service.cancelRunningJob("active"))
            assertTrue(scope.stopReason is CancellationException)
        }
    }

    private fun context(root: File): Context {
        val preferences = mutableMapOf<String, SharedPreferences>()
        return object : ContextWrapper(Application()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                // Android's mocked Base64/keystore cannot implement encrypted preferences faithfully.
                // Exercise the repository's real fallback path, rather than a half-created cipher store.
                check(name != "llm_settings") { "No Android keystore in this offline fixture" }
                return preferences.getOrPut(name) { preferences() }
            }
        }
    }

    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when {
                method.name.startsWith("put") -> { values[args[0] as String] = args[1]; editor }
                method.name == "clear" -> { values.clear(); editor }
                method.name == "remove" -> { values.remove(args[0]); editor }
                method.name == "commit" -> true
                else -> null
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getAll" -> values.toMap()
                "contains" -> values.containsKey(args[0])
                "getString", "getInt", "getLong", "getBoolean", "getFloat", "getStringSet" -> values[args[0]] ?: args[1]
                else -> null
            }
        } as SharedPreferences
    }
}
