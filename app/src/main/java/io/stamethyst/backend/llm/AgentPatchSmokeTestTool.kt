package io.stamethyst.backend.llm

import android.content.Context
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import io.stamethyst.backend.mods.AgentPatchModManager
import io.stamethyst.backend.mods.AgentPatchSmokeTest
import io.stamethyst.backend.mods.AgentPatchWorkspace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Launches the game with the parent mod plus one packaged AI patch and reports whether the main
 * menu was reached.
 *
 * This is the runtime half of the patch workflow: compilation and bytecode preflight prove the
 * patch targets existing code, but only a real launch proves the patch does not break mod loading.
 * The launch uses the parent mod, the patch, the parent's prerequisites, and the launcher's built-in
 * mods, so an unrelated optional mod cannot influence the result.
 */
class AgentPatchSmokeTestTool(
    private val context: Context,
    private val parentModId: String,
    private val parentJar: File,
    private val workspace: () -> AgentPatchWorkspace?,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("smoke_test_agent_patch_mod")
        .description(
            "Launch the game once with the parent mod, one packaged patch mod, the parent mod's " +
                "prerequisites, and the launcher's built-in mods, then report whether the game reached " +
                "the main menu. This is the only check that proves the patch does not break mod loading; " +
                "compilation and validation cannot catch runtime failures. The game opens and closes " +
                "itself, takes roughly 1-4 minutes, and blocks until it finishes. Call it after " +
                "package_agent_patch_mod, and fix the patch and re-run it if it fails.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty(
                    "patch_id",
                    "Packaged patch revision to test. Defaults to the current session's patch revision.",
                )
                .addStringProperty(
                    "timeout_seconds",
                    "How long to wait for the main menu before failing. Defaults to 240.",
                )
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.PATCH_SMOKE_TEST

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val patchId = json["patch_id"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: workspace()?.patchId
            ?: return failure("patch_id_required: pass a patch_id from list_agent_patch_mods")
        val timeoutSeconds = json["timeout_seconds"]?.jsonPrimitive?.content
            ?.toLongOrNull()
            ?.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
            ?: DEFAULT_TIMEOUT_SECONDS
        val patchInfo = AgentPatchModManager.listPackaged(context, parentModId)
            .firstOrNull { it.patchId == patchId }
            ?: return failure("patch_not_found: call package_agent_patch_mod first, or pass a patch_id from list_agent_patch_mods")
        val result = AgentPatchSmokeTest.run(
            context = context,
            parentModId = parentModId,
            parentJar = parentJar,
            patchJar = patchInfo.jarFile,
            timeoutMs = timeoutSeconds * 1_000L,
        )
        return buildJsonObject {
            put("status", JsonPrimitive(result.status))
            put("passed", JsonPrimitive(result.passed))
            put("reached_main_menu", JsonPrimitive(result.reachedMainMenu))
            put("reason", JsonPrimitive(result.reason))
            put("duration_ms", JsonPrimitive(result.durationMs))
            put("patch_id", JsonPrimitive(patchInfo.patchId))
            put("patch_mod_id", JsonPrimitive(patchInfo.patchModId))
            put("launch_mod_ids", buildJsonArray { result.launchModIds.forEach { add(JsonPrimitive(it)) } })
            if (result.unresolvedDependencies.isNotEmpty()) {
                put(
                    "unresolved_dependencies",
                    buildJsonArray { result.unresolvedDependencies.forEach { add(JsonPrimitive(it)) } },
                )
            }
            if (result.events.isNotBlank()) put("boot_events", JsonPrimitive(result.events))
            if (result.logExcerpt.isNotBlank()) put("log_excerpt", JsonPrimitive(result.logExcerpt))
            put(
                "next_step",
                JsonPrimitive(
                    if (result.passed) {
                        "The patch loads and reaches the main menu. Report success to the user."
                    } else {
                        "The patch failed to reach the main menu. Read log_excerpt/boot_events, fix the patch, recompile, repackage, and run this tool again."
                    },
                ),
            )
        }.toString()
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = AgentPatchSmokeTest.DEFAULT_TIMEOUT_MS / 1_000L
        const val MIN_TIMEOUT_SECONDS = 15L
        const val MAX_TIMEOUT_SECONDS = 900L
    }
}
