package io.stamethyst.backend.mods

internal data class ImportedModPatchInfo(
    val modId: String,
    val modName: String,
    val patchedAtlasEntries: Int = 0,
    val patchedFilterLines: Int = 0,
    val downscaledAtlasEntries: Int = 0,
    val downscaledAtlasPageEntries: Int = 0,
    val patchedManifestRootEntries: Int = 0,
    val patchedManifestRootPrefix: String = "",
    val patchedFrierenAntiPirateMethod: Boolean = false,
    val patchedDownfallClassEntries: Int = 0,
    val patchedDownfallMerchantClassEntries: Int = 0,
    val patchedDownfallHexaghostBodyClassEntries: Int = 0,
    val patchedDownfallBossMechanicPanelClassEntries: Int = 0,
    val patchedVupShionWebButtonConstructor: Boolean = false,
    val patchedJacketNoAnoKoShaderEntries: Int = 0,
    val patchedJacketNoAnoKoDesktopVersionDirectives: Int = 0,
    val patchedJacketNoAnoKoFragmentPrecisionBlocks: Int = 0
) {
    val wasAtlasPatched: Boolean
        get() = patchedFilterLines > 0
    val wasAtlasDownscaled: Boolean
        get() = downscaledAtlasPageEntries > 0
    val wasManifestRootPatched: Boolean
        get() = patchedManifestRootEntries > 0
    val wasFrierenAntiPiratePatched: Boolean
        get() = patchedFrierenAntiPirateMethod
    val wasDownfallPatched: Boolean
        get() = patchedDownfallClassEntries > 0
    val wasVupShionPatched: Boolean
        get() = patchedVupShionWebButtonConstructor
    val wasJacketNoAnoKoPatched: Boolean
        get() = patchedJacketNoAnoKoShaderEntries > 0
    val hasCompatibilityPatches: Boolean
        get() = wasAtlasPatched ||
            wasAtlasDownscaled ||
            wasManifestRootPatched ||
            wasFrierenAntiPiratePatched ||
            wasDownfallPatched ||
            wasVupShionPatched ||
            wasJacketNoAnoKoPatched
}
