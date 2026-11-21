package io.stamethyst.backend.llm

import java.io.File

data class AgentResourceReadTarget(
    val source: String,
    val jar: File,
    val modId: String? = null,
    val patchModId: String? = null,
)

/** Resource-only routing; game access does not broaden class tools or the installed-mod catalog. */
class AgentResourceReadTargets(
    private val mods: AgentModReadTargets,
    private val gameJar: File?,
) {
    fun resolve(source: String?, modId: String?, patchModId: String?): AgentResourceReadTarget = when (source ?: "mod") {
        "mod" -> mods.resolve(modId, patchModId).let {
            if (it.patchParentModId == null) AgentResourceReadTarget("mod", it.jar, modId = it.modId)
            else AgentResourceReadTarget("mod", it.jar, patchModId = it.modId)
        }
        "game" -> {
            require(modId.isNullOrBlank()) { "mod_id_not_allowed_for_game_source" }
            require(patchModId == null) { "patch_mod_id_not_allowed_for_game_source" }
            require(gameJar != null && gameJar.isFile) { "game_jar_not_found" }
            AgentResourceReadTarget("game", gameJar)
        }
        else -> throw IllegalArgumentException("invalid_source")
    }
}
