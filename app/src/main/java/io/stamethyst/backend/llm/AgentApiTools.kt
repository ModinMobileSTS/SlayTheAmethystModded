package io.stamethyst.backend.llm

import android.content.Context
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import io.stamethyst.backend.mods.AgentApiIndex
import io.stamethyst.backend.mods.AgentPatchSourceCompiler
import io.stamethyst.backend.mods.ApiMember
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Bundled reference skills the agent can read on demand. */
internal object AgentSkills {
    const val BASEMOD_AND_STSLIB = "basemod-and-stslib"

    private val skills = linkedMapOf(
        BASEMOD_AND_STSLIB to Skill(
            assetPath = "agent/skills/basemod-and-stslib.md",
            summary = "BaseMod 5.56.0 and StSLib 2.12.0: registration lifecycle, content routing, " +
                "hooks, keywords, relic/power hooks, actions, and the pitfalls that crash the game.",
        ),
    )

    fun names(): List<String> = skills.keys.toList()

    fun get(name: String): Skill? = skills[name.trim().lowercase()]

    data class Skill(val assetPath: String, val summary: String)
}

/**
 * Reads a bundled reference skill.
 *
 * The heavy API reference lives here instead of the system prompt so it costs context only when the
 * agent is actually writing BaseMod/StSLib code.
 */
class AgentSkillTool(private val context: Context) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("read_agent_skill")
        .description(
            "Read a bundled reference skill for the modding APIs. Call this before writing " +
                "BaseMod or StSLib code, or when registering cards, relics, potions, events, " +
                "keywords, characters, or colors. Omit 'name' to list the available skills.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("name", "Skill name from the list; for example ${AgentSkills.BASEMOD_AND_STSLIB}.")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.READ_ONLY

    override fun execute(arguments: String): String {
        val name = runCatching {
            Json.parseToJsonElement(arguments).jsonObject["name"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf(String::isNotBlank)
        if (name == null) {
            return buildJsonObject {
                put("skills", buildJsonArray {
                    AgentSkills.names().forEach { skillName ->
                        add(buildJsonObject {
                            put("name", JsonPrimitive(skillName))
                            put("summary", JsonPrimitive(AgentSkills.get(skillName)?.summary.orEmpty()))
                        })
                    }
                })
            }.toString()
        }
        val skill = AgentSkills.get(name) ?: return failure("unknown_skill")
        val content = runCatching {
            context.assets.open(skill.assetPath).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrElse { return failure("skill_unreadable") }
        return buildJsonObject {
            put("name", JsonPrimitive(name.trim().lowercase()))
            put("content", JsonPrimitive(content))
        }.toString()
    }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()
}

/**
 * Searches the installed compile classpath for types by name.
 *
 * This is how the agent discovers what the game, BaseMod, and StSLib actually provide instead of
 * recalling an API from training data. Results are type headers only; use
 * [AgentApiDescribeTool] for the exact member signatures of one type.
 */
class AgentApiSearchTool(
    private val context: Context,
    private val parentJar: File,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("search_agent_api")
        .description(
            "Search the game, ModTheSpire, BaseMod, StSLib, and parent mod classpath for types by " +
                "name (for example CustomRelic, EditCardsSubscriber, VulnerablePower). Use it to " +
                "find the exact type before extending or calling it, and use the optional " +
                "'implements' filter to list every type implementing an interface.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("query", "Case-insensitive full or partial type name, for example 'CustomRelic'.")
                .addStringProperty("implements", "Only return types implementing this interface, for example 'EditCardsSubscriber'.")
                .addStringProperty("limit", "Maximum results; defaults to 25.")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.READ_ONLY

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val query = json["query"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        val implementsFilter = json["implements"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        if (query == null && implementsFilter == null) return failure("query_or_implements_required")
        val limit = json["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_LIMIT
        val classpath = classpath() ?: return failure("classpath_unavailable")
        if (classpath.isEmpty()) return failure("classpath_unavailable")
        val index = AgentApiIndex.index(classpath)
        val results = AgentApiIndex.search(index.all(), query, implementsFilter, limit)
        return buildJsonObject {
            put("query", JsonPrimitive(query.orEmpty()))
            if (implementsFilter != null) put("implements", JsonPrimitive(implementsFilter))
            put("indexed_types", JsonPrimitive(index.size))
            put("returned", JsonPrimitive(results.size))
            put("results", buildJsonArray {
                results.forEach { type ->
                    add(buildJsonObject {
                        put("name", JsonPrimitive(type.binaryName))
                        put("kind", JsonPrimitive(type.kind))
                        if (type.superName.isNotEmpty() && type.superName != "java.lang.Object") {
                            put("extends", JsonPrimitive(type.superName))
                        }
                        if (type.interfaces.isNotEmpty()) {
                            put("interfaces", buildJsonArray { type.interfaces.forEach { add(JsonPrimitive(it)) } })
                        }
                        put("origin", JsonPrimitive(type.origin))
                    })
                }
            })
            put(
                "next_step",
                JsonPrimitive("Call describe_agent_api_class on a result to see its exact constructors, methods, and fields."),
            )
        }.toString()
    }

    private fun classpath(): List<File>? = runCatching {
        AgentPatchSourceCompiler.resolveCompileClasspath(context, parentJar)
    }.getOrNull()

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()

    private companion object {
        const val DEFAULT_LIMIT = 25
    }
}

/**
 * Returns the exact public and protected surface of one classpath type.
 *
 * The declarations come from installed bytecode, so the agent writes calls against the version it
 * is actually patching rather than a remembered or out-of-date signature.
 */
class AgentApiDescribeTool(
    private val context: Context,
    private val parentJar: File,
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("describe_agent_api_class")
        .description(
            "Show the exact constructors, methods, and fields of one type from the installed " +
                "game/mod bytecode. Call this before extending a class or calling a method so the " +
                "signature is correct. Nested types may be written as Outer.Inner; use " +
                "'member_filter' to narrow a large type such as AbstractCard or BaseMod.",
        )
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("class_name", "Binary name from search_agent_api, for example basemod.abstracts.CustomCard.")
                .addStringProperty("member_filter", "Case-insensitive substring; only members whose name contains it are returned.")
                .required("class_name")
                .additionalProperties(false)
                .build(),
        )
        .build()

    override val safety: AgentToolSafety = AgentToolSafety.READ_ONLY

    override fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return failure("invalid_arguments")
        val className = json["class_name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: return failure("invalid_arguments")
        val memberFilter = json["member_filter"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        val classpath = runCatching {
            AgentPatchSourceCompiler.resolveCompileClasspath(context, parentJar)
        }.getOrElse { return failure(it.message ?: "classpath_unavailable") }
        val detail = AgentApiIndex.describe(classpath, className, memberFilter) ?: return failure("class_not_found")
        return buildJsonObject {
            put("name", JsonPrimitive(detail.summary.binaryName))
            put("kind", JsonPrimitive(detail.summary.kind))
            if (detail.summary.superName.isNotEmpty() && detail.summary.superName != "java.lang.Object") {
                put("extends", JsonPrimitive(detail.summary.superName))
            }
            if (detail.summary.interfaces.isNotEmpty()) {
                put("interfaces", buildJsonArray { detail.summary.interfaces.forEach { add(JsonPrimitive(it)) } })
            }
            put("origin", JsonPrimitive(detail.summary.origin))
            put("constructors", members(detail.constructors))
            put("methods", members(detail.methods))
            put("fields", members(detail.fields))
            if (detail.methods.size >= MAX_MEMBERS) {
                put("note", JsonPrimitive("Member list truncated to $MAX_MEMBERS entries; pass member_filter to narrow it."))
            }
        }.toString()
    }

    private fun members(members: List<ApiMember>) =
        buildJsonArray {
            members.take(MAX_MEMBERS).forEach { member ->
                add(buildJsonObject {
                    put("name", JsonPrimitive(member.name))
                    put("declaration", JsonPrimitive(member.declaration))
                })
            }
        }

    private fun failure(code: String): String = buildJsonObject {
        put("error", JsonPrimitive(code))
    }.toString()

    private companion object {
        const val MAX_MEMBERS = 400
    }
}
