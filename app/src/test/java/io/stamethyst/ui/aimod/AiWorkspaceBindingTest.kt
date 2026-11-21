package io.stamethyst.ui.aimod

import io.stamethyst.backend.llm.AgentContextMessage
import io.stamethyst.backend.llm.AgentContextState
import org.junit.Assert.*
import org.junit.Test

class AiWorkspaceBindingTest {
    private fun result(id: String, tool: String = "update_agent_patch_mod") =
        AiToolCall("c", tool, "{}", result = """{"status":"updated","patch_id":"$id"}""")
    private fun session(vararg calls: AiToolCall, saved: String? = null) = AiEditorSession("s",
        messages = calls.mapIndexed { i, call -> AiEditorMessage(i + 1L, false, "", tools = listOf(call)) },
        context = AgentContextState(activePatchId = saved))
    private fun resolve(s: AiEditorSession) = inferWorkspaceBinding(s) { if (it == "patch-lost") null else it }

    @Test fun successfulLegacyWriteAndProtocolEditCanRecoverExistingDraftWithoutCreate() {
        val legacy = AiToolCall("c", "write_agent_workspace_file", "{}", result =
            """{"path":"patch_source/patch-a/src/X.java","bytes":2}""")
        assertEquals("patch-a", resolve(session(legacy)).id)
        val archive = AgentContextMessage(4, "tool", text = """{"patch_id":"patch-b","status":"compiled"}""",
            toolName = "compile_agent_patch_source")
        assertEquals("inferred", resolve(AiEditorSession("s", context = AgentContextState(messages = listOf(archive)))).origin)
    }
    @Test fun conflictsRequireSavedAndFinalTargetToAgree() {
        assertNull(resolve(session(result("patch-a"), result("patch-b"))).id)
        assertNull(resolve(session(result("patch-a"), result("patch-b"), saved = "patch-a")).id)
        assertEquals("patch-b", resolve(session(result("patch-a"), result("patch-b"), saved = "patch-b")).id)
    }
    @Test fun neverBindFromReadsFailureOrMissingWorkspace() {
        val b = resolve(session(result("patch-a", "list_agent_patch_mods"), result("patch-b").copy(failed = true)))
        assertNull(b.id)
        val missing = resolve(session(result("patch-lost")))
        assertEquals("missing", missing.origin)
        assertEquals("patch-lost", missing.id)
    }
}
