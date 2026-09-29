package io.stamethyst.backend.mods

/**
 * One mod the launcher knows about, as shown to the agent.
 *
 * [modId] and [manifestModId] are both accepted by the smoke test's `mod_ids`, because a mod's
 * launch id and its manifest `modid` are not always identical.
 */
internal data class AgentInstalledModEntry(
    val modId: String,
    val manifestModId: String,
    val name: String,
    val version: String,
    val required: Boolean,
    val installed: Boolean,
    val enabled: Boolean,
    val isParent: Boolean,
    val dependencies: List<String>,
)

/**
 * Read-only view of the installed mods for the AI editor.
 *
 * Kept separate from the tool so the classification (built-in, parent, enabled) can be tested
 * without an Android context.
 */
internal object AgentInstalledModCatalog {
    fun entries(
        installedMods: List<ModManager.InstalledMod>,
        parentModId: String,
    ): List<AgentInstalledModEntry> {
        val normalizedParent = ModManager.normalizeModId(parentModId)
        return installedMods.map { mod ->
            val normalizedModId = ModManager.normalizeModId(mod.modId)
            val normalizedManifestId = ModManager.normalizeModId(mod.manifestModId)
            AgentInstalledModEntry(
                modId = normalizedModId,
                manifestModId = mod.manifestModId.trim(),
                name = mod.name,
                version = mod.version,
                required = ModManager.isRequiredModId(mod.modId),
                installed = mod.installed,
                enabled = mod.enabled,
                isParent = normalizedParent.isNotEmpty() &&
                    (normalizedModId == normalizedParent || normalizedManifestId == normalizedParent),
                dependencies = mod.dependencies,
            )
        }
    }
}
