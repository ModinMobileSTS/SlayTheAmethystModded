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
    listOf(
        "com/badlogic/gdx/Application.class",
        "com/badlogic/gdx/utils/Disposable.class"
    )
)

// Batch and its method descriptor types must be resolved by MTSClassLoader
// together with SpriteBatch. In particular, Texture is replaced by Ram Saver
// and must never be supplied by the parent API classpath.
internal val GDX_MTS_OWNED_CLASSES: Set<String> = HashSet(
    listOf(
        "com/badlogic/gdx/graphics/g2d/Batch.class",
        "com/badlogic/gdx/graphics/Texture.class"
    )
)

internal val GDX_BRIDGE_CLASSES: Set<String> = HashSet(
    listOf(
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
internal const val STS_PATCH_LWJGL_HOT_LOOP_CONFIG_CLASS =
    "com/badlogic/gdx/backends/lwjgl/LwjglHotLoopConfig.class"
internal const val STS_PATCH_LWJGL_FRAME_PACER_SCHEDULE_CLASS =
    "com/badlogic/gdx/backends/lwjgl/LwjglFramePacerSchedule.class"
internal const val STS_PATCH_FRAME_RING_BUFFER_CLASS =
    "com/badlogic/gdx/backends/lwjgl/FrameRingBuffer.class"
internal const val STS_PATCH_FRAME_RING_BUFFER_PREFIX =
    "com/badlogic/gdx/backends/lwjgl/FrameRingBuffer$"
internal const val STS_PATCH_SPRITE_BATCH_CLASS =
    "com/badlogic/gdx/graphics/g2d/SpriteBatch.class"
internal const val STS_PATCH_PIXEL_SCALE_CLASS =
    "com/badlogic/gdx/backends/lwjgl/PixelScaleCompat.class"
internal const val STS_PATCH_LWJGL_INPUT_CLASS =
    "com/badlogic/gdx/backends/lwjgl/LwjglInput.class"
internal const val STS_PATCH_LWJGL_NATIVES_CLASS =
    "com/badlogic/gdx/backends/lwjgl/LwjglNativesLoader.class"
internal const val STS_PATCH_SHARED_LOADER_CLASS =
    "com/badlogic/gdx/utils/SharedLibraryLoader.class"
internal const val STS_PATCH_FIRST_PERSON_GYRO_BRIDGE_CLASS =
    "io/stamethyst/bridge/FirstPersonGyroBridge.class"
internal const val STS_PATCH_STEAM_UTILS_CLASS =
    "com/codedisaster/steamworks/SteamUtils.class"
internal const val STS_PATCH_STEAM_UTILS_ENUM_CLASS =
    $$"com/codedisaster/steamworks/SteamUtils$FloatingGamepadTextInputMode.class"
internal const val STS_PATCH_STEAM_INPUT_HELPER_CLASS =
    "com/megacrit/cardcrawl/helpers/steamInput/SteamInputHelper.class"
internal const val STS_PATCH_TIP_HELPER_CLASS =
    "com/megacrit/cardcrawl/helpers/TipHelper.class"
internal const val STS_PATCH_HITBOX_CLASS =
    "com/megacrit/cardcrawl/helpers/Hitbox.class"
internal const val STS_PATCH_INPUT_HELPER_CLASS =
    "com/megacrit/cardcrawl/helpers/input/InputHelper.class"
internal const val STS_PATCH_TYPE_HELPER_CLASS =
    "com/megacrit/cardcrawl/helpers/TypeHelper.class"
internal const val STS_PATCH_RENAME_POPUP_CLASS =
    "com/megacrit/cardcrawl/ui/panels/RenamePopup.class"
internal const val STS_PATCH_SEED_PANEL_CLASS =
    "com/megacrit/cardcrawl/ui/panels/SeedPanel.class"
internal const val STS_PATCH_SINGLE_CARD_VIEW_POPUP_CLASS =
    "com/megacrit/cardcrawl/screens/SingleCardViewPopup.class"
internal const val STS_PATCH_SINGLE_CARD_VIEW_POPUP_PREFIX =
    "com/megacrit/cardcrawl/screens/SingleCardViewPopup"
internal const val STS_PATCH_GL_TEXTURE_CLASS =
    "com/badlogic/gdx/graphics/GLTexture.class"
internal const val STS_PATCH_GL_TEXTURE_INNER_PREFIX =
    "com/badlogic/gdx/graphics/GLTexture$"
internal const val STS_PATCH_TEXTURE_OWNER_SUMMARY_CLASS =
    "com/badlogic/gdx/graphics/TextureOwnerSummary.class"
internal const val STS_PATCH_GPU_RESOURCE_GUARDIAN_CLASS =
    "com/badlogic/gdx/graphics/GpuResourceGuardian.class"
internal const val STS_PATCH_GPU_RESOURCE_GUARDIAN_MODE_CLASS =
    "com/badlogic/gdx/graphics/GpuResourceGuardian\$Mode.class"
internal const val STS_PATCH_GPU_RESOURCE_GUARDIAN_STATE_CLASS =
    "com/badlogic/gdx/graphics/GpuResourceGuardian\$State.class"
internal const val STS_PATCH_GPU_RESOURCE_GUARDIAN_INNER_PREFIX =
    "com/badlogic/gdx/graphics/GpuResourceGuardian$"
internal const val STS_PATCH_GPU_LEAK_INJECTOR_CLASS =
    "com/badlogic/gdx/graphics/GpuLeakInjector.class"
internal const val STS_PATCH_GL_FRAMEBUFFER_CLASS =
    "com/badlogic/gdx/graphics/glutils/GLFrameBuffer.class"
internal const val STS_PATCH_GL_FRAMEBUFFER_INNER_PREFIX =
    "com/badlogic/gdx/graphics/glutils/GLFrameBuffer$"
internal const val STS_PATCH_FRAMEBUFFER_OWNER_SUMMARY_CLASS =
    "com/badlogic/gdx/graphics/glutils/FrameBufferOwnerSummary.class"
internal const val STS_PATCH_SHADER_PROGRAM_CLASS =
    "com/badlogic/gdx/graphics/glutils/ShaderProgram.class"
internal const val STS_PATCH_FREETYPE_BITMAP_FONT_DATA_CLASS =
    "com/badlogic/gdx/graphics/g2d/freetype/FreeTypeFontGenerator\$FreeTypeBitmapFontData.class"
internal const val STS_PATCH_FREETYPE_GLYPH_FALLBACK_COMPAT_CLASS =
    "com/badlogic/gdx/graphics/g2d/freetype/FreeTypeGlyphFallbackCompat.class"
internal const val STS_PATCH_FRAGMENT_SHADER_COMPAT_CLASS =
    "io/stamethyst/gdx/FragmentShaderCompat.class"
internal const val STS_PATCH_FRAGMENT_SHADER_COMPAT_INTEGER_LITERAL_CLASS =
    $$"io/stamethyst/gdx/FragmentShaderCompat$IntegerLiteral.class"
internal const val STS_PATCH_FRAGMENT_SHADER_COMPAT_INNER_PREFIX =
    "io/stamethyst/gdx/FragmentShaderCompat$"
internal const val STS_PATCH_COLOR_TAB_BAR_CLASS =
    "com/megacrit/cardcrawl/screens/mainMenu/ColorTabBar.class"
internal const val STS_PATCH_COLOR_TAB_BAR_PREFIX =
    "com/megacrit/cardcrawl/screens/mainMenu/ColorTabBar"
internal const val STS_PATCH_OPTIONS_PANEL_CLASS =
    "com/megacrit/cardcrawl/screens/options/OptionsPanel.class"
internal const val STS_PATCH_DESKTOP_CONTROLLER_MANAGER_CLASS =
    "com/badlogic/gdx/controllers/desktop/DesktopControllerManager.class"
internal const val STS_PATCH_DESKTOP_CONTROLLER_MANAGER_DIRECT_CLASS =
    $$"com/badlogic/gdx/controllers/desktop/DesktopControllerManager$DirectController.class"
internal const val STS_PATCH_DESKTOP_CONTROLLER_MANAGER_POLL_CLASS =
    $$"com/badlogic/gdx/controllers/desktop/DesktopControllerManager$PollRunnable.class"
internal const val STS_PATCH_DESKTOP_CONTROLLER_MANAGER_PREFIX =
    "com/badlogic/gdx/controllers/desktop/DesktopControllerManager"
internal const val STS_PATCH_BUILD_PROPERTIES = "build.properties"

internal const val STS_RESOURCE_SENTINEL = "build.properties"
internal const val BASEMOD_RESOURCE_SENTINEL =
    "localization/basemod/eng/customMods.json"

internal const val COMPAT_LOG_PREFIX = "[compat] "

internal val REQUIRED_STS_PATCH_CLASSES: Set<String> = HashSet(
    listOf(
        STS_PATCH_LWJGL_APPLICATION_CLASS,
        STS_PATCH_LWJGL_HOT_LOOP_CONFIG_CLASS,
        STS_PATCH_LWJGL_FRAME_PACER_SCHEDULE_CLASS,
        STS_PATCH_FRAME_RING_BUFFER_CLASS,
        STS_PATCH_SPRITE_BATCH_CLASS,
        "com/badlogic/gdx/backends/lwjgl/LwjglGraphics.class",
        STS_PATCH_PIXEL_SCALE_CLASS,
        STS_PATCH_LWJGL_INPUT_CLASS,
        STS_PATCH_LWJGL_NATIVES_CLASS,
        STS_PATCH_SHARED_LOADER_CLASS,
        STS_PATCH_FIRST_PERSON_GYRO_BRIDGE_CLASS,
        STS_PATCH_STEAM_UTILS_CLASS,
        STS_PATCH_STEAM_UTILS_ENUM_CLASS,
        STS_PATCH_STEAM_INPUT_HELPER_CLASS,
        STS_PATCH_TIP_HELPER_CLASS,
        STS_PATCH_HITBOX_CLASS,
        STS_PATCH_INPUT_HELPER_CLASS,
        STS_PATCH_TYPE_HELPER_CLASS,
        STS_PATCH_RENAME_POPUP_CLASS,
        STS_PATCH_SEED_PANEL_CLASS,
        STS_PATCH_SINGLE_CARD_VIEW_POPUP_CLASS,
        STS_PATCH_GL_TEXTURE_CLASS,
        STS_PATCH_TEXTURE_OWNER_SUMMARY_CLASS,
        STS_PATCH_GPU_RESOURCE_GUARDIAN_CLASS,
        STS_PATCH_GPU_RESOURCE_GUARDIAN_MODE_CLASS,
        STS_PATCH_GPU_RESOURCE_GUARDIAN_STATE_CLASS,
        STS_PATCH_GPU_LEAK_INJECTOR_CLASS,
        STS_PATCH_GL_FRAMEBUFFER_CLASS,
        STS_PATCH_FRAMEBUFFER_OWNER_SUMMARY_CLASS,
        STS_PATCH_FREETYPE_GLYPH_FALLBACK_COMPAT_CLASS,
        STS_PATCH_FRAGMENT_SHADER_COMPAT_CLASS,
        STS_PATCH_FRAGMENT_SHADER_COMPAT_INTEGER_LITERAL_CLASS,
        STS_PATCH_COLOR_TAB_BAR_CLASS,
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

internal val COMPAT_PATCH_RULES: Array<CompatPatchRule> = emptyArray()
