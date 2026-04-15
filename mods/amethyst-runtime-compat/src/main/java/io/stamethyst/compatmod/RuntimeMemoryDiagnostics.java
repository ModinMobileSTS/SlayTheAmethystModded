package io.stamethyst.compatmod;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.characters.CharacterManager;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.screens.charSelect.CharacterOption;
import com.megacrit.cardcrawl.screens.charSelect.CharacterSelectScreen;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;

import java.lang.reflect.Method;
import java.util.ArrayList;

public final class RuntimeMemoryDiagnostics {
    private static final String MENU_TEXTURE_WINDOW_LABEL = "menu_cycle";
    private static final String MODDED_OPTIONS_TEXTURE_WINDOW_LABEL = "modded_options_build";
    private static final String CHARACTER_RECREATE_TEXTURE_WINDOW_PREFIX = "recreate_character";
    private static final String MENU_DIAG_PROP = "amethyst.runtime_compat.menu_diag";
    private static final String MENU_DIAG_VERBOSE_PROP = "amethyst.runtime_compat.menu_diag_verbose";
    private static final String MENU_DIAG_HOTSPOTS_PROP = "amethyst.runtime_compat.menu_diag_hotspots";
    private static final String GPU_RESOURCE_DIAG_PROP = "amethyst.gdx.gpu_resource_diag";
    private static final String FBO_MANAGER_PROP = "amethyst.gdx.fbo_manager";
    private static final boolean MENU_DIAG_ENABLED =
        readBooleanSystemProperty(MENU_DIAG_PROP, true);
    private static final boolean MENU_DIAG_VERBOSE_ENABLED =
        readBooleanSystemProperty(MENU_DIAG_VERBOSE_PROP, false);
    private static final boolean GPU_RESOURCE_DIAG_ENABLED =
        readBooleanSystemProperty(GPU_RESOURCE_DIAG_PROP, false);
    private static final boolean FBO_MANAGER_ENABLED =
        readBooleanSystemProperty(FBO_MANAGER_PROP, true);
    private static final boolean MENU_DIAG_HOTSPOTS_ENABLED =
        readBooleanSystemProperty(MENU_DIAG_HOTSPOTS_PROP, MENU_DIAG_ENABLED);
    private static long mainMenuConstructorStartNs = -1L;
    private static long characterSelectInitializeStartNs = -1L;
    private static CardCrawlGame.GameMode lastMode;
    private static MainMenuScreen.CurScreen lastMenuScreen;
    private static int lastMainMenuIdentity;
    private static int lastCharSelectIdentity;
    private static boolean startupConfigurationLogged;
    private static boolean gpuSummaryMethodsResolved;
    private static boolean gpuSummaryLookupFailureLogged;
    private static boolean gpuSummaryInvokeFailureLogged;
    private static Method glTextureSummaryMethod;
    private static Method glTextureLiveSourceSummaryMethod;
    private static Method glTextureLiveOwnerSummaryMethod;
    private static Method glTextureUploadSourceSummaryMethod;
    private static Method glTextureUploadOwnerSummaryMethod;
    private static Method glTextureReleaseSourceSummaryMethod;
    private static Method glTextureReleaseOwnerSummaryMethod;
    private static Method glTextureBeginDebugWindowMethod;
    private static Method glTextureFinishDebugWindowMethod;
    private static Method glFrameBufferSummaryMethod;
    private static Method glFrameBufferLiveOwnerSummaryMethod;
    private static final ThreadLocal<CharacterRecreateTrace> characterRecreateTrace =
        new ThreadLocal<CharacterRecreateTrace>();
    private static final ThreadLocal<Boolean> characterRecreateShortcutLogged =
        new ThreadLocal<Boolean>();

    private RuntimeMemoryDiagnostics() {
    }

    public static void logStartupConfiguration() {
        synchronized (RuntimeMemoryDiagnostics.class) {
            if (startupConfigurationLogged) {
                return;
            }
            startupConfigurationLogged = true;
        }
        System.out.println(
            "[amethyst-runtime-diag] init"
                + " previewReuseEnabled=" + MainMenuPreviewStrategy.isEnabled()
                + " fboManagerEnabled=" + FBO_MANAGER_ENABLED
                + " summaryLogs=" + MENU_DIAG_ENABLED
                + " verboseLogs=" + MENU_DIAG_VERBOSE_ENABLED
                + " hotspotLogs=" + MENU_DIAG_HOTSPOTS_ENABLED
                + " gpuDiagTrace=" + GPU_RESOURCE_DIAG_ENABLED
        );
    }

    public static boolean isMainMenuPreviewReuseEnabled() {
        return MainMenuPreviewStrategy.isEnabled();
    }

    public static void onMainMenuConstructorBegin(boolean screenState) {
        MainMenuPreviewStrategy.onMainMenuConstructorBegin();
        mainMenuConstructorStartNs = shouldTrackDurations() ? System.nanoTime() : -1L;
        beginTextureDebugWindow(MENU_TEXTURE_WINDOW_LABEL);
        logSummaryOrVerbose(
            "menu_cycle_begin",
            "ctorArg=" + screenState + " previewReuseEnabled=" + MainMenuPreviewStrategy.isEnabled(),
            null,
            null,
            false
        );
    }

    public static void onMainMenuConstructorEnd(MainMenuScreen screen, boolean screenState) {
        MainMenuPreviewStrategy.Snapshot snapshot = MainMenuPreviewStrategy.finishMainMenuConstruction();
        StringBuilder builder = new StringBuilder(256);
        builder.append("ctorArg=").append(screenState);
        builder.append(" durationMs=").append(consumeMainMenuConstructorDurationMs());
        appendPreviewSummary(builder, snapshot);
        appendTextureDebugWindowSummary(builder, MENU_TEXTURE_WINDOW_LABEL);
        logSummaryOrVerbose(
            "menu_cycle_end",
            builder.toString(),
            screen,
            screen == null ? null : screen.charSelectScreen,
            true
        );
    }

    public static void onCharacterSelectInitializeBegin(CharacterSelectScreen screen) {
        MainMenuPreviewStrategy.onCharacterSelectInitializeBegin();
        characterSelectInitializeStartNs = shouldTrackDurations() ? System.nanoTime() : -1L;
        logVerbose("char_select_initialize_begin", null, null, screen, false);
    }

    public static void onCharacterSelectInitializeEnd(CharacterSelectScreen screen) {
        MainMenuPreviewStrategy.onCharacterSelectInitializeEnd();
        logVerbose(
            "char_select_initialize_end",
            "durationMs=" + consumeCharacterSelectInitializeDurationMs(),
            null,
            screen,
            false
        );
    }

    public static ArrayList<CharacterOption> buildModdedCharacterOptions() {
        long startedAtNs = shouldTrackDurations() ? System.nanoTime() : -1L;
        beginTextureDebugWindow(MODDED_OPTIONS_TEXTURE_WINDOW_LABEL);
        ArrayList<CharacterOption> options = MainMenuPreviewStrategy.buildModdedCharacterOptions();
        MainMenuPreviewStrategy.Snapshot snapshot = MainMenuPreviewStrategy.snapshot();
        StringBuilder builder = new StringBuilder(256);
        builder.append("durationMs=").append(toDurationMs(startedAtNs));
        builder.append(" optionsBuilt=").append(options == null ? -1 : options.size());
        builder.append(" moddedCharacters=").append(snapshot.moddedCharacterOptionCount);
        builder.append(" previewReuseModded=").append(snapshot.previewReuseModdedCount);
        builder.append(" previewForcedRecreateModded=").append(snapshot.previewForcedRecreateModdedCount);
        builder.append(" textureCacheHits=").append(snapshot.moddedCharacterTextureCacheHits);
        builder.append(" textureCacheMisses=").append(snapshot.moddedCharacterTextureCacheMisses);
        builder.append(" textureCacheSize=").append(snapshot.cachedCharacterOptionTextureCount);
        appendTextureDebugWindowSummary(builder, MODDED_OPTIONS_TEXTURE_WINDOW_LABEL);
        logSummaryOrVerbose("modded_options_build", builder.toString(), null, null, true);
        return options;
    }

    public static void onCharacterSelectOpen(CharacterSelectScreen screen, boolean sourceFlag) {
        logVerbose(
            "char_select_open",
            "openArg=" + sourceFlag,
            null,
            screen,
            false
        );
    }

    public static void observeGameState() {
        if (!MENU_DIAG_VERBOSE_ENABLED) {
            return;
        }
        MainMenuScreen mainMenuScreen = CardCrawlGame.mainMenuScreen;
        CharacterSelectScreen charSelectScreen = resolveCharSelect(mainMenuScreen);
        CardCrawlGame.GameMode mode = CardCrawlGame.mode;
        MainMenuScreen.CurScreen menuScreen = mainMenuScreen == null ? null : mainMenuScreen.screen;
        int mainMenuIdentity = identityHash(mainMenuScreen);
        int charSelectIdentity = identityHash(charSelectScreen);
        CardCrawlGame.GameMode previousMode;
        MainMenuScreen.CurScreen previousMenuScreen;
        int previousMainMenuIdentity;
        int previousCharSelectIdentity;
        synchronized (RuntimeMemoryDiagnostics.class) {
            boolean changed =
                lastMode != mode
                    || lastMenuScreen != menuScreen
                    || lastMainMenuIdentity != mainMenuIdentity
                    || lastCharSelectIdentity != charSelectIdentity;
            if (!changed) {
                return;
            }
            previousMode = lastMode;
            previousMenuScreen = lastMenuScreen;
            previousMainMenuIdentity = lastMainMenuIdentity;
            previousCharSelectIdentity = lastCharSelectIdentity;
            lastMode = mode;
            lastMenuScreen = menuScreen;
            lastMainMenuIdentity = mainMenuIdentity;
            lastCharSelectIdentity = charSelectIdentity;
        }
        logVerbose(
            "game_state_change",
            "previousMode=" + safeName(previousMode)
                + " previousMenuScreen=" + safeName(previousMenuScreen)
                + " previousMenuId=" + previousMainMenuIdentity
                + " previousCharSelectId=" + previousCharSelectIdentity,
            mainMenuScreen,
            charSelectScreen,
            false
        );
    }

    public static void onStartOverRequested(String source) {
        logSummaryOrVerbose(
            "start_over_requested",
            "source=" + source + " chosenCharacter=" + safePlayerClassName(CardCrawlGame.chosenCharacter),
            CardCrawlGame.mainMenuScreen,
            resolveCharSelect(CardCrawlGame.mainMenuScreen),
            true
        );
    }

    public static void onAbstractDungeonResetBegin() {
        if (!MENU_DIAG_VERBOSE_ENABLED) {
            return;
        }
        StringBuilder builder = new StringBuilder(256);
        builder.append("chosenCharacter=").append(safePlayerClassName(CardCrawlGame.chosenCharacter));
        appendCharacterRef(builder, "dungeonPlayer", AbstractDungeon.player);
        appendCharacterRef(builder, "chosenMaster", resolveMasterCharacter(CardCrawlGame.chosenCharacter));
        logVerbose("dungeon_reset_begin", builder.toString(), null, null, false);
    }

    public static void onAbstractDungeonResetEnd() {
        if (!MENU_DIAG_VERBOSE_ENABLED) {
            return;
        }
        StringBuilder builder = new StringBuilder(256);
        builder.append("chosenCharacter=").append(safePlayerClassName(CardCrawlGame.chosenCharacter));
        appendCharacterRef(builder, "dungeonPlayer", AbstractDungeon.player);
        appendCharacterRef(builder, "chosenMaster", resolveMasterCharacter(CardCrawlGame.chosenCharacter));
        logVerbose("dungeon_reset_end", builder.toString(), null, null, false);
    }

    public static void onCreateCharacterBegin(AbstractPlayer.PlayerClass playerClass) {
        if (!MENU_DIAG_VERBOSE_ENABLED) {
            return;
        }
        StringBuilder builder = new StringBuilder(256);
        builder.append("targetClass=").append(safePlayerClassName(playerClass));
        builder.append(" loadingSave=").append(CardCrawlGame.loadingSave);
        builder.append(" startOver=").append(CardCrawlGame.startOver);
        appendCharacterRef(builder, "dungeonPlayer", AbstractDungeon.player);
        appendCharacterRef(builder, "masterPlayer", resolveMasterCharacter(playerClass));
        logVerbose("create_character_begin", builder.toString(), null, null, false);
    }

    public static void onCreateCharacterEnd(
        AbstractPlayer.PlayerClass playerClass,
        AbstractPlayer createdPlayer
    ) {
        if (!MENU_DIAG_VERBOSE_ENABLED) {
            return;
        }
        StringBuilder builder = new StringBuilder(320);
        builder.append("targetClass=").append(safePlayerClassName(playerClass));
        builder.append(" loadingSave=").append(CardCrawlGame.loadingSave);
        builder.append(" startOver=").append(CardCrawlGame.startOver);
        appendCharacterRef(builder, "createdPlayer", createdPlayer);
        appendCharacterRef(builder, "dungeonPlayer", AbstractDungeon.player);
        AbstractPlayer masterPlayer = resolveMasterCharacter(playerClass);
        appendCharacterRef(builder, "masterPlayer", masterPlayer);
        builder.append(" masterMatchesCreated=").append(createdPlayer != null && createdPlayer == masterPlayer);
        logVerbose("create_character_end", builder.toString(), null, null, false);
    }

    public static void onPlayerDispose(AbstractPlayer player) {
        if (!MENU_DIAG_VERBOSE_ENABLED) {
            return;
        }
        StringBuilder builder = new StringBuilder(256);
        appendCharacterRef(builder, "targetPlayer", player);
        builder.append(" isDungeonPlayer=").append(player != null && player == AbstractDungeon.player);
        builder.append(" isChosenMaster=")
            .append(player != null && player == resolveMasterCharacter(player.chosenClass));
        logVerbose("player_dispose", builder.toString(), null, null, false);
    }

    public static void onCharacterRecreateBegin(
        CharacterManager characterManager,
        AbstractPlayer.PlayerClass playerClass
    ) {
        if (characterManager == null || playerClass == null) {
            return;
        }
        String phase = MainMenuPreviewStrategy.describeCharacterRecreatePhase(playerClass);
        boolean summaryEvent = shouldLogCharacterRecreateSummary(phase);
        if (!MENU_DIAG_VERBOSE_ENABLED && !summaryEvent) {
            return;
        }
        AbstractPlayer existing = characterManager.getCharacter(playerClass);
        long startedAtNs = System.nanoTime();
        String textureWindowLabel = buildCharacterRecreateTextureWindowLabel(
            playerClass,
            phase,
            startedAtNs
        );
        CharacterRecreateTrace trace = new CharacterRecreateTrace(
            playerClass,
            existing,
            existing != null && existing == AbstractDungeon.player,
            startedAtNs,
            phase,
            textureWindowLabel,
            summaryEvent
        );
        characterRecreateTrace.set(trace);
        characterRecreateShortcutLogged.remove();
        beginTextureDebugWindow(textureWindowLabel);
        if (!MENU_DIAG_VERBOSE_ENABLED) {
            return;
        }
        StringBuilder builder = new StringBuilder(256);
        builder.append("targetClass=").append(safePlayerClassName(playerClass));
        builder.append(" phase=").append(trace.phase);
        appendCharacterRef(builder, "existingPlayer", existing);
        appendCharacterRef(builder, "dungeonPlayer", AbstractDungeon.player);
        builder.append(" chosenCharacter=").append(safePlayerClassName(CardCrawlGame.chosenCharacter));
        logVerbose("recreate_character_begin", builder.toString(), null, null, false);
    }

    public static void onCharacterRecreateShortcut(
        CharacterManager characterManager,
        AbstractPlayer.PlayerClass playerClass,
        AbstractPlayer reusedPlayer,
        String reason
    ) {
        CharacterRecreateTrace trace = characterRecreateTrace.get();
        if (trace == null && MENU_DIAG_VERBOSE_ENABLED) {
            trace = new CharacterRecreateTrace(
                playerClass,
                reusedPlayer,
                reusedPlayer != null && reusedPlayer == AbstractDungeon.player,
                System.nanoTime(),
                MainMenuPreviewStrategy.describeCharacterRecreatePhase(playerClass),
                buildCharacterRecreateTextureWindowLabel(
                    playerClass,
                    MainMenuPreviewStrategy.describeCharacterRecreatePhase(playerClass),
                    System.nanoTime()
                ),
                shouldLogCharacterRecreateSummary(
                    MainMenuPreviewStrategy.describeCharacterRecreatePhase(playerClass)
                )
            );
        }
        if (!MENU_DIAG_VERBOSE_ENABLED && trace == null) {
            characterRecreateTrace.remove();
            characterRecreateShortcutLogged.remove();
            return;
        }
        characterRecreateShortcutLogged.set(Boolean.TRUE);
        logCharacterRecreateEnd(characterManager, trace, reusedPlayer, true, reason);
    }

    public static void onCharacterRecreateEnd(
        CharacterManager characterManager,
        AbstractPlayer.PlayerClass playerClass,
        AbstractPlayer result
    ) {
        if (Boolean.TRUE.equals(characterRecreateShortcutLogged.get())) {
            characterRecreateShortcutLogged.remove();
            characterRecreateTrace.remove();
            return;
        }
        characterRecreateShortcutLogged.remove();
        CharacterRecreateTrace trace = characterRecreateTrace.get();
        if (trace == null) {
            String phase = MainMenuPreviewStrategy.describeCharacterRecreatePhase(playerClass);
            boolean summaryEvent = shouldLogCharacterRecreateSummary(phase);
            if (!MENU_DIAG_VERBOSE_ENABLED && !summaryEvent) {
                characterRecreateTrace.remove();
                return;
            }
            long startedAtNs = System.nanoTime();
            trace = new CharacterRecreateTrace(
                playerClass,
                characterManager == null ? null : characterManager.getCharacter(playerClass),
                false,
                startedAtNs,
                phase,
                buildCharacterRecreateTextureWindowLabel(playerClass, phase, startedAtNs),
                summaryEvent
            );
            beginTextureDebugWindow(trace.textureWindowLabel);
        }
        logCharacterRecreateEnd(characterManager, trace, result, false, "new_instance");
    }

    public static AbstractPlayer tryReuseCharacterPreview(
        CharacterManager characterManager,
        AbstractPlayer.PlayerClass playerClass
    ) {
        return MainMenuPreviewStrategy.tryReuseCharacterPreview(characterManager, playerClass);
    }

    public static boolean getBooleanArgument(Object[] args, int index, boolean defaultValue) {
        if (args == null || index < 0 || index >= args.length) {
            return defaultValue;
        }
        Object value = args[index];
        return value instanceof Boolean ? ((Boolean) value).booleanValue() : defaultValue;
    }

    private static long consumeMainMenuConstructorDurationMs() {
        long startedAt = mainMenuConstructorStartNs;
        mainMenuConstructorStartNs = -1L;
        return toDurationMs(startedAt);
    }

    private static long consumeCharacterSelectInitializeDurationMs() {
        long startedAt = characterSelectInitializeStartNs;
        characterSelectInitializeStartNs = -1L;
        return toDurationMs(startedAt);
    }

    private static void appendPreviewSummary(
        StringBuilder builder,
        MainMenuPreviewStrategy.Snapshot snapshot
    ) {
        builder.append(" preview={enabled=").append(snapshot.enabled);
        builder.append(",reuse=").append(snapshot.previewReuseCount);
        builder.append(",reuseVanilla=").append(snapshot.previewReuseVanillaCount);
        builder.append(",reuseModded=").append(snapshot.previewReuseModdedCount);
        builder.append(",reuseByClass=").append(orNone(snapshot.previewReuseByClassSummary));
        builder.append(",forced=").append(snapshot.previewForcedRecreateCount);
        builder.append(",forcedVanilla=").append(snapshot.previewForcedRecreateVanillaCount);
        builder.append(",forcedModded=").append(snapshot.previewForcedRecreateModdedCount);
        builder.append(",forcedByClass=").append(orNone(snapshot.previewForcedRecreateByClassSummary));
        builder.append(",moddedCharacters=").append(snapshot.moddedCharacterOptionCount);
        builder.append(",textureCacheHits=").append(snapshot.moddedCharacterTextureCacheHits);
        builder.append(",textureCacheMisses=").append(snapshot.moddedCharacterTextureCacheMisses);
        builder.append(",textureCacheSize=").append(snapshot.cachedCharacterOptionTextureCount);
        builder.append("}");
    }

    private static void beginTextureDebugWindow(String label) {
        ensureGpuSummaryMethods();
        if (glTextureBeginDebugWindowMethod == null || label == null || label.length() == 0) {
            return;
        }
        invokeVoid(glTextureBeginDebugWindowMethod, label);
    }

    private static void appendTextureDebugWindowSummary(StringBuilder builder, String label) {
        String summary = finishTextureDebugWindowSummary(label);
        if (summary == null) {
            return;
        }
        builder.append(" textureWindow={").append(summary).append("}");
    }

    private static String finishTextureDebugWindowSummary(String label) {
        ensureGpuSummaryMethods();
        if (glTextureFinishDebugWindowMethod == null || label == null || label.length() == 0) {
            return null;
        }
        String summary = invokeSummary(glTextureFinishDebugWindowMethod, "unavailable", label);
        if (summary == null || summary.length() == 0 || "missing".equals(summary) || "unavailable".equals(summary)) {
            return null;
        }
        return summary;
    }

    private static String orNone(String value) {
        return value == null || value.length() == 0 ? "none" : value;
    }

    private static void logCharacterRecreateEnd(
        CharacterManager characterManager,
        CharacterRecreateTrace trace,
        AbstractPlayer result,
        boolean reusedShortcut,
        String reason
    ) {
        try {
            if (trace == null) {
                return;
            }
            AbstractPlayer masterPlayer =
                characterManager == null || trace.playerClass == null
                    ? null
                    : characterManager.getCharacter(trace.playerClass);
            StringBuilder builder = new StringBuilder(MENU_DIAG_VERBOSE_ENABLED ? 384 : 256);
            builder.append("targetClass=").append(safePlayerClassName(trace.playerClass));
            builder.append(" phase=").append(trace.phase);
            builder.append(" durationMs=").append(toDurationMs(trace.startedAtNs));
            builder.append(" reusedShortcut=").append(reusedShortcut);
            builder.append(" reason=").append(reason);
            builder.append(" resultMatchesExisting=").append(result != null && result == trace.existingPlayer);
            builder.append(" resultMatchesMaster=").append(result != null && result == masterPlayer);
            appendTextureDebugWindowSummary(builder, trace.textureWindowLabel);
            if (!MENU_DIAG_VERBOSE_ENABLED) {
                if (!trace.summaryEvent) {
                    return;
                }
                logSummaryOrVerbose("recreate_character_summary", builder.toString(), null, null, false);
                return;
            }
            builder.append(" existingWasDungeonPlayer=").append(trace.existingWasDungeonPlayer);
            appendCharacterRef(builder, "existingPlayer", trace.existingPlayer);
            appendCharacterRef(builder, "resultPlayer", result);
            appendCharacterRef(builder, "masterPlayer", masterPlayer);
            appendCharacterRef(builder, "dungeonPlayer", AbstractDungeon.player);
            logVerbose("recreate_character_end", builder.toString(), null, null, false);
        } finally {
            if (trace != null && trace.textureWindowLabel != null) {
                finishTextureDebugWindowSummary(trace.textureWindowLabel);
            }
            characterRecreateTrace.remove();
        }
    }

    private static boolean shouldLogCharacterRecreateSummary(String phase) {
        return MENU_DIAG_ENABLED && "main_menu_modded_selected".equals(phase);
    }

    private static String buildCharacterRecreateTextureWindowLabel(
        AbstractPlayer.PlayerClass playerClass,
        String phase,
        long startedAtNs
    ) {
        StringBuilder builder = new StringBuilder(96);
        builder.append(CHARACTER_RECREATE_TEXTURE_WINDOW_PREFIX);
        builder.append(":").append(safePlayerClassName(playerClass));
        builder.append(":").append(phase == null ? "unknown" : phase);
        builder.append(":").append(startedAtNs);
        return builder.toString();
    }

    private static void logSummaryOrVerbose(
        String event,
        String detail,
        MainMenuScreen explicitMainMenuScreen,
        CharacterSelectScreen explicitCharSelectScreen,
        boolean includeTextureHotspots
    ) {
        if (MENU_DIAG_ENABLED) {
            logEvent(event, detail, explicitMainMenuScreen, explicitCharSelectScreen, includeTextureHotspots, false);
            return;
        }
        if (MENU_DIAG_VERBOSE_ENABLED) {
            logEvent(event, detail, explicitMainMenuScreen, explicitCharSelectScreen, includeTextureHotspots, true);
        }
    }

    private static void logVerbose(
        String event,
        String detail,
        MainMenuScreen explicitMainMenuScreen,
        CharacterSelectScreen explicitCharSelectScreen,
        boolean includeTextureHotspots
    ) {
        if (!MENU_DIAG_VERBOSE_ENABLED) {
            return;
        }
        logEvent(event, detail, explicitMainMenuScreen, explicitCharSelectScreen, includeTextureHotspots, true);
    }

    private static void logEvent(
        String event,
        String detail,
        MainMenuScreen explicitMainMenuScreen,
        CharacterSelectScreen explicitCharSelectScreen,
        boolean includeTextureHotspots,
        boolean verbose
    ) {
        MainMenuScreen mainMenuScreen =
            explicitMainMenuScreen != null ? explicitMainMenuScreen : CardCrawlGame.mainMenuScreen;
        CharacterSelectScreen charSelectScreen =
            explicitCharSelectScreen != null ? explicitCharSelectScreen : resolveCharSelect(mainMenuScreen);
        Runtime runtime = Runtime.getRuntime();
        long usedBytes = runtime.totalMemory() - runtime.freeMemory();
        StringBuilder builder = new StringBuilder(verbose ? 896 : 640);
        builder.append("[amethyst-runtime-diag] event=").append(event);
        if (detail != null && detail.length() > 0) {
            builder.append(" ").append(detail);
        }
        builder.append(" frame=").append(getCurrentFrameId());
        builder.append(" state={mode=").append(safeName(CardCrawlGame.mode));
        builder.append(",menu=").append(safeName(mainMenuScreen == null ? null : mainMenuScreen.screen));
        builder.append(",mainMenuId=").append(identityHash(mainMenuScreen));
        builder.append(",charSelectId=").append(identityHash(charSelectScreen));
        if (verbose) {
            builder.append(",menuButtons=").append(sizeOf(mainMenuScreen == null ? null : mainMenuScreen.buttons));
            builder.append(",charOptions=").append(sizeOf(charSelectScreen == null ? null : charSelectScreen.options));
            builder.append(",ascensionMode=").append(charSelectScreen != null && charSelectScreen.isAscensionMode);
            builder.append(",ascensionLevel=").append(charSelectScreen == null ? -1 : charSelectScreen.ascensionLevel);
            builder.append(",startOver=").append(CardCrawlGame.startOver);
            builder.append(",hasDungeon=").append(CardCrawlGame.dungeon != null);
        }
        builder.append("}");
        builder.append(" heap={usedMb=").append(toMegabytes(usedBytes));
        builder.append(",committedMb=").append(toMegabytes(runtime.totalMemory()));
        builder.append(",maxMb=").append(toMegabytes(runtime.maxMemory())).append("}");
        builder.append(" gpu={").append(getGpuResourceSummary()).append("}");
        if (includeTextureHotspots && MENU_DIAG_HOTSPOTS_ENABLED) {
            builder.append(" gpuTopSources={").append(getTextureLiveSourceSummary()).append("}");
            builder.append(" gpuTopOwners={").append(getTextureLiveOwnerSummary()).append("}");
            builder.append(" gpuTopUploads={").append(getTextureUploadSourceSummary()).append("}");
            builder.append(" gpuTopUploadOwners={").append(getTextureUploadOwnerSummary()).append("}");
            builder.append(" gpuTopReleases={").append(getTextureReleaseSourceSummary()).append("}");
            builder.append(" gpuTopReleaseOwners={").append(getTextureReleaseOwnerSummary()).append("}");
            builder.append(" gpuTopFboOwners={").append(getFrameBufferLiveOwnerSummary()).append("}");
        }
        System.out.println(builder.toString());
    }

    private static CharacterSelectScreen resolveCharSelect(MainMenuScreen mainMenuScreen) {
        return mainMenuScreen == null ? null : mainMenuScreen.charSelectScreen;
    }

    private static int identityHash(Object value) {
        return value == null ? 0 : System.identityHashCode(value);
    }

    private static int sizeOf(java.util.List<?> values) {
        return values == null ? -1 : values.size();
    }

    private static long getCurrentFrameId() {
        try {
            if (Gdx.graphics == null) {
                return -1L;
            }
            return Gdx.graphics.getFrameId();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static long toMegabytes(long bytes) {
        if (bytes <= 0L) {
            return 0L;
        }
        return bytes / (1024L * 1024L);
    }

    private static String safeName(Enum<?> value) {
        return value == null ? "null" : value.name();
    }

    private static String safePlayerClassName(AbstractPlayer.PlayerClass value) {
        return value == null ? "null" : value.name();
    }

    private static String safeClassName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static AbstractPlayer resolveMasterCharacter(AbstractPlayer.PlayerClass playerClass) {
        CharacterManager characterManager = CardCrawlGame.characterManager;
        if (characterManager == null || playerClass == null) {
            return null;
        }
        return characterManager.getCharacter(playerClass);
    }

    private static void appendCharacterRef(StringBuilder builder, String prefix, AbstractPlayer player) {
        builder.append(" ").append(prefix).append("Id=").append(identityHash(player));
        builder.append(" ").append(prefix).append("Type=").append(safeClassName(player));
        builder.append(" ").append(prefix).append("Class=")
            .append(player == null ? "null" : safePlayerClassName(player.chosenClass));
        if (player == null) {
            return;
        }
        builder.append(" ").append(prefix).append("Relics=").append(sizeOf(player.relics));
        builder.append(" ").append(prefix).append("Orbs=").append(sizeOf(player.orbs));
        builder.append(" ").append(prefix).append("ImgHandle=").append(textureHandle(player.img));
        builder.append(" ").append(prefix).append("ShoulderHandle=").append(textureHandle(player.shoulderImg));
        builder.append(" ").append(prefix).append("Shoulder2Handle=").append(textureHandle(player.shoulder2Img));
        builder.append(" ").append(prefix).append("CorpseHandle=").append(textureHandle(player.corpseImg));
    }

    private static int textureHandle(Texture texture) {
        if (texture == null) {
            return 0;
        }
        try {
            return texture.getTextureObjectHandle();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String getGpuResourceSummary() {
        ensureGpuSummaryMethods();
        String textureSummary = invokeSummary(glTextureSummaryMethod, "texturesSummary=unavailable");
        String frameBufferSummary = invokeSummary(glFrameBufferSummaryMethod, "frameBuffersSummary=unavailable");
        return textureSummary + " " + frameBufferSummary;
    }

    private static String getTextureLiveSourceSummary() {
        ensureGpuSummaryMethods();
        return stripSummaryLabel(
            invokeSummary(glTextureLiveSourceSummaryMethod, "textureLiveTop=unavailable"),
            "textureLiveTop="
        );
    }

    private static String getTextureLiveOwnerSummary() {
        ensureGpuSummaryMethods();
        return stripSummaryLabel(
            invokeSummary(glTextureLiveOwnerSummaryMethod, "textureOwnerTop=unavailable"),
            "textureOwnerTop="
        );
    }

    private static String getTextureUploadSourceSummary() {
        ensureGpuSummaryMethods();
        return stripSummaryLabel(
            invokeSummary(glTextureUploadSourceSummaryMethod, "textureUploadTop=unavailable"),
            "textureUploadTop="
        );
    }

    private static String getTextureUploadOwnerSummary() {
        ensureGpuSummaryMethods();
        return stripSummaryLabel(
            invokeSummary(glTextureUploadOwnerSummaryMethod, "textureUploadOwnerTop=unavailable"),
            "textureUploadOwnerTop="
        );
    }

    private static String getTextureReleaseSourceSummary() {
        ensureGpuSummaryMethods();
        return stripSummaryLabel(
            invokeSummary(glTextureReleaseSourceSummaryMethod, "textureReleaseTop=unavailable"),
            "textureReleaseTop="
        );
    }

    private static String getTextureReleaseOwnerSummary() {
        ensureGpuSummaryMethods();
        return stripSummaryLabel(
            invokeSummary(glTextureReleaseOwnerSummaryMethod, "textureReleaseOwnerTop=unavailable"),
            "textureReleaseOwnerTop="
        );
    }

    private static String getFrameBufferLiveOwnerSummary() {
        ensureGpuSummaryMethods();
        return stripSummaryLabel(
            invokeSummary(glFrameBufferLiveOwnerSummaryMethod, "frameBufferOwnerTop=unavailable"),
            "frameBufferOwnerTop="
        );
    }

    private static String stripSummaryLabel(String summary, String prefix) {
        if (summary == null) {
            return "unavailable";
        }
        return summary.startsWith(prefix) ? summary.substring(prefix.length()) : summary;
    }

    private static void ensureGpuSummaryMethods() {
        synchronized (RuntimeMemoryDiagnostics.class) {
            if (gpuSummaryMethodsResolved) {
                return;
            }
            gpuSummaryMethodsResolved = true;
            try {
                Class<?> textureClass = Class.forName("com.badlogic.gdx.graphics.GLTexture");
                glTextureSummaryMethod = textureClass.getMethod("getDebugStatusSummary");
                glTextureLiveSourceSummaryMethod = textureClass.getMethod("getLiveSourceSummary");
                glTextureLiveOwnerSummaryMethod = textureClass.getMethod("getLiveOwnerSummary");
                glTextureUploadSourceSummaryMethod = textureClass.getMethod("getUploadSourceSummary");
                glTextureUploadOwnerSummaryMethod = textureClass.getMethod("getUploadOwnerSummary");
                glTextureReleaseSourceSummaryMethod = textureClass.getMethod("getReleaseSourceSummary");
                glTextureReleaseOwnerSummaryMethod = textureClass.getMethod("getReleaseOwnerSummary");
                glTextureBeginDebugWindowMethod = textureClass.getMethod("beginDebugWindow", String.class);
                glTextureFinishDebugWindowMethod = textureClass.getMethod("finishDebugWindow", String.class);
            } catch (Throwable ignored) {
                glTextureSummaryMethod = null;
                glTextureLiveSourceSummaryMethod = null;
                glTextureLiveOwnerSummaryMethod = null;
                glTextureUploadSourceSummaryMethod = null;
                glTextureUploadOwnerSummaryMethod = null;
                glTextureReleaseSourceSummaryMethod = null;
                glTextureReleaseOwnerSummaryMethod = null;
                glTextureBeginDebugWindowMethod = null;
                glTextureFinishDebugWindowMethod = null;
            }
            try {
                Class<?> frameBufferClass = Class.forName("com.badlogic.gdx.graphics.glutils.GLFrameBuffer");
                glFrameBufferSummaryMethod = frameBufferClass.getMethod("getDebugStatusSummary");
                glFrameBufferLiveOwnerSummaryMethod = frameBufferClass.getMethod("getLiveOwnerSummary");
            } catch (Throwable ignored) {
                glFrameBufferSummaryMethod = null;
                glFrameBufferLiveOwnerSummaryMethod = null;
            }
            if ((glTextureSummaryMethod == null
                    || glTextureLiveSourceSummaryMethod == null
                    || glTextureLiveOwnerSummaryMethod == null
                    || glTextureUploadSourceSummaryMethod == null
                    || glTextureUploadOwnerSummaryMethod == null
                    || glTextureReleaseSourceSummaryMethod == null
                    || glTextureReleaseOwnerSummaryMethod == null
                    || glTextureBeginDebugWindowMethod == null
                    || glTextureFinishDebugWindowMethod == null
                    || glFrameBufferSummaryMethod == null
                    || glFrameBufferLiveOwnerSummaryMethod == null)
                && !gpuSummaryLookupFailureLogged) {
                gpuSummaryLookupFailureLogged = true;
                System.out.println(
                    "[amethyst-runtime-diag] gpu_summary_lookup_unavailable"
                        + " textureMethod=" + (glTextureSummaryMethod != null)
                        + " textureLiveSourceMethod=" + (glTextureLiveSourceSummaryMethod != null)
                        + " textureLiveOwnerMethod=" + (glTextureLiveOwnerSummaryMethod != null)
                        + " textureUploadSourceMethod=" + (glTextureUploadSourceSummaryMethod != null)
                        + " textureUploadOwnerMethod=" + (glTextureUploadOwnerSummaryMethod != null)
                        + " textureReleaseSourceMethod=" + (glTextureReleaseSourceSummaryMethod != null)
                        + " textureReleaseOwnerMethod=" + (glTextureReleaseOwnerSummaryMethod != null)
                        + " textureBeginWindowMethod=" + (glTextureBeginDebugWindowMethod != null)
                        + " textureFinishWindowMethod=" + (glTextureFinishDebugWindowMethod != null)
                        + " frameBufferMethod=" + (glFrameBufferSummaryMethod != null)
                        + " frameBufferLiveOwnerMethod=" + (glFrameBufferLiveOwnerSummaryMethod != null)
                );
            }
        }
    }

    private static String invokeSummary(Method method, String fallback) {
        return invokeSummary(method, fallback, new Object[0]);
    }

    private static String invokeSummary(Method method, String fallback, Object... args) {
        if (method == null) {
            return fallback;
        }
        try {
            Object result = method.invoke(null, args);
            return result instanceof String ? (String) result : fallback;
        } catch (Throwable error) {
            synchronized (RuntimeMemoryDiagnostics.class) {
                if (!gpuSummaryInvokeFailureLogged) {
                    gpuSummaryInvokeFailureLogged = true;
                    System.out.println(
                        "[amethyst-runtime-diag] gpu_summary_invoke_failed reason="
                            + error.getClass().getSimpleName()
                    );
                }
            }
            return fallback;
        }
    }

    private static void invokeVoid(Method method, Object... args) {
        if (method == null) {
            return;
        }
        try {
            method.invoke(null, args);
        } catch (Throwable error) {
            synchronized (RuntimeMemoryDiagnostics.class) {
                if (!gpuSummaryInvokeFailureLogged) {
                    gpuSummaryInvokeFailureLogged = true;
                    System.out.println(
                        "[amethyst-runtime-diag] gpu_summary_invoke_failed reason="
                            + error.getClass().getSimpleName()
                    );
                }
            }
        }
    }

    private static boolean shouldTrackDurations() {
        return MENU_DIAG_ENABLED || MENU_DIAG_VERBOSE_ENABLED;
    }

    private static long toDurationMs(long startedAtNs) {
        if (startedAtNs <= 0L) {
            return -1L;
        }
        long elapsedNs = System.nanoTime() - startedAtNs;
        if (elapsedNs <= 0L) {
            return 0L;
        }
        return elapsedNs / 1000000L;
    }

    private static boolean readBooleanSystemProperty(String key, boolean defaultValue) {
        String configured = System.getProperty(key);
        if (configured == null) {
            return defaultValue;
        }
        configured = configured.trim();
        if (configured.length() == 0) {
            return defaultValue;
        }
        if ("false".equalsIgnoreCase(configured)
            || "0".equals(configured)
            || "off".equalsIgnoreCase(configured)) {
            return false;
        }
        if ("true".equalsIgnoreCase(configured)
            || "1".equals(configured)
            || "on".equalsIgnoreCase(configured)) {
            return true;
        }
        return defaultValue;
    }

    private static final class CharacterRecreateTrace {
        private final AbstractPlayer.PlayerClass playerClass;
        private final AbstractPlayer existingPlayer;
        private final boolean existingWasDungeonPlayer;
        private final long startedAtNs;
        private final String phase;
        private final String textureWindowLabel;
        private final boolean summaryEvent;

        private CharacterRecreateTrace(
            AbstractPlayer.PlayerClass playerClass,
            AbstractPlayer existingPlayer,
            boolean existingWasDungeonPlayer,
            long startedAtNs,
            String phase,
            String textureWindowLabel,
            boolean summaryEvent
        ) {
            this.playerClass = playerClass;
            this.existingPlayer = existingPlayer;
            this.existingWasDungeonPlayer = existingWasDungeonPlayer;
            this.startedAtNs = startedAtNs;
            this.phase = phase;
            this.textureWindowLabel = textureWindowLabel;
            this.summaryEvent = summaryEvent;
        }
    }
}
