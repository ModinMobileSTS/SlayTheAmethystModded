package io.stamethyst.backend.llm

import org.junit.Assert.*
import org.junit.Test

class AgentToolFailureTest {
    @Test fun compilerAndPreflightFailuresIncludeTheActualDiagnostic() {
        assertEquals("failed: missing symbol", agentToolFailure("""{"status":"failed","diagnostics":"missing symbol"}"""))
        assertEquals("failed: target does not exist", agentToolFailure("""{"status":"failed","issues":["target does not exist"]}"""))
    }

    @Test fun smokeTestFailureIncludesItsReason() {
        assertEquals("crashed: mod initialization failed", agentToolFailure("""{"status":"crashed","passed":false,"reason":"mod initialization failed"}"""))
    }

    @Test fun normalReadResultsAndWarningsAreNeverMistakenForFailures() {
        assertNull(agentToolFailure("""{"content":"error: compilation failed","status":"read"}"""))
        assertNull(agentToolFailure("""{"status":"valid","warnings":["optional target missing"]}"""))
        assertNull(agentToolFailure("""{"status":"passed","passed":true}"""))
        assertNull(agentToolFailure("not JSON, just file contents"))
    }

    @Test fun toolErrorMessageIsBoundedWithoutCopyingSourceOrFullLogs() {
        val failure = agentToolFailure("""{"error":"decompile_failed","message":"cannot resolve class","content":"private source","log_excerpt":"full logs"}""")!!
        assertEquals("decompile_failed: cannot resolve class", failure)
        assertFalse(failure.contains("private source"))
        assertFalse(failure.contains("full logs"))
        assertEquals(480, agentToolFailure("""{"error":"${"x".repeat(2_000)}"}""")!!.length)
    }
}
