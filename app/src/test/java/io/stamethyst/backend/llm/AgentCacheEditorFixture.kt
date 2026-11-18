package io.stamethyst.backend.llm

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import io.stamethyst.ui.aimod.AiEditorMessage
import io.stamethyst.ui.aimod.AiEditorSession
import io.stamethyst.ui.aimod.AiModAgentExecutor
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest

/** Captures the real editor's prompt and complete schemas without opening a JAR or using a network. */
internal object AgentCacheEditorFixture {
    fun request(root: File): ChatRequest {
        val preferences = mutableMapOf<String, SharedPreferences>()
        val context = object : ContextWrapper(Application()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name != "llm_settings") { "No Android keystore in this offline fixture" }
                return preferences.getOrPut(name) { preferences() }
            }
        }
        LlmSettingsRepository(context).set(LlmSettings(
            apiKey = "synthetic-key", baseUrl = "https://synthetic.invalid/v1", modelName = "synthetic",
        ))
        var captured: ChatRequest? = null
        val model = object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse {
                check(captured == null) { "Fixture must capture exactly one main request" }
                captured = request
                return ChatResponse.builder().aiMessage(AiMessage.from("OK")).build()
            }
        }
        AiModAgentExecutor(context, File(root, "never-opened.jar").path, "Synthetic", "synthetic",
            LlmReasoningEffort.OFF, modelFactory = { model }, streamingModelFactory = { null })
            .execute(AiEditorSession("synthetic-cache-session", messages = listOf(
                AiEditorMessage(1, true, "Synthetic cache fixture; reply only OK."),
                AiEditorMessage(2, false, ""),
            )), 2)
        return requireNotNull(captured)
    }

    /** Non-repeating, source-like tool output; stays below the gateway's 51,200-byte output cap. */
    fun toolResult(index: Int): String = (1..400).joinToString("\n") { line ->
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("synthetic-source-$index-$line".toByteArray()).joinToString("") { "%02x".format(it) }
        "$line: private static final String VALUE_$line = \"$digest\";"
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
