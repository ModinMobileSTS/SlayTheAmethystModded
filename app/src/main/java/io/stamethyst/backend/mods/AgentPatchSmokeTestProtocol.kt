package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.config.RuntimePaths
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * One smoke-test run requested by the launcher process.
 *
 * [runId] is echoed back in the result so a stale verdict from an earlier run can never be mistaken
 * for this one.
 */
internal data class AgentPatchSmokeTestRequest(
    val runId: String,
    val parentModId: String,
    val patchJarPath: String,
    val timeoutMs: Long,
)

/** Verdict produced by the `:game` process after running the game with the patch enabled. */
internal data class AgentPatchSmokeTestOutcome(
    val runId: String,
    val passed: Boolean,
    val status: String,
    val reason: String,
    val reachedMainMenu: Boolean,
    val durationMs: Long,
    val error: String = "",
)

/**
 * File hand-off between the launcher process and the `:game` process service.
 *
 * The two run in different processes, so the run cannot be driven by direct calls. The launcher
 * writes a request, binds the service, and polls the result; the service writes the verdict before
 * stopping. All files live under the launcher's STS root, which both processes share.
 */
internal object AgentPatchSmokeTestProtocol {
    fun newRunId(): String = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"

    fun writeRequest(context: Context, request: AgentPatchSmokeTestRequest) {
        val file = RuntimePaths.agentSmokeTestRequestFile(context)
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject()
                .put("run_id", request.runId)
                .put("parent_mod_id", request.parentModId)
                .put("patch_jar_path", request.patchJarPath)
                .put("timeout_ms", request.timeoutMs)
                .toString(2),
            StandardCharsets.UTF_8,
        )
    }

    fun readRequest(context: Context): AgentPatchSmokeTestRequest? = runCatching {
        val file = RuntimePaths.agentSmokeTestRequestFile(context)
        if (!file.isFile) {
            return null
        }
        val json = JSONObject(file.readText(StandardCharsets.UTF_8))
        AgentPatchSmokeTestRequest(
            runId = json.getString("run_id"),
            parentModId = json.getString("parent_mod_id"),
            patchJarPath = json.getString("patch_jar_path"),
            timeoutMs = json.getLong("timeout_ms"),
        )
    }.getOrNull()?.takeIf { it.runId.isNotBlank() }

    fun writeOutcome(context: Context, outcome: AgentPatchSmokeTestOutcome) {
        val file = RuntimePaths.agentSmokeTestResultFile(context)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        temporary.writeText(
            JSONObject()
                .put("run_id", outcome.runId)
                .put("passed", outcome.passed)
                .put("status", outcome.status)
                .put("reason", outcome.reason)
                .put("reached_main_menu", outcome.reachedMainMenu)
                .put("duration_ms", outcome.durationMs)
                .put("error", outcome.error)
                .toString(2),
            StandardCharsets.UTF_8,
        )
        if (file.exists() && !file.delete()) {
            temporary.delete()
            return
        }
        if (!temporary.renameTo(file)) {
            temporary.delete()
        }
    }

    /** Returns the outcome only when it belongs to [runId]. */
    fun readOutcome(context: Context, runId: String): AgentPatchSmokeTestOutcome? = runCatching {
        val file = RuntimePaths.agentSmokeTestResultFile(context)
        if (!file.isFile) {
            return null
        }
        val json = JSONObject(file.readText(StandardCharsets.UTF_8))
        if (json.optString("run_id") != runId) {
            return null
        }
        AgentPatchSmokeTestOutcome(
            runId = runId,
            passed = json.optBoolean("passed", false),
            status = json.optString("status", "failed"),
            reason = json.optString("reason"),
            reachedMainMenu = json.optBoolean("reached_main_menu", false),
            durationMs = json.optLong("duration_ms", 0L),
            error = json.optString("error"),
        )
    }.getOrNull()

    fun clearResult(context: Context) {
        runCatching { RuntimePaths.agentSmokeTestResultFile(context).delete() }
    }

    fun clearRequest(context: Context) {
        runCatching { RuntimePaths.agentSmokeTestRequestFile(context).delete() }
    }

    fun markRunActive(context: Context) {
        runCatching {
            val marker = RuntimePaths.agentSmokeTestRunMarker(context)
            marker.parentFile?.mkdirs()
            marker.writeText("active\n", StandardCharsets.UTF_8)
        }
    }

    fun clearRunActive(context: Context) {
        runCatching { RuntimePaths.agentSmokeTestRunMarker(context).delete() }
    }

    /**
     * True while a smoke test is running.
     *
     * Consulted by `ExitActivity` to suppress the launcher restart the native JVM exit trap would
     * otherwise trigger. A marker older than [RUN_MARKER_MAX_AGE_MS] is treated as abandoned — for
     * example when the launcher process died mid-run — so a stale marker can never permanently
     * disable crash recovery.
     */
    fun isRunActive(context: Context): Boolean {
        val marker = RuntimePaths.agentSmokeTestRunMarker(context)
        if (!runCatching { marker.isFile }.getOrDefault(false)) {
            return false
        }
        val ageMs = System.currentTimeMillis() - marker.lastModified()
        if (ageMs > RUN_MARKER_MAX_AGE_MS) {
            clearRunActive(context)
            return false
        }
        return true
    }

    /** Comfortably longer than the tool's maximum timeout plus shutdown. */
    private const val RUN_MARKER_MAX_AGE_MS = 20 * 60 * 1000L
}
