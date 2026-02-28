package io.stamethyst.backend.mods

import java.util.Arrays
import java.util.HashSet
import java.util.regex.Pattern

internal val MOD_ID_JSON_KEYS = arrayOf(
    "modid",
    "modId",
    "id",
    "ID",
    "mod_id"
)

internal val MOD_NAME_JSON_KEYS = arrayOf(
    "name",
    "Name",
    "modName",
    "mod_name"
)

internal val MOD_VERSION_JSON_KEYS = arrayOf(
    "version",
    "Version",
    "modVersion",
    "ModVersion"
)

internal val MOD_DESCRIPTION_JSON_KEYS = arrayOf(
    "description",
    "Description",
    "detail",
    "Detail"
)

internal val MOD_DEPENDENCIES_JSON_KEYS = arrayOf(
    "dependencies",
    "Dependencies",
    "depends",
    "DependsOn",
    "requiredMods",
    "RequiredMods",
    "required_mods"
)

internal val MOD_ID_PATTERN: Pattern =
    Pattern.compile("\"(?:modid|modId|id|ID|mod_id)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")

internal val MOD_NAME_PATTERN: Pattern =
    Pattern.compile("\"(?:name|Name|modName|mod_name)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")

internal val MOD_VERSION_PATTERN: Pattern =
    Pattern.compile("\"(?:version|Version|modVersion|ModVersion)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")

internal val MOD_DESCRIPTION_PATTERN: Pattern =
    Pattern.compile("\"(?:description|Description|detail|Detail)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")

internal const val STSLIB_MAIN_CLASS = "com/evacipated/cardcrawl/mod/stslib/StSLib.class"

internal const val GDX_CLASS_PREFIX = "com/badlogic/gdx/"
internal const val GDX_BACKEND_PREFIX = "com/badlogic/gdx/backends/"
internal val ALLOWED_PARENT_BACKEND_CLASSES: MutableSet<String> = HashSet()

internal val REQUIRED_GDX_CLASSES: Set<String> = HashSet(
    Arrays.asList(
        "com/badlogic/gdx/Application.class",
        "com/badlogic/gdx/graphics/g2d/Batch.class",
        "com/badlogic/gdx/utils/Disposable.class"
    )
)

internal val GDX_BRIDGE_CLASSES: Set<String> = HashSet(
    Arrays.asList(
        "com/badlogic/gdx/utils/SharedLibraryLoader.class",
        "com/badlogic/gdx/backends/lwjgl/LwjglNativesLoader.class"
    )
)

internal const val GDX_BRIDGE_LWJGL_INPUT_PREFIX =
    "com/badlogic/gdx/backends/lwjgl/LwjglInput"

internal const val STS_PATCH_LWJGL_GRAPHICS_PREFIX =
    "com/badlogic/gdx/backends/lwjgl/LwjglGraphics"
internal const val STS_PATCH_LWJGL_APPLICATION_PREFIX =
    "com/badlogic/gdx/backends/lwjgl/LwjglApplication"
internal const val STS_PATCH_LWJGL_APPLICATION_CLASS =
    "com/badlogic/gdx/backends/lwjgl/LwjglApplication.class"
internal const val STS_PATCH_PIXEL_SCALE_CLASS =
    "com/badlogic/gdx/backends/lwjgl/PixelScaleCompat.class"
internal const val STS_PATCH_LWJGL_INPUT_CLASS =
    "com/badlogic/gdx/backends/lwjgl/LwjglInput.class"
internal const val STS_PATCH_LWJGL_NATIVES_CLASS =
    "com/badlogic/gdx/backends/lwjgl/LwjglNativesLoader.class"
internal const val STS_PATCH_SHARED_LOADER_CLASS =
    "com/badlogic/gdx/utils/SharedLibraryLoader.class"
internal const val STS_PATCH_STEAM_UTILS_CLASS =
    "com/codedisaster/steamworks/SteamUtils.class"
internal const val STS_PATCH_STEAM_UTILS_ENUM_CLASS =
    "com/codedisaster/steamworks/SteamUtils\$FloatingGamepadTextInputMode.class"
internal const val STS_PATCH_STEAM_INPUT_HELPER_CLASS =
    "com/megacrit/cardcrawl/helpers/steamInput/SteamInputHelper.class"
internal const val STS_PATCH_GL_FRAMEBUFFER_CLASS =
    "com/badlogic/gdx/graphics/glutils/GLFrameBuffer.class"
internal const val STS_PATCH_DESKTOP_CONTROLLER_MANAGER_CLASS =
    "com/badlogic/gdx/controllers/desktop/DesktopControllerManager.class"
internal const val STS_PATCH_DESKTOP_CONTROLLER_MANAGER_DIRECT_CLASS =
    "com/badlogic/gdx/controllers/desktop/DesktopControllerManager\$DirectController.class"
internal const val STS_PATCH_DESKTOP_CONTROLLER_MANAGER_POLL_CLASS =
    "com/badlogic/gdx/controllers/desktop/DesktopControllerManager\$PollRunnable.class"
internal const val STS_PATCH_DESKTOP_CONTROLLER_MANAGER_PREFIX =
    "com/badlogic/gdx/controllers/desktop/DesktopControllerManager"
internal const val STS_PATCH_BUILD_PROPERTIES = "build.properties"

internal const val STS_RESOURCE_SENTINEL = "build.properties"
internal const val BASEMOD_RESOURCE_SENTINEL =
    "localization/basemod/eng/customMods.json"

internal const val BASEMOD_MOD_ID = "basemod"
internal const val BASEMOD_JAR_FILE_NAME = "BaseMod.jar"
internal const val BASEMOD_GLOW_PATCH_JAR = "basemod-glow-fbo-compat.jar"
internal const val BASEMOD_GLOW_PATCH_CLASS =
    "basemod/helpers/CardBorderGlowManager\$RenderGlowPatch.class"
internal const val BASEMOD_POSTPROCESS_PATCH_JAR = "basemod-postprocess-fbo-compat.jar"
internal const val BASEMOD_POSTPROCESS_PATCH_CLASS =
    "basemod/patches/com/megacrit/cardcrawl/core/CardCrawlGame/ApplyScreenPostProcessor.class"

internal const val DOWNFALL_MOD_ID = "downfall"
internal const val DOWNFALL_FBO_PATCH_JAR = "downfall-fbo-compat.jar"
internal const val DOWNFALL_FBO_PATCH_CLASS =
    "collector/util/DoubleEnergyOrb.class"
internal const val DOWNFALL_NPC_FBO_PATCH_CLASS =
    "downfall/vfx/CustomAnimatedNPC.class"

internal const val COMPAT_LOG_PREFIX = "[compat] "

internal val REQUIRED_STS_PATCH_CLASSES: Set<String> = HashSet(
    Arrays.asList(
        STS_PATCH_LWJGL_APPLICATION_CLASS,
        "com/badlogic/gdx/backends/lwjgl/LwjglGraphics.class",
        STS_PATCH_PIXEL_SCALE_CLASS,
        STS_PATCH_LWJGL_INPUT_CLASS,
        STS_PATCH_LWJGL_NATIVES_CLASS,
        STS_PATCH_SHARED_LOADER_CLASS,
        STS_PATCH_STEAM_UTILS_CLASS,
        STS_PATCH_STEAM_UTILS_ENUM_CLASS,
        STS_PATCH_STEAM_INPUT_HELPER_CLASS,
        STS_PATCH_GL_FRAMEBUFFER_CLASS,
        STS_PATCH_DESKTOP_CONTROLLER_MANAGER_CLASS,
        STS_PATCH_DESKTOP_CONTROLLER_MANAGER_DIRECT_CLASS,
        STS_PATCH_DESKTOP_CONTROLLER_MANAGER_POLL_CLASS,
        STS_PATCH_BUILD_PROPERTIES
    )
)

internal enum class CompatPatchApplyResult {
    PATCHED,
    ALREADY_PATCHED
}

internal data class CompatPatchRule(
    val modId: String,
    val patchJarName: String,
    val targetClassEntry: String,
    val label: String,
    val applyWhenInstalled: Boolean,
    val fixedTargetJarName: String?
)

internal val COMPAT_PATCH_RULES: Array<CompatPatchRule> = arrayOf(
    CompatPatchRule(
        modId = BASEMOD_MOD_ID,
        patchJarName = BASEMOD_GLOW_PATCH_JAR,
        targetClassEntry = BASEMOD_GLOW_PATCH_CLASS,
        label = "BaseMod glow",
        applyWhenInstalled = false,
        fixedTargetJarName = BASEMOD_JAR_FILE_NAME
    ),
    CompatPatchRule(
        modId = BASEMOD_MOD_ID,
        patchJarName = BASEMOD_POSTPROCESS_PATCH_JAR,
        targetClassEntry = BASEMOD_POSTPROCESS_PATCH_CLASS,
        label = "BaseMod postprocess FBO",
        applyWhenInstalled = false,
        fixedTargetJarName = BASEMOD_JAR_FILE_NAME
    ),
    CompatPatchRule(
        modId = DOWNFALL_MOD_ID,
        patchJarName = DOWNFALL_FBO_PATCH_JAR,
        targetClassEntry = DOWNFALL_FBO_PATCH_CLASS,
        label = "Downfall FBO",
        applyWhenInstalled = true,
        fixedTargetJarName = null
    ),
    CompatPatchRule(
        modId = DOWNFALL_MOD_ID,
        patchJarName = DOWNFALL_FBO_PATCH_JAR,
        targetClassEntry = DOWNFALL_NPC_FBO_PATCH_CLASS,
        label = "Downfall NPC portal FBO",
        applyWhenInstalled = true,
        fixedTargetJarName = null
    )
)
