package io.stamethyst.backend.llm

import android.content.Context
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import io.stamethyst.backend.mods.AgentInstalledModCatalog
import io.stamethyst.backend.mods.ModManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Lists the mods the launcher has installed.
 *
 * This is the read-only companion to the smoke test: a patch that is meant to work alongside another
 * mod has to be verified together with it, and the agent can only name a mod if it can see which ids
 * exist. The result also shows which built-in mods are always loaded and which mods the user
 * currently has enabled, so a patch can account for the user's actual setup.
 */
class AgentListInstalledModsTool(
    private val context: Context,
    private val parentModId: String,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("list_installed_mods")
        .description(
            "List the mods installed in the launcher: mod_id, manifest_mod_id, name, version, " +
                "dependencies, whether it is a built-in mod that is always loaded, and whether the " +
                "user currently has it enabled. Use the ids with smoke_test_agent_patch_mod's " +
                "mod_ids to test a patch together with the mods it interacts with.",
        )
        .parameters(JsonObjectSchema.builder().additionalProperties(false).build())
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.READ_ONLY

    override fun execute(arguments: String): String {
        val entries = AgentInstalledModCatalog.entries(
            installedMods = ModManager.listInstalledMods(context),
            parentModId = parentModId,
        )
        val builtInModIds = entries.filter { it.required }.map { it.modId }
        // Only installed mods can actually be launched or selected.
        val selectableModIds = entries
            .filter { it.installed && !it.required }
            .map { it.modId }
        return buildJsonObject {
            put("parent_mod_id", JsonPrimitive(ModManager.normalizeModId(parentModId)))
            put(
                "note",
                JsonPrimitive(
                    "Built-in mods and the parent mod are always loaded by the smoke test. Pass other " +
                        "mod ids from selectable_mod_ids in smoke_test_agent_patch_mod's mod_ids to " +
                        "enable them for a run.",
                ),
            )
            put("built_in_mod_ids", buildJsonArray { builtInModIds.forEach { add(JsonPrimitive(it)) } })
            put("selectable_mod_ids", buildJsonArray { selectableModIds.forEach { add(JsonPrimitive(it)) } })
            put("mod_count", JsonPrimitive(entries.size))
            put(
                "mods",
                buildJsonArray {
                    entries.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("mod_id", JsonPrimitive(entry.modId))
                                if (entry.manifestModId.isNotEmpty()) {
                                    put("manifest_mod_id", JsonPrimitive(entry.manifestModId))
                                }
                                put("name", JsonPrimitive(entry.name))
                                if (entry.version.isNotEmpty()) put("version", JsonPrimitive(entry.version))
                                put("built_in", JsonPrimitive(entry.required))
                                put("installed", JsonPrimitive(entry.installed))
                                put("enabled", JsonPrimitive(entry.enabled))
                                put("is_parent", JsonPrimitive(entry.isParent))
                                if (entry.dependencies.isNotEmpty()) {
                                    put(
                                        "dependencies",
                                        buildJsonArray { entry.dependencies.forEach { add(JsonPrimitive(it)) } },
                                    )
                                }
                            },
                        )
                    }
                },
            )
        }.toString()
    }
}
