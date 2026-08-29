package io.stamethyst.backend.launch

import android.content.Context
import android.os.Build
import android.util.Log
import io.stamethyst.BuildConfig
import io.stamethyst.backend.easytier.EasyTierConnectionSnapshot
import io.stamethyst.backend.easytier.EasyTierConnectionStatus
import io.stamethyst.backend.easytier.EasyTierSessionController
import io.stamethyst.backend.easytier.EasyTierStateStore
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.importing.patches.ImportPatchRegistry
import io.stamethyst.backend.mods.importing.patches.texture.AtlasFilterPatchModule
import io.stamethyst.backend.render.AndroidGameModeSupport
import io.stamethyst.backend.render.DisplayConfigSync
import io.stamethyst.backend.render.DisplayRefreshRateController
import io.stamethyst.backend.render.FullscreenCanvasSize
import io.stamethyst.backend.render.FullscreenCanvasResolution
import io.stamethyst.backend.render.RendererBackendResolver
import io.stamethyst.backend.render.RendererDecision
import io.stamethyst.backend.render.RendererBackend
import io.stamethyst.backend.render.VirtualResolutionPolicy
import io.stamethyst.backend.render.VirtualResolutionMode
import io.stamethyst.backend.resources.ArthasResourcePackService
import io.stamethyst.config.GpuResourceGuardianMode
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import io.stamethyst.config.SpecialKeyInputMode
import io.stamethyst.config.TouchscreenInputMode
import net.kdt.pojavlaunch.AWTCanvasView
import org.lwjgl.glfw.CallbackBridge
import java.io.File
import java.util.Arrays
import java.util.Locale
import java.util.TimeZone

object StsLaunchSpec {
    private const val TAG = "STS-LaunchSpec"
    private const val EASYTIER_TOGETHER_IN_SPIRE_PORT = "33455"
    private const val TOGETHER_IN_SPIRE_ROUTE_LOCK_PROPERTY =
        "amethyst.runtime_compat.together_in_spire_route_lock"
    private const val TOGETHER_IN_SPIRE_EASYTIER_AUTOFILL_PROPERTY =
        "amethyst.runtime_compat.together_in_spire_easytier_autofill"
    private const val DEFAULT_G1_MAX_PAUSE_MILLIS = 80
    private const val DEFAULT_ACTIVE_PROCESSOR_COUNT = 3
    private const val DEFAULT_TIERED_STOP_AT_LEVEL = 2
    private const val DEBUG_GPU_GUARDIAN_TEST_PREFS = "sts_debug_gpu_guardian_test"
    private val EFFECTIVE_PERFORMANCE_PROPERTY_KEYS = listOf(
        "amethyst.gdx.frame_ring",
        "amethyst.gdx.frame_hud",
        "amethyst.bridge.launcher_perf_snapshot",
        "amethyst.gdx.gpu_resource_summary",
        "amethyst.gdx.gpu_resource_diag"
    )
    private val DEBUG_GPU_GUARDIAN_PROPERTY_KEYS = setOf(
        "amethyst.gdx.debug_leak_injector",
        "amethyst.gdx.debug_leak_interval_frames",
        "amethyst.gdx.debug_leak_max_bytes",
        "amethyst.gdx.debug_leak_texture_size",
        "amethyst.runtime_compat.loadout_monster_scan_probe",
        "amethyst.runtime_compat.class_finder_scan_cache_profile",
        "amethyst.gdx.gpu_guardian_soft_budget_bytes",
        "amethyst.gdx.gpu_guardian_hard_budget_bytes",
        "amethyst.gdx.gpu_guardian_watch_growth_bytes",
        "amethyst.gdx.gpu_guardian_pressure_growth_bytes",
        "amethyst.gdx.gpu_guardian_sweep_interval_frames",
        "amethyst.gdx.gpu_guardian_cooldown_frames",
        "amethyst.gdx.gpu_guardian_texture_min_idle_frames",
        "amethyst.gdx.gpu_guardian_texture_min_bytes",
        "amethyst.gdx.gpu_guardian_texture_max_checks_per_sweep",
        "amethyst.gdx.gpu_guardian_texture_max_reclaims_per_sweep",
        "amethyst.gdx.gpu_guardian_texture_max_bytes_per_sweep"
    )

    internal data class DebugJvmPropertyAppendResult(
        val appendedKeys: List<String>,
        val skippedManagedKeys: List<String>
    )

    const val LAUNCH_MODE_VANILLA = "vanilla"
    const val LAUNCH_MODE_MTS = "mts"
    // Legacy alias kept only so old debug scripts still resolve to the single MTS mode.
    const val LAUNCH_MODE_MTS_BASEMOD = "mts_basemod"

    @JvmStatic
    fun isMtsLaunchMode(launchMode: String?): Boolean {
        return launchMode == LAUNCH_MODE_MTS || launchMode == LAUNCH_MODE_MTS_BASEMOD
    }

    @JvmStatic
    fun buildArgs(context: Context, javaHome: File): List<String> {
        return buildArgs(
            context = context,
            javaHome = javaHome,
            launchMode = LAUNCH_MODE_VANILLA,
            rendererDecision = resolveRendererDecision(context),
            forceJvmCrash = false,
            forceRuntimeCrash = false
        )
    }

    @JvmStatic
    fun buildArgs(context: Context, javaHome: File, launchMode: String): List<String> {
        return buildArgs(
            context = context,
            javaHome = javaHome,
            launchMode = launchMode,
            rendererDecision = resolveRendererDecision(context),
            forceJvmCrash = false,
            forceRuntimeCrash = false
        )
    }

    @JvmStatic
    fun buildArgs(
        context: Context,
        javaHome: File,
        launchMode: String,
        rendererDecision: RendererDecision,
        renderScaleOverride: Float? = null,
        forceJvmCrash: Boolean = false,
        forceRuntimeCrash: Boolean = false,
        debugMode: Boolean = false,
        autoplay: Boolean = false,
        autoplaySaveMode: AutoplaySaveMode = AutoplaySaveMode.DEFAULT,
        autoplayMode: AutoplayMode = AutoplayMode.DEFAULT,
        autoplaySingleRoomSpecPath: String = "",
        autoplayChoiceDelayMs: Long = 0L,
        autoplaySingleRoomBenchMode: Boolean = false,
        cardObtainEffectOwnershipCompatEnabled: Boolean = true,
        performanceDeepDiagnosticsOverride: Boolean? = null
    ): List<String> {
        val stsRoot = RuntimePaths.stsRoot(context)
        val stsHome = RuntimePaths.stsHome(context)
        val ramSaverEnabled = isMtsLaunchMode(launchMode) && ModManager.isRamSaverEnabled(context)
        val easyTierSnapshot = EasyTierSessionController.currentSnapshot(context)
        if (!stsHome.exists()) {
            stsHome.mkdirs()
        }
        if (isMtsLaunchMode(launchMode)) {
            // MTS boots the LWJGL3 desktop backend when its config has imgui=true, and that
            // path crashes in Amethyst's incomplete GLFW bridge. Force-clear the flag before
            // the game JVM starts; the runtime guard in MtsLoaderCrashPatcher backs this up.
            MtsImguiGuard.disableImguiIfEnabled(context)
        }
        val forceInterpreterFlag = File(stsRoot, "compat_xint.flag")
        val classTraceFlag = File(stsRoot, "classload_trace.flag")
        val is64BitRuntime = is64BitRuntime(javaHome)
        val showPerformanceOverlay = LauncherConfig.isGamePerformanceOverlayEnabled(context)
        val requestedPerformanceDeepDiagnostics = performanceDeepDiagnosticsOverride
            ?: LauncherConfig.isGamePerformanceDeepDiagnosticsEnabled(context)
        val performanceDeepDiagnostics = requestedPerformanceDeepDiagnostics &&
            ArthasResourcePackService.isInstalled(context)

        val args = ArrayList<String>()
        // Performance-first by default, with a compatibility fallback file switch.
        // Create <stsRoot>/compat_xint.flag to force interpreted mode on unstable devices.
        if (forceInterpreterFlag.exists()) {
            args.add("-Xint")
        } else {
            args.add("-XX:+TieredCompilation")
            args.add("-XX:TieredStopAtLevel=$DEFAULT_TIERED_STOP_AT_LEVEL")
        }
        if (is64BitRuntime) {
            val useCompressedPointers = LauncherConfig.isJvmCompressedPointersEnabled(context)
            // Some OpenJDK 8 aarch64 builds crash in VM init with compressed pointers on newer Android stacks.
            // Disable compressed pointers to prefer startup stability over peak performance.
            if (useCompressedPointers) {
                args.add("-XX:+UseCompressedOops")
                args.add("-XX:+UseCompressedClassPointers")
            } else {
                args.add("-XX:-UseCompressedOops")
                args.add("-XX:-UseCompressedClassPointers")
            }
        }
        val heapMaxMb = LauncherConfig.readJvmHeapMaxMb(context)
        // Keep startup heap conservative. Using Xms=Xmx makes a 2G selection
        // immediately commit the full heap during VM init, which is unstable on
        // some Android devices even when the phone has plenty of total RAM.
        val heapStartMb = LauncherConfig.resolveJvmHeapStartMb(heapMaxMb)
        args.add("-Xms${heapStartMb}M")
        args.add("-Xmx${heapMaxMb}M")
        args.add(
            "-XX:ActiveProcessorCount=$DEFAULT_ACTIVE_PROCESSOR_COUNT"
        )
        val disableExplicitGc = resolveDisableExplicitGcEnabled(ramSaverEnabled = ramSaverEnabled)
        if (disableExplicitGc) {
            args.add("-XX:+DisableExplicitGC")
        }
        if (is64BitRuntime) {
            // Reduce periodic frame hitching from stop-the-world pauses.
            args.add("-XX:+UseG1GC")
            args.add("-XX:MaxGCPauseMillis=$DEFAULT_G1_MAX_PAUSE_MILLIS")
            args.add("-XX:+ParallelRefProcEnabled")
            if (resolveExplicitGcInvokesConcurrentEnabled(disableExplicitGc = disableExplicitGc)) {
                args.add("-XX:+ExplicitGCInvokesConcurrent")
            }
            if (LauncherConfig.isJvmStringDeduplicationEnabled(context)) {
                args.add("-XX:+UseStringDeduplication")
            } else {
                args.add("-XX:-UseStringDeduplication")
            }
        }
        args.add("-XX:ErrorFile=/dev/null")
        if (performanceDeepDiagnostics) {
            args.add("-XX:+UnlockDiagnosticVMOptions")
            args.add("-verbose:gc")
            args.add("-Xloggc:${RuntimePaths.jvmGcLog(context).absolutePath}")
            // Single switch activates FrameRingBuffer + amethyst-frame-probe HUD.
            // Budget defaults to 1000ms/foregroundFPS; override with frame_ring.budget_ms.
            args.add("-Damethyst.gdx.frame_ring=true")
            val budgetMs = 1000 / LauncherConfig.readTargetFps(context).coerceAtLeast(1)
            args.add("-Damethyst.gdx.frame_ring.budget_ms=$budgetMs")
        }
        if (isMtsLaunchMode(launchMode)) {
            // BaseMod bytecode can fail verification on some Android/OpenJDK 8 combos after MTS patching.
            args.add("-noverify")
        }
        if (classTraceFlag.exists()) {
            args.add("-verbose:class")
        }
        val enableLwjglDebug = LauncherConfig.isLwjglDebugEnabled(context)
        val enableGdxPadCursorDebug = LauncherConfig.isGdxPadCursorDebugEnabled(context)
        val enableGlBridgeSwapHeartbeatDebug =
            LauncherConfig.isGlBridgeSwapHeartbeatDebugEnabled(context)
        args.add("-Dorg.lwjgl.util.Debug=${if (enableLwjglDebug) "true" else "false"}")
        args.add("-Dorg.lwjgl.util.DebugLoader=${if (enableLwjglDebug) "true" else "false"}")
        args.add("-Damethyst.debug.gdx_pad_cursor=${if (enableGdxPadCursorDebug) "true" else "false"}")
        args.add(
            "-Damethyst.debug.glbridge_swap_heartbeat=" +
                if (enableGlBridgeSwapHeartbeatDebug) "true" else "false"
        )
        args.add("-Djava.home=${javaHome.absolutePath}")
        args.add("-Djava.io.tmpdir=${context.cacheDir.absolutePath}")
        args.add(
            "-Djava.library.path=" +
                NativeLibraryPathResolver.buildJavaLibraryPath(
                    context = context,
                    javaHome = javaHome,
                    appNativeLibraryDir = context.applicationInfo.nativeLibraryDir
                )
        )
        args.add("-Duser.home=${stsHome.absolutePath}")
        args.add("-Duser.dir=${stsRoot.absolutePath}")
        args.add("-Damethyst.expected_exit_marker=${RuntimePaths.expectedGameExitMarker(context).absolutePath}")
        args.add("-Damethyst.in_game_keyboard_request=${RuntimePaths.inGameKeyboardRequestFile(context).absolutePath}")
        args.add(
            "-Damethyst.in_game_lan_game_state_request=" +
                RuntimePaths.inGameLanGameStateRequestFile(context).absolutePath
        )
        args.add("-Damethyst.in_game_file_picker_request=${RuntimePaths.inGameFilePickerRequestFile(context).absolutePath}")
        args.add("-Damethyst.in_game_file_picker_result=${RuntimePaths.inGameFilePickerResultFile(context).absolutePath}")
        args.add("-Damethyst.runtime_rescue_toast_request=${RuntimePaths.runtimeRescueToastRequestFile(context).absolutePath}")
        args.add("-Damethyst.achievement.request_path=${RuntimePaths.achievementRequestFile(context).absolutePath}")
        args.add(
            "-Damethyst.achievement.lock_command_path=" +
                RuntimePaths.achievementLockCommandFile(context).absolutePath
        )
        args.add("-Damethyst.richpresence.path=${RuntimePaths.richPresenceFile(context).absolutePath}")
        val richPresenceDisplay = LauncherConfig.readRichPresenceDisplayPreferences(context)
        args.add("-Damethyst.richpresence.prefix=${richPresenceDisplay.prefix.persistedValue}")
        args.add("-Damethyst.richpresence.device_name=${richPresenceDeviceName()}")
        args.add("-Damethyst.richpresence.show_character=${richPresenceDisplay.showCharacter}")
        args.add("-Damethyst.richpresence.show_floor=${richPresenceDisplay.showFloor}")
        args.add("-Damethyst.richpresence.show_ascension=${richPresenceDisplay.showAscension}")
        args.add("-Damethyst.richpresence.show_act=${richPresenceDisplay.showAct}")
        args.add("-Damethyst.touchscreen_card_hold_state=${RuntimePaths.touchscreenCardHoldStateFile(context).absolutePath}")
        args.add("-Damethyst.easytier.runtime_state_file=${EasyTierStateStore.stateFile(context).absolutePath}")
        args.add(
            "-Damethyst.touchscreen_card_hold_right_click_guard=" +
                if (LauncherConfig.readIgnoreLongPressRightClickWhilePlayingCard(context)) "true" else "false"
        )
        val touchscreenInputMode = LauncherConfig.readTouchscreenInputMode(context)
        val cardPlayOptimizationMode = LauncherConfig.readCardPlayOptimizationMode(context)
        args.add(
            "-Damethyst.touchscreen_enabled=" +
                if (touchscreenInputMode == TouchscreenInputMode.MOBILE) {
                    "true"
                } else {
                    "false"
                }
        )
        args.add(
            "-Damethyst.native_touchscreen_enabled=" +
                if (touchscreenInputMode.touchscreenEnabled) "true" else "false"
        )
        args.add(
            "-Damethyst.touch_indicator_enabled=" +
                if (touchscreenInputMode.touchscreenEnabled &&
                    LauncherConfig.readTouchIndicatorEnabled(context)
                ) {
                    "true"
                } else {
                    "false"
                }
        )
        args.add(
            "-Damethyst.touchscreen_policy=" +
                if (touchscreenInputMode.nativeTouchscreenAllowlistEnabled) {
                    "vanilla_allowlist"
                } else {
                    "global"
                }
        )
        args.add(
            "-Damethyst.runtime_compat.touchscreen_card_play_optimization=" +
                if (cardPlayOptimizationMode.optimizationEnabled) "true" else "false"
        )
        args.add(
            "-Damethyst.runtime_compat.touchscreen_card_gesture=" +
                if (cardPlayOptimizationMode.optimizationEnabled) "true" else "false"
        )
        args.add(
            "-Damethyst.runtime_compat.touchscreen_card_tap_inspect=" +
                if (cardPlayOptimizationMode.tapInspectEnabled) "true" else "false"
        )
        args.add(
            "-Damethyst.runtime_compat.touchscreen_card_tap_play=" +
                if (cardPlayOptimizationMode.tapPlayEnabled) "true" else "false"
        )
        args.add(
            "-Damethyst.runtime_compat.touchscreen_cursor_warp_cleanup=" +
                if (cardPlayOptimizationMode.optimizationEnabled) "true" else "false"
        )
        args.add(
            "-Damethyst.runtime_compat.touchscreen_target_assist=" +
                if (cardPlayOptimizationMode.optimizationEnabled) "true" else "false"
        )
        args.add(
            "-Damethyst.runtime_compat.touchscreen_idle_card_hover_cleanup=" +
                if (cardPlayOptimizationMode.optimizationEnabled) "true" else "false"
        )
        args.add(
            "-Damethyst.font_scale=" +
                LauncherConfig.formatGameplayFontScale(
                    LauncherConfig.readGameplayFontScale(context)
                )
        )
        args.add(
            "-Damethyst.ui_scale=" +
                LauncherConfig.formatGameplayUiScale(
                    LauncherConfig.resolveGameplayUiScale(
                        LauncherConfig.readGameplayLargerUiEnabled(context)
                    )
                )
        )
        args.add(
            "-Damethyst.mobile_hud_enabled=" +
                if (LauncherConfig.readMobileHudEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.compendium_upgrade_touch_fix_enabled=" +
                if (LauncherConfig.readCompendiumUpgradeTouchFixEnabled(context)) {
                    "true"
                } else {
                    "false"
                }
        )
        args.add(
            "-Damethyst.pre_click_hitbox_hover_refresh_enabled=" +
                if (LauncherConfig.readCompendiumUpgradeTouchFixEnabled(context)) {
                    "true"
                } else {
                    "false"
                }
        )
        args.add(
            "-Damethyst.floating_tools.enabled=" +
                if (isMtsLaunchMode(launchMode) &&
                    LauncherConfig.readSpecialKeyInputMode(context) == SpecialKeyInputMode.BUILT_IN_MOD
                ) {
                    "true"
                } else {
                    "false"
                }
        )
        args.add(
            "-Damethyst.floating_tools.interaction_mode=" +
                LauncherConfig.readTouchMouseInteractionMode(context).persistedValue
        )
        args.add(
            "-Damethyst.floating_tools.built_in_keyboard=" +
                if (LauncherConfig.isBuiltInSoftKeyboardEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.floating_tools.auto_switch_left_after_right_click=" +
                if (LauncherConfig.readAutoSwitchLeftAfterRightClick(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.floating_tools.buttons=" +
                LauncherConfig.readFloatingToolButtons(context).joinToString(",")
        )
        args.add(
            "-D$TOGETHER_IN_SPIRE_ROUTE_LOCK_PROPERTY=" +
                LauncherConfig.isTogetherInSpireRouteLockEnabled(context)
        )
        buildEasyTierTogetherInSpireJvmProperties(
            snapshot = easyTierSnapshot,
            autofillEnabled = LauncherConfig.isTogetherInSpireEasyTierAutofillEnabled(context),
        ).forEach { (key, value) ->
            args.add("-D$key=$value")
        }
        args.add("-Duser.language=${Locale.getDefault().language}")
        args.add("-Duser.timezone=${TimeZone.getDefault().id}")
        args.add("-Dos.name=Linux")
        args.add("-Dos.version=Android-${Build.VERSION.RELEASE}")
        args.add("-Djdk.lang.Process.launchMechanism=FORK")
        args.add("-Dorg.lwjgl.opengl.libname=${rendererDecision.effectiveBackend.lwjglOpenGlLibName()}")
        // Clamp reported GL capability to a conservative baseline on GLES bridges.
        // This avoids exposing desktop GL3.3 paths with missing entry points.
        args.add("-Dorg.lwjgl.opengl.maxVersion=3.0")
        args.add("-Dorg.lwjgl.opengles.maxVersion=3.0")
        if (enableLwjglDebug) {
            args.add("-Dorg.lwjgl.util.DebugFunctions=true")
        }
        val appNativeLibraryDir = context.applicationInfo.nativeLibraryDir
        val lwjglLibraryFile = File(appNativeLibraryDir, "liblwjgl.so")
        val openalLibraryFile = File(appNativeLibraryDir, "libopenal.so")
        val lwjglLibraryDir = lwjglLibraryFile.parentFile ?: File(appNativeLibraryDir)
        // Do not expose resource-pack, patch, or market directories to LWJGL's global
        // resolver. Those directories may hold another LWJGL generation; GDX receives its
        // external native directory separately through amethyst.gdx.native_dir below.
        val lwjglLibraryPath = lwjglLibraryDir.absolutePath
        args.add("-Dorg.lwjgl.vulkan.libname=libvulkan.so")
        args.add("-Dorg.lwjgl.libname=${lwjglLibraryFile.absolutePath}")
        args.add("-Dorg.lwjgl.openal.libname=${openalLibraryFile.absolutePath}")
        args.add("-Dorg.lwjgl.librarypath=$lwjglLibraryPath")
        args.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=${lwjglLibraryDir.absolutePath}")
        args.add("-Dorg.lwjgl.system.EmulateSystemLoadLibrary=true")
        args.add("-Damethyst.renderer.selection_mode=${rendererDecision.selectionMode.persistedValue}")
        args.add("-Damethyst.renderer.auto_backend=${rendererDecision.automaticBackend.rendererId()}")
        args.add("-Damethyst.renderer.effective_backend=${rendererDecision.effectiveBackend.rendererId()}")
        args.add("-Damethyst.renderer.requested_surface=${rendererDecision.requestedSurfaceBackend.persistedValue}")
        args.add("-Damethyst.renderer.effective_surface=${rendererDecision.effectiveSurfaceBackend.persistedValue}")
        if (
            rendererDecision.effectiveBackend == RendererBackend.OPENGL_ES2_GL4ES ||
            rendererDecision.effectiveBackend == RendererBackend.OPENGL_ES3_DESKTOPGL_ZINK_KOPPER
        ) {
            args.add("-Damethyst.lwjgl.force_default_framebuffer=true")
        }
        rendererDecision.fallbackSummary()?.let {
            args.add("-Damethyst.renderer.fallback_reason=$it")
        }
        val renderScale = renderScaleOverride ?: LauncherConfig.readRenderScale(context)
        val virtualResolutionMode = LauncherConfig.readVirtualResolutionMode(context)
        val physicalWidth = Math.max(1, CallbackBridge.physicalWidth)
        val physicalHeight = Math.max(1, CallbackBridge.physicalHeight)
        val fullscreenCanvas = FullscreenCanvasResolution.resolve(context)
        val virtualResolution = VirtualResolutionPolicy.resolve(
            physicalWidth = fullscreenCanvas.width,
            physicalHeight = fullscreenCanvas.height,
            renderScale = renderScale,
            mode = virtualResolutionMode
        )
        val launchVirtualSize = resolveLaunchVirtualSize(
            bridgeWidth = CallbackBridge.windowWidth,
            bridgeHeight = CallbackBridge.windowHeight,
            fallbackWidth = virtualResolution.width,
            fallbackHeight = virtualResolution.height
        )
        val virtualWidth = launchVirtualSize.width
        val virtualHeight = launchVirtualSize.height
        val effectiveTargetFps = AndroidGameModeSupport.resolveTargetFps(
            LauncherConfig.readTargetFps(context),
            AndroidGameModeSupport.readCurrentMode(context)
        )
        // The in-JVM LWJGL shim cannot read the real panel refresh rate, so publish the value the
        // launcher itself requested. Without this the game assumes 60Hz and mis-paces every frame.
        val expectedRefreshRateHz = DisplayRefreshRateController.resolveExpectedActiveRefreshRateHz(
            context,
            effectiveTargetFps
        )
        if (expectedRefreshRateHz > 0f) {
            args.add("-Damethyst.gdx.active_refresh_rate=${Math.round(expectedRefreshRateHz)}")
        }
        try {
            // DesktopLauncher reads this file before LibGDX starts. Keep its first Settings
            // initialization aligned with the fixed fullscreen-priority logical canvas.
            DisplayConfigSync.syncToCurrentResolution(
                context = context,
                width = virtualWidth,
                height = virtualHeight,
                targetFpsLimitOverride = effectiveTargetFps
            )
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "Failed to prepare startup display config ${virtualWidth}x${virtualHeight}",
                error
            )
        }
        args.add(
            "-Damethyst.gdx.native_dir=" +
                NativeLibraryPathResolver.buildAmethystGdxNativeDirValue(context)
        )
        println(
            "StsLaunchSpec: " +
                "renderScale=$renderScale, " +
                "virtualMode=${virtualResolutionMode.persistedValue}, " +
                "effectiveScale=${virtualResolution.effectiveScale}, " +
                "virtual=${virtualWidth}x${virtualHeight}, " +
                "glfwstub=${physicalWidth}x${physicalHeight}, " +
                "physical=${physicalWidth}x${physicalHeight}, " +
                "targetFps=$effectiveTargetFps, " +
                "activeRefreshRate=$expectedRefreshRateHz"
        )
        args.add("-Dglfwstub.windowWidth=$virtualWidth")
        args.add("-Dglfwstub.windowHeight=$virtualHeight")
        args.add("-Dglfwstub.physicalWidth=$physicalWidth")
        args.add("-Dglfwstub.physicalHeight=$physicalHeight")
        args.add("-Damethyst.gdx.virtual_width=$virtualWidth")
        args.add("-Damethyst.gdx.virtual_height=$virtualHeight")
        args.add("-Dglfwstub.initEgl=false")
        args.add("-Djava.awt.headless=false")
        args.add("-Dcacio.managed.screensize=${AWTCanvasView.AWT_CANVAS_WIDTH}x${AWTCanvasView.AWT_CANVAS_HEIGHT}")
        args.add("-Dcacio.font.fontmanager=sun.awt.X11FontManager")
        args.add("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler")
        args.add("-Dswing.defaultlaf=javax.swing.plaf.metal.MetalLookAndFeel")
        args.add("-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit")
        args.add("-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment")
        args.add(
            "-Damethyst.gdx.global_atlas_filter_compat=" +
                if (ImportPatchRegistry.isEnabled(context, AtlasFilterPatchModule.id)) "true" else "false"
        )
        args.add(
            "-Damethyst.gdx.runtime_texture_compat=" +
                if (CompatibilitySettings.isRuntimeTextureCompatEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.runtime_compat.main_menu_preview_reuse=" +
                if (CompatibilitySettings.isMainMenuPreviewReuseCompatEnabled(context)) {
                    "true"
                } else {
                    "false"
                }
        )
        args.add(
            "-Damethyst.runtime_compat.card_obtain_effect_ownership=" +
                if (cardObtainEffectOwnershipCompatEnabled) "true" else "false"
        )
        args.add(
            "-Damethyst.runtime_compat.rescue.hand_layout_room_context=" +
                if (CompatibilitySettings.isRoomContextHandLayoutRescueCompatEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.runtime_compat.rescue.room_transition=" +
                if (CompatibilitySettings.isRoomTransitionRescueCompatEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.runtime_compat.rescue.event_room=" +
                if (CompatibilitySettings.isEventRoomRescueCompatEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.runtime_compat.rescue.shop_room=" +
                if (CompatibilitySettings.isShopRoomRescueCompatEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.runtime_compat.rescue.basemod_save_load=" +
                if (CompatibilitySettings.isBaseModSaveLoadRescueCompatEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.runtime_compat.rescue.relic_enter_room=" +
                if (CompatibilitySettings.isRelicEnterRoomRescueCompatEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.runtime_compat.rescue.dungeon_render_room_context=" +
                if (CompatibilitySettings.isDungeonRenderRoomContextRescueCompatEnabled(context)) {
                    "true"
                } else {
                    "false"
                }
        )
        args.add(
            "-Damethyst.runtime_compat.rescue.power_icon_render=" +
                if (CompatibilitySettings.isPowerIconRenderRescueCompatEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.runtime_compat.rescue.basemod_custom_monster_render=" +
                if (CompatibilitySettings.isBaseModCustomMonsterRenderRescueCompatEnabled(context)) {
                    "true"
                } else {
                    "false"
                }
        )
        args.add(
            "-Damethyst.runtime_compat.rescue.non_combat_player_render=" +
                if (CompatibilitySettings.isNonCombatPlayerRenderRescueCompatEnabled(context)) {
                    "true"
                } else {
                    "false"
                }
        )
        args.add(
            "-Damethyst.runtime_compat.rescue.card_tooltip_keyword=" +
                if (CompatibilitySettings.isCardTooltipKeywordRescueCompatEnabled(context)) {
                    "true"
                } else {
                    "false"
                }
        )
        val runtimeDownscalePolicy = CompatibilitySettings.readRuntimeDownscaleMaterialPolicy(context)
        val texturePressureDownscaleEnabled = resolveTexturePressureDownscaleEnabled(
            ramSaverEnabled = ramSaverEnabled,
            configuredEnabled = CompatibilitySettings.isLargeTextureDownscaleCompatEnabled(context)
        )
        args.add(
            "-Damethyst.gdx.texture_pressure_downscale=" +
                if (texturePressureDownscaleEnabled) "true" else "false"
        )
        args.add(
            "-Damethyst.gdx.texture_residency_manager=" +
                if (CompatibilitySettings.isTextureResidencyManagerCompatEnabled(context)) {
                    "true"
                } else {
                    "false"
                }
        )
        args.add(
            "-Damethyst.gdx.texture_residency_skip_for_ramsaver=" +
                if (ramSaverEnabled) "true" else "false"
        )
        args.add(
            "-Damethyst.gdx.texture_pressure_downscale_divisor=" +
                CompatibilitySettings.readTexturePressureDownscaleDivisor(context)
        )
        args.add("-Damethyst.gdx.texture_pressure_downscale_max_pixels=2073600")
        args.add("-Damethyst.gdx.texture_pressure_downscale_max_edge=1920")
        args.add(
            "-Damethyst.gdx.texture_pressure_downscale.allow_ordinary_textures=" +
                if (runtimeDownscalePolicy.ordinaryTextures) "true" else "false"
        )
        args.add(
            "-Damethyst.gdx.texture_pressure_downscale.allow_texture_atlas_pages=" +
                if (runtimeDownscalePolicy.textureAtlasPages.enabled) "true" else "false"
        )
        args.add(
            "-Damethyst.gdx.texture_pressure_downscale.texture_atlas_max_pixels=" +
                runtimeDownscalePolicy.textureAtlasPages.maxPixels
        )
        args.add(
            "-Damethyst.gdx.texture_pressure_downscale.texture_atlas_max_edge=" +
                runtimeDownscalePolicy.textureAtlasPages.maxEdge
        )
        args.add(
            "-Damethyst.gdx.texture_pressure_downscale.allow_spine=" +
                if (runtimeDownscalePolicy.spineTextures) "true" else "false"
        )
        val gpuResourceGuardianMode = resolveGpuResourceGuardianModeForLaunch(
            ramSaverEnabled = ramSaverEnabled,
            configuredMode = LauncherConfig.readGpuResourceGuardianMode(context)
        )
        args.add(
            "-Damethyst.gdx.gpu_resource_guardian=" +
                gpuResourceGuardianMode.runtimePropertyValue
        )
        if (gpuResourceGuardianMode == GpuResourceGuardianMode.ULTRA_AGGRESSIVE) {
            args.add("-Damethyst.gdx.gpu_guardian_sync_restore_max_bytes=67108864")
            args.add("-Damethyst.gdx.gpu_guardian_sync_restore_budget_bytes_per_frame=67108864")
        }
        args.add(
            "-Damethyst.gdx.force_linear_mipmap_filter=" +
                if (CompatibilitySettings.isForceLinearMipmapFilterEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.runtime_compat.hina_character_render=" +
                if (CompatibilitySettings.isHinaCharacterRenderCompatEnabled(context)) {
                    "true"
                } else {
                    "false"
                }
        )
        args.add(
            "-Damethyst.gdx.non_renderable_fbo_format_compat=" +
                if (CompatibilitySettings.isNonRenderableFboFormatCompatEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.gdx.fbo_manager=" +
                if (CompatibilitySettings.isFboManagerCompatEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.gdx.fbo_idle_reclaim=" +
                if (CompatibilitySettings.isFboIdleReclaimCompatEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.gdx.fbo_pressure_downscale=" +
                if (resolveFboPressureDownscaleEnabled(
                        ramSaverEnabled = ramSaverEnabled,
                        configuredEnabled = CompatibilitySettings.isFboPressureDownscaleCompatEnabled(context),
                        offscreenFrameBuffersEnabled = runtimeDownscalePolicy.offscreenFrameBuffers
                    )
                ) "true" else "false"
        )
        args.add(
            "-Damethyst.gdx.fragment_shader_precision_compat=" +
                if (CompatibilitySettings.isFragmentShaderPrecisionCompatEnabled(context)) {
                    "true"
                } else {
                    "false"
                }
        )
        args.add(
            "-Damethyst.gdx.gpu_resource_diag=" +
                if (LauncherConfig.isGpuResourceDiagEnabled(context)) "true" else "false"
        )
        if (performanceDeepDiagnostics) {
            args.add("-Damethyst.gdx.gpu_resource_summary=true")
            args.add("-Damethyst.gdx.frame_hud=$showPerformanceOverlay")
        }
        if (ramSaverEnabled) {
            // Scale ram-saver's hot-pin texture budget with the heap size.
            // The default 384 MB was calibrated for a 512 MB heap (75%).
            // At 1024 MB the same ratio gives ~768 MB, keeping the proportion
            // consistent so the hot-pin set doesn't shrink relative to available
            // memory and cause DrawCardAction flush-spikes from atlas re-uploads.
            val hotBudgetMb = (heapMaxMb * 0.75).toLong().coerceIn(256L, 1536L)
            args.add("-Dramsaver.hot.budget_mb=$hotBudgetMb")
            // Pin textures for the full combat duration (up to 10 min).
            // 120 s was too short — textures were age-dropped mid-combat and
            // re-materialized during DrawCardAction, multiplying SpriteBatch flushes.
            args.add("-Dramsaver.hot.pin_seconds=600")
        }
        val debugPropertyResult = addDebugGpuGuardianTestProperties(context, args)
        logEffectivePerformanceProperties(
            args = args,
            showPerformanceOverlay = showPerformanceOverlay,
            performanceDeepDiagnostics = performanceDeepDiagnostics,
            debugPropertyResult = debugPropertyResult
        )
        val bridgeDelegateMainClass = if (isMtsLaunchMode(launchMode)) {
            "com.evacipated.cardcrawl.modthespire.Loader"
        } else {
            "com.megacrit.cardcrawl.desktop.DesktopLauncher"
        }
        args.add("-Damethyst.bridge.delegate=$bridgeDelegateMainClass")
        args.add("-Damethyst.bridge.mode=$launchMode")
        args.add("-Damethyst.debug.force_jvm_crash=${if (forceJvmCrash) "true" else "false"}")
        args.add("-Damethyst.debug.force_runtime_crash=${if (BuildConfig.BUILD_TYPE == "debug" && forceRuntimeCrash) "true" else "false"}")
        // Bundled amethyst-runtime-compat reads these to enable and configure the autoplay driver.
        // Vanilla launches ignore them (the properties are never read).
        val effectiveAutoplay = autoplay && isMtsLaunchMode(launchMode)
        args.add("-Damethyst.debug.autoplay=${if (effectiveAutoplay) "true" else "false"}")
        args.add(
            "-Damethyst.debug.autoplay.mode=" +
                if (effectiveAutoplay) {
                    autoplayMode.persistedValue
                } else {
                    AutoplayMode.DEFAULT.persistedValue
                }
        )
        args.add(
            "-Damethyst.debug.autoplay.save_mode=" +
                if (effectiveAutoplay) {
                    autoplaySaveMode.persistedValue
                } else {
                    AutoplaySaveMode.DEFAULT.persistedValue
                }
        )
        args.add(
            "-Damethyst.debug.autoplay.single_room_spec=" +
                if (effectiveAutoplay && autoplayMode == AutoplayMode.SINGLE_ROOM) {
                    autoplaySingleRoomSpecPath
                } else {
                    ""
                }
        )
        args.add(
            "-Damethyst.debug.autoplay.choice_delay_ms=" +
                if (effectiveAutoplay) {
                    autoplayChoiceDelayMs.coerceAtLeast(0L).toString()
                } else {
                    "0"
                }
        )
        // bench mode: played by perf-bench harness — infinite energy + HP, full autoplay
        args.add(
            "-Damethyst.debug.autoplay.single_room_bench_mode=" +
                if (effectiveAutoplay && autoplayMode == AutoplayMode.SINGLE_ROOM
                    && autoplaySingleRoomBenchMode) "true" else "false"
        )
        // wait_for_agent=false: perf-bench and normal smoke autoplay do not use an agent.
        // Only set true when an external agent is explicitly requested.
        args.add("-Damethyst.autoplay.wait_for_agent=false")
        args.add("-Damethyst.bridge.events=${RuntimePaths.bootBridgeEventsLog(context).absolutePath}")
        if (isMtsLaunchMode(launchMode)) {
            args.add("-Damethyst.mts.mod_file_list=${RuntimePaths.mtsModFileList(context).absolutePath}")
            MtsPatchCacheCoordinator.appendRuntimeProperties(
                context = context,
                args = args,
                enabled = LauncherConfig.isMtsPatchCacheEnabled(context)
            )
        }
        if (showPerformanceOverlay || performanceDeepDiagnostics) {
            args.add("-Damethyst.bridge.heap_snapshot=${RuntimePaths.jvmHeapSnapshot(context).absolutePath}")
            args.add("-Damethyst.bridge.launcher_perf_snapshot=${RuntimePaths.launcherPerfSnapshot(context).absolutePath}")
        }
        if (performanceDeepDiagnostics) {
            args.add("-Damethyst.bridge.gc_histogram_dir=${RuntimePaths.jvmHistogramsDir(context).absolutePath}")
        }

        addCacioBootClasspath(args, RuntimePaths.cacioDir(context))

        args.add("-javaagent:${RuntimePaths.lwjgl2InjectorJar(context).absolutePath}")
        if (shouldEnableGameProbe(
                launchMode = launchMode,
                debugMode = debugMode,
                autoplay = autoplay,
                forceJvmCrash = forceJvmCrash,
                forceRuntimeCrash = forceRuntimeCrash,
                performanceDeepDiagnostics = performanceDeepDiagnostics
            )
        ) {
            val gameProbeArgs = buildString {
                append("port=9099")
                if (performanceDeepDiagnostics) {
                    val outputDir = RuntimePaths.offlineArthasOutputDir(context)
                    if (!outputDir.exists()) {
                        outputDir.mkdirs()
                    }
                    append(",arthas=true")
                    append(",arthasPort=8099")
                    append(",arthasDelaySeconds=15")
                    append(",arthasOutputDir=").append(outputDir.absolutePath)
                    append(",arthasHome=")
                        .append(RuntimePaths.arthasResourceCurrentDir(context).absolutePath)
                }
            }
            args.add("-javaagent:${RuntimePaths.agentConnectorJar(context).absolutePath}=$gameProbeArgs")
        }
        args.add("-cp")
        if (isMtsLaunchMode(launchMode)) {
            val classpathEntries = arrayListOf(
                RuntimePaths.bootBridgeJar(context).absolutePath,
                RuntimePaths.lwjglJar(context).absolutePath,
                RuntimePaths.mtsGdxApiJar(context).absolutePath
            )
            if (RuntimePaths.bundledLog4jApiJar(context).isFile) {
                classpathEntries.add(RuntimePaths.bundledLog4jApiJar(context).absolutePath)
            }
            if (RuntimePaths.bundledLog4jCoreJar(context).isFile) {
                classpathEntries.add(RuntimePaths.bundledLog4jCoreJar(context).absolutePath)
            }
            classpathEntries.add(RuntimePaths.mtsStsResourcesJar(context).absolutePath)
            classpathEntries.add(RuntimePaths.mtsBaseModResourcesJar(context).absolutePath)
            classpathEntries.add(RuntimePaths.importedMtsJar(context).absolutePath)
            args.add(classpathEntries.joinToString(":"))
            args.add("io.stamethyst.bridge.BootBridgeLauncher")
            // Prevent ModTheSpire from attempting desktop-style self-restart via jre1.8.0_51
            // and exiting the Android process immediately.
            args.add("--jre51")
            args.add("--skip-launcher")
            val launchMods: List<String> = try {
                ModManager.resolveLaunchModIds(context)
            } catch (_: Exception) {
                Arrays.asList(ModManager.MOD_ID_BASEMOD, ModManager.MOD_ID_STSLIB)
            }
            args.add("--mods")
            args.add(joinModIds(launchMods))
        } else {
            args.add(
                RuntimePaths.bootBridgeJar(context).absolutePath +
                    ":" + RuntimePaths.gdxPatchJar(context).absolutePath +
                    ":" + RuntimePaths.lwjglJar(context).absolutePath +
                    ":" + RuntimePaths.importedStsJar(context).absolutePath
            )
            args.add("io.stamethyst.bridge.BootBridgeLauncher")
        }
        return args
    }

    private fun resolveRendererDecision(context: Context): RendererDecision {
        return RendererBackendResolver.resolve(
            context = context,
            requestedSurfaceBackend = LauncherConfig.readRenderSurfaceBackend(context),
            selectionMode = LauncherConfig.readRendererSelectionMode(context),
            manualBackend = LauncherConfig.readManualRendererBackend(context)
        )
    }

    internal fun resolveLaunchVirtualSize(
        bridgeWidth: Int,
        bridgeHeight: Int,
        fallbackWidth: Int,
        fallbackHeight: Int
    ): FullscreenCanvasSize {
        return if (bridgeWidth > 0 && bridgeHeight > 0) {
            FullscreenCanvasSize(bridgeWidth, bridgeHeight)
        } else {
            FullscreenCanvasSize(
                fallbackWidth.coerceAtLeast(1),
                fallbackHeight.coerceAtLeast(1)
            )
        }
    }

    private fun joinModIds(modIds: List<String>): String {
        val builder = StringBuilder()
        for (modId in modIds) {
            val value = modId.trim()
            if (value.isEmpty()) {
                continue
            }
            if (builder.isNotEmpty()) {
                builder.append(",")
            }
            builder.append(value)
        }
        return builder.toString()
    }

    internal fun shouldEnableGameProbe(
        launchMode: String,
        debugMode: Boolean = false,
        autoplay: Boolean,
        forceJvmCrash: Boolean,
        forceRuntimeCrash: Boolean,
        performanceDeepDiagnostics: Boolean
    ): Boolean {
        return isMtsLaunchMode(launchMode) &&
            (debugMode || autoplay || forceJvmCrash || forceRuntimeCrash || performanceDeepDiagnostics)
    }

    internal fun resolveTexturePressureDownscaleEnabled(
        ramSaverEnabled: Boolean,
        configuredEnabled: Boolean
    ): Boolean {
        return !ramSaverEnabled && configuredEnabled
    }

    /**
     * Ram Saver's release path depends on the collector actually clearing and enqueuing its weak
     * references: `RamSaver.update` drains a [java.lang.ref.ReferenceQueue] and only disposes the
     * backing texture for entries it finds there. Its `AggressiveGC` patch calls `System.gc()` from
     * BaseMod/FontHelper lifecycle points to make that happen promptly after startup work has made
     * a batch of assets collectible. `-XX:+DisableExplicitGC` turns those calls into no-ops, which
     * delays reference clearing and therefore delays freeing native texture memory — the opposite of
     * what Ram Saver is installed to do.
     *
     * Without Ram Saver nothing in the app calls `System.gc()`, so suppressing explicit GC still
     * protects against third-party mods forcing full collections mid-frame.
     */
    internal fun resolveDisableExplicitGcEnabled(ramSaverEnabled: Boolean): Boolean {
        return !ramSaverEnabled
    }

    /**
     * When explicit GC is left enabled for Ram Saver, run those collections concurrently instead of
     * as a full stop-the-world pause.
     *
     * `System.gc()` defaults to a full STW collection, which is a frame hitch on the render thread.
     * `-XX:+ExplicitGCInvokesConcurrent` redirects it to a G1 concurrent cycle, which still clears and
     * enqueues the weak references that `RamSaver.update` drains — so the native texture release path
     * this flag combination exists to protect keeps working — without stalling the frame.
     *
     * Only meaningful when explicit GC is actually reachable, so it is skipped whenever
     * [resolveDisableExplicitGcEnabled] already turned those calls into no-ops. The flag is G1-only,
     * so the caller applies it inside the 64-bit branch that selects `-XX:+UseG1GC`.
     */
    internal fun resolveExplicitGcInvokesConcurrentEnabled(disableExplicitGc: Boolean): Boolean {
        return !disableExplicitGc
    }

    internal fun resolveGpuResourceGuardianModeForLaunch(
        ramSaverEnabled: Boolean,
        configuredMode: GpuResourceGuardianMode
    ): GpuResourceGuardianMode {
        return if (ramSaverEnabled) GpuResourceGuardianMode.OFF else configuredMode
    }

    internal fun resolveFboPressureDownscaleEnabled(
        ramSaverEnabled: Boolean,
        configuredEnabled: Boolean,
        offscreenFrameBuffersEnabled: Boolean
    ): Boolean {
        return !ramSaverEnabled && configuredEnabled && offscreenFrameBuffersEnabled
    }

    internal fun buildEasyTierTogetherInSpireJvmProperties(
        snapshot: EasyTierConnectionSnapshot?,
        autofillEnabled: Boolean,
    ): Map<String, String> {
        val properties = linkedMapOf(
            TOGETHER_IN_SPIRE_EASYTIER_AUTOFILL_PROPERTY to autofillEnabled.toString(),
        )
        if (!autofillEnabled) {
            return properties
        }
        properties["amethyst.easytier.together_in_spire.port"] =
            EASYTIER_TOGETHER_IN_SPIRE_PORT
        if (snapshot == null || snapshot.status != EasyTierConnectionStatus.CONNECTED) {
            return properties
        }
        val ownerHost = extractEasyTierIpv4Host(snapshot.roomOwnerIpv4Cidr)
        val localHost = extractEasyTierIpv4Host(snapshot.assignedIpv4Cidr)
        if (ownerHost.isBlank() || localHost.isBlank()) {
            return properties
        }
        putIfNotBlank(
            properties,
            "amethyst.easytier.together_in_spire.host_ip",
            ownerHost,
        )
        putIfNotBlank(properties, "amethyst.easytier.assigned_ipv4_cidr", snapshot.assignedIpv4Cidr)
        putIfNotBlank(properties, "amethyst.easytier.current_player_id", snapshot.currentPlayerId)
        putIfNotBlank(properties, "amethyst.easytier.room_owner_player_id", snapshot.roomOwnerPlayerId)
        putIfNotBlank(properties, "amethyst.easytier.room_id", snapshot.roomId)
        return properties
    }

    internal fun extractEasyTierIpv4Host(value: String): String {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            return ""
        }
        val host = normalized.substringBefore('/').trim()
        val octets = host.split('.')
        if (octets.size != 4) {
            return ""
        }
        return if (octets.all { octet ->
                octet.all(Char::isDigit) && octet.toIntOrNull()?.let { it in 0..255 } == true
            }
        ) {
            host
        } else {
            ""
        }
    }

    private fun addDebugGpuGuardianTestProperties(
        context: Context,
        args: MutableList<String>
    ): DebugJvmPropertyAppendResult {
        if (BuildConfig.BUILD_TYPE != "debug") {
            return DebugJvmPropertyAppendResult(emptyList(), emptyList())
        }
        val prefs = context.getSharedPreferences(DEBUG_GPU_GUARDIAN_TEST_PREFS, Context.MODE_PRIVATE)
        val rawProperties = LinkedHashMap<String, String>()
        for (key in DEBUG_GPU_GUARDIAN_PROPERTY_KEYS) {
            rawProperties[key] = prefs.getString(key, null).orEmpty()
        }
        return appendDebugJvmPropertiesForLaunch(args, rawProperties)
    }

    internal fun appendDebugJvmPropertiesForLaunch(
        args: MutableList<String>,
        rawProperties: Map<String, String>
    ): DebugJvmPropertyAppendResult {
        val appendedKeys = ArrayList<String>()
        val skippedManagedKeys = ArrayList<String>()
        for (key in DEBUG_GPU_GUARDIAN_PROPERTY_KEYS) {
            val value = rawProperties[key]?.trim().orEmpty()
            if (value.isEmpty() || !isSafeJvmPropertyValue(value)) {
                continue
            }
            if (hasJvmProperty(args, key)) {
                skippedManagedKeys.add(key)
                continue
            }
            args.add("-D$key=$value")
            appendedKeys.add(key)
        }
        return DebugJvmPropertyAppendResult(appendedKeys, skippedManagedKeys)
    }

    private fun isSafeJvmPropertyValue(value: String): Boolean {
        if (value.length > 128) {
            return false
        }
        return value.all { char ->
            char.isLetterOrDigit() || char == '_' || char == '-' || char == '.'
        }
    }

    private fun logEffectivePerformanceProperties(
        args: List<String>,
        showPerformanceOverlay: Boolean,
        performanceDeepDiagnostics: Boolean,
        debugPropertyResult: DebugJvmPropertyAppendResult
    ) {
        val effectiveProperties = EFFECTIVE_PERFORMANCE_PROPERTY_KEYS.joinToString(",") { key ->
            "$key=${readEffectiveJvmProperty(args, key) ?: "<unset>"}"
        }
        Log.i(
            TAG,
            "performanceOverlay=$showPerformanceOverlay, " +
                "deepDiagnostics=$performanceDeepDiagnostics, " +
                "debugJvmPropertiesApplied=" +
                debugPropertyResult.appendedKeys.joinToString("|").ifEmpty { "none" } +
                ", debugJvmPropertiesSkippedManaged=" +
                debugPropertyResult.skippedManagedKeys.joinToString("|").ifEmpty { "none" } +
                ", effectivePerformanceProperties={$effectiveProperties}"
        )
    }

    private fun hasJvmProperty(args: List<String>, key: String): Boolean {
        return readEffectiveJvmProperty(args, key) != null
    }

    private fun richPresenceDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return when {
            model.isEmpty() -> manufacturer.ifEmpty { "Android" }
            manufacturer.isEmpty() || model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
    }

    private fun readEffectiveJvmProperty(args: List<String>, key: String): String? {
        val exact = "-D$key"
        val prefix = "$exact="
        for (index in args.indices.reversed()) {
            val arg = args[index]
            if (arg == exact) {
                return ""
            }
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length)
            }
        }
        return null
    }

    private fun putIfNotBlank(
        target: MutableMap<String, String>,
        key: String,
        value: String,
    ) {
        if (value.isNotBlank()) {
            target[key] = value
        }
    }

    private fun addCacioBootClasspath(args: MutableList<String>, cacioDir: File) {
        val files = cacioDir.listFiles()
            ?: throw IllegalStateException("Missing caciocavallo directory: ${cacioDir.absolutePath}")
        val jars = ArrayList<File>()
        for (file in files) {
            if (file.isFile && file.name.endsWith(".jar")) {
                jars.add(file)
            }
        }
        if (jars.isEmpty()) {
            throw IllegalStateException("No caciocavallo jars found in ${cacioDir.absolutePath}")
        }
        jars.sortWith { a, b -> a.name.compareTo(b.name, ignoreCase = true) }

        val boot = StringBuilder("-Xbootclasspath/p")
        for (jar in jars) {
            boot.append(":").append(jar.absolutePath)
        }
        args.add(boot.toString())
    }

    private fun is64BitRuntime(javaHome: File): Boolean {
        return File(javaHome, "lib/aarch64").isDirectory ||
            File(javaHome, "lib/arm64").isDirectory ||
            File(javaHome, "lib/x86_64").isDirectory
    }

}
