package io.stamethyst.backend.llm

import io.stamethyst.backend.mods.AgentPatchModInfo
import io.stamethyst.backend.mods.ModManager
import java.io.File

data class AgentModReadTarget(val modId: String, val jar: File, val patchParentModId: String? = null)

/** Resolves only catalogued, installed JARs; model input is never treated as a file path. */
class AgentModReadTargets(
    private val parentModId: String,
    private val parentJar: File,
    private val packagedPatches: () -> List<AgentPatchModInfo> = { emptyList() },
    private val installedMods: () -> List<ModManager.InstalledMod>,
) {
    fun resolve(modId: String?, patchModId: String? = null): AgentModReadTarget {
        if (patchModId != null) {
            require(modId == null) { "mod_id_and_patch_mod_id_are_mutually_exclusive" }
            val requested = ModManager.normalizeModId(patchModId)
            require(requested.isNotEmpty()) { "patch_not_installed" }
            val matches = packagedPatches().filter {
                ModManager.normalizeModId(it.patchModId) == requested && it.jarFile.isFile
            }
            require(matches.isNotEmpty()) { "patch_not_installed" }
            require(matches.size == 1) { "ambiguous_patch_mod_id" }
            return matches.single().let { AgentModReadTarget(it.patchModId, it.jarFile, it.parentModId) }
        }
        val requested = modId?.trim().orEmpty()
        if (requested.isEmpty() || ModManager.normalizeModId(requested) == ModManager.normalizeModId(parentModId)) {
            return AgentModReadTarget(parentModId, parentJar)
        }
        val normalized = ModManager.normalizeModId(requested)
        val matches = installedMods().filter {
            it.installed && it.jarFile.isFile &&
                (ModManager.normalizeModId(it.modId) == normalized ||
                    ModManager.normalizeModId(it.manifestModId) == normalized)
        }
        val exact = matches.filter { ModManager.normalizeModId(it.modId) == normalized }
        val candidates = exact.ifEmpty { matches }
        require(candidates.isNotEmpty()) { "mod_not_installed" }
        require(candidates.size == 1) { "ambiguous_mod_id_use_catalog_mod_id" }
        return candidates.single().let { AgentModReadTarget(it.modId, it.jarFile) }
    }
}
