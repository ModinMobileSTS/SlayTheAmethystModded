package io.stamethyst.backend.llm

import android.content.Context
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import io.stamethyst.backend.mods.AgentPatchModManager
import io.stamethyst.backend.mods.AgentPatchSmokeTest
import io.stamethyst.backend.mods.AgentPatchWorkspace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Launches the game with the parent mod plus one packaged AI patch and reports whether the main
 * menu was reached.
 *
 * This is the runtime half of the patch workflow: compilation and bytecode preflight prove the
 * patch targets existing code, but only a real launch proves the patch does not break mod loading.
 * The baseline set is the parent mod, the patch, the parent's prerequisites, and the launcher's
 * built-in mods, so an unrelated optional mod cannot influence the result. The agent may add mods on
 * top of that baseline with `mod_ids` when the patch is meant to interact with them.
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
                "compilation and validation cannot catch runtime failures. The game runs in the " +
                "background with no visible window, so the user's screen does not change. It takes " +
                "roughly 1-4 minutes and blocks until it finishes. Call it after package_agent_patch_mod, " +
                "and fix the patch and re-run it if it fails. The baseline always loads the parent mod, " +
                "its prerequisites, and the built-in mods; use mod_ids (from list_installed_mods) to " +
                "also enable the optional mods this patch is meant to work with. The user's own " +
                "optional-mod selection is not used, so the result is reproducible.",
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
                .addStringProperty(
                    "mod_ids",
                    "Optional comma- or newline-separated mod ids to enable for this run, from " +
                        "list_installed_mods. They are loaded on top of the parent mod, its " +
                        "prerequisites, and the built-in mods.",
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
        val selectedModIds = readModIds(json)
        val patchInfo = AgentPatchModManager.listPackaged(context, parentModId)
            .firstOrNull { it.patchId == patchId }
            ?: return failure("patch_not_found: call package_agent_patch_mod first, or pass a patch_id from list_agent_patch_mods")
        val result = AgentPatchSmokeTest.run(
            context = context,
            parentModId = parentModId,
            parentJar = parentJar,
            patchJar = patchInfo.jarFile,
            timeoutMs = timeoutSeconds * 1_000L,
            selectedModIds = selectedModIds,
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
            if (selectedModIds.isNotEmpty()) {
                put("requested_mod_ids", buildJsonArray { selectedModIds.forEach { add(JsonPrimitive(it)) } })
            }
            if (result.unknownModIds.isNotEmpty()) {
                put(
                    "unknown_mod_ids",
                    buildJsonArray { result.unknownModIds.forEach { add(JsonPrimitive(it)) } },
                )
            }
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

    /**
     * Reads `mod_ids` defensively.
     *
     * The property is declared as a string for provider compatibility, but some models still emit a
     * JSON array, so both shapes are accepted.
     */
    private fun readModIds(json: JsonObject): List<String> {
        val element = json["mod_ids"] ?: return emptyList()
        val raw = runCatching {
            when (element) {
                is JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull }
                else -> listOfNotNull(element.jsonPrimitive.contentOrNull)
            }
        }.getOrDefault(emptyList())
        return raw
            .flatMap { it.split(',', '\n', ';') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
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
