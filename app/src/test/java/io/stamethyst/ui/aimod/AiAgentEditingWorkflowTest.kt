package io.stamethyst.ui.aimod

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.stamethyst.R
import io.stamethyst.backend.llm.LlmReasoningEffort
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AiAgentEditingWorkflowTest {
    @get:Rule val temporary = TemporaryFolder(
        File(if (File("src/main").isDirectory) "../agent-tmp" else "agent-tmp").apply { mkdirs() },
    )

    @Test fun executorAdvertisesTheEditToolWithItsLocalizedDescription() {
        val root = temporary.newFolder()
        val context = object : ContextWrapper(Application()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?): File = File(root, "external").apply { mkdirs() }
        }
        val executor = AiModAgentExecutor(context, File(root, "parent.jar").path, "Parent", "parent", LlmReasoningEffort.LOW)
        val tools = executor.availableTools()
        assertEquals(1, tools.count { it.name == "edit_agent_workspace_file" })
        assertEquals(R.string.ai_mod_editor_tool_desc_edit_agent_workspace_file,
            tools.single { it.name == "edit_agent_workspace_file" }.descriptionRes)
        assertTrue(tools.any { it.name == "write_agent_workspace_file" })
        assertTrue(tools.any { it.name == "validate_agent_patch_mod" })
        assertTrue(tools.any { it.name == "smoke_test_agent_patch_mod" })
    }

    @Test fun emittedPromptPrefersLocalEditsAndEvidenceReuseWhileKeepingRuntimeVerification() {
        // These are assertions on the actual prompt output contract, not implementation-source checks.
        val prompt = buildAiEditorSystemPrompt("Parent", "parent", File("/synthetic/workspace"))
        assertTrue(prompt.contains("Prefer edit_agent_workspace_file"))
        assertTrue(prompt.contains("expected_sha256"))
        assertTrue(prompt.contains("decompile_agent_class prepares its own"))
        assertTrue(prompt.contains("Reuse reliable skill, API, source and target evidence"))
        assertTrue(prompt.contains("an independent validate_agent_patch_mod call is optional"))
        assertFalse(prompt.contains("Run validate_agent_patch_mod after compiling"))
        assertTrue(prompt.contains("After packaging, call smoke_test_agent_patch_mod"))
        assertTrue(prompt.contains("Ask before enabling a packaged patch mod"))
    }
}
