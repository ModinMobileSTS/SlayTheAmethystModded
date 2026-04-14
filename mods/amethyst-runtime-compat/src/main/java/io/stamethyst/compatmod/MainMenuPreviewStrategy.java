package io.stamethyst.compatmod;

import basemod.BaseMod;
import com.badlogic.gdx.graphics.Texture;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.characters.CharacterManager;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.screens.charSelect.CharacterOption;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MainMenuPreviewStrategy {
    private static final String PREVIEW_REUSE_PROP = "amethyst.runtime_compat.main_menu_preview_reuse";
    private static final boolean PREVIEW_REUSE_ENABLED =
        readBooleanSystemProperty(PREVIEW_REUSE_PROP, true);
    private static final int CLASS_SUMMARY_LIMIT = 8;
    private static final Object LOCK = new Object();
    private static boolean mainMenuConstructionActive;
    private static boolean characterSelectInitializeActive;
    private static boolean moddedCharacterOptionsBuildActive;
    private static int previewReuseCount;
    private static int previewReuseVanillaCount;
    private static int previewReuseModdedCount;
    private static int previewForcedRecreateCount;
    private static int previewForcedRecreateVanillaCount;
    private static int previewForcedRecreateModdedCount;
    private static int moddedCharacterOptionCount;
    private static int moddedCharacterTextureCacheHits;
    private static int moddedCharacterTextureCacheMisses;
    private static final LinkedHashMap<String, Integer> previewReuseByClass =
        new LinkedHashMap<String, Integer>();
    private static final LinkedHashMap<String, Integer> previewForcedRecreateByClass =
        new LinkedHashMap<String, Integer>();
    private static final HashMap<String, Texture> cachedCharacterOptionTextures =
        new HashMap<String, Texture>();

    private MainMenuPreviewStrategy() {
    }

    static boolean isEnabled() {
        return PREVIEW_REUSE_ENABLED;
    }

    static void onMainMenuConstructorBegin() {
        synchronized (LOCK) {
            mainMenuConstructionActive = true;
            characterSelectInitializeActive = false;
            moddedCharacterOptionsBuildActive = false;
            resetCycleCountersLocked();
        }
    }

    static Snapshot finishMainMenuConstruction() {
        synchronized (LOCK) {
            Snapshot snapshot = snapshotLocked();
            mainMenuConstructionActive = false;
            characterSelectInitializeActive = false;
            moddedCharacterOptionsBuildActive = false;
            resetCycleCountersLocked();
            return snapshot;
        }
    }

    static void onCharacterSelectInitializeBegin() {
        synchronized (LOCK) {
            characterSelectInitializeActive = true;
        }
    }

    static void onCharacterSelectInitializeEnd() {
        synchronized (LOCK) {
            characterSelectInitializeActive = false;
        }
    }

    static ArrayList<CharacterOption> buildModdedCharacterOptions() {
        List<AbstractPlayer> moddedCharacters = BaseMod.getModdedCharacters();
        int count = moddedCharacters == null ? 0 : moddedCharacters.size();
        synchronized (LOCK) {
            moddedCharacterOptionsBuildActive = true;
            moddedCharacterOptionCount = count;
        }

        ArrayList<CharacterOption> options = new ArrayList<CharacterOption>(count);
        try {
            if (moddedCharacters == null) {
                return options;
            }
            for (AbstractPlayer moddedCharacter : moddedCharacters) {
                if (moddedCharacter == null) {
                    continue;
                }
                AbstractPlayer.PlayerClass playerClass = moddedCharacter.chosenClass;
                AbstractPlayer preview = CardCrawlGame.characterManager.recreateCharacter(playerClass);
                if (preview == null) {
                    continue;
                }
                Texture button = loadCachedCharacterOptionTexture(BaseMod.playerSelectButtonMap.get(playerClass));
                Texture portrait = loadCachedCharacterOptionTexture(BaseMod.playerPortraitMap.get(playerClass));
                options.add(
                    new CharacterOption(
                        moddedCharacter.getLocalizedCharacterName(),
                        preview,
                        button,
                        portrait
                    )
                );
            }
            return options;
        } finally {
            synchronized (LOCK) {
                moddedCharacterOptionsBuildActive = false;
            }
        }
    }

    static AbstractPlayer tryReuseCharacterPreview(
        CharacterManager characterManager,
        AbstractPlayer.PlayerClass playerClass
    ) {
        if (!PREVIEW_REUSE_ENABLED || characterManager == null || playerClass == null) {
            return null;
        }
        synchronized (LOCK) {
            if (!mainMenuConstructionActive) {
                return null;
            }
            if (CardCrawlGame.chosenCharacter == playerClass) {
                previewForcedRecreateCount++;
                if (moddedCharacterOptionsBuildActive) {
                    previewForcedRecreateModdedCount++;
                } else if (characterSelectInitializeActive) {
                    previewForcedRecreateVanillaCount++;
                }
                incrementClassCounter(previewForcedRecreateByClass, safePlayerClassName(playerClass));
                return null;
            }
            AbstractPlayer existing = characterManager.getCharacter(playerClass);
            if (existing == null) {
                return null;
            }
            previewReuseCount++;
            if (moddedCharacterOptionsBuildActive) {
                previewReuseModdedCount++;
            } else if (characterSelectInitializeActive) {
                previewReuseVanillaCount++;
            }
            incrementClassCounter(previewReuseByClass, safePlayerClassName(playerClass));
            return existing;
        }
    }

    static Snapshot snapshot() {
        synchronized (LOCK) {
            return snapshotLocked();
        }
    }

    static String describeCharacterRecreatePhase(AbstractPlayer.PlayerClass playerClass) {
        synchronized (LOCK) {
            if (mainMenuConstructionActive) {
                if (moddedCharacterOptionsBuildActive) {
                    return CardCrawlGame.chosenCharacter == playerClass
                        ? "main_menu_modded_selected"
                        : "main_menu_modded_preview";
                }
                if (characterSelectInitializeActive) {
                    return "main_menu_vanilla_preview";
                }
                return "main_menu";
            }
        }
        if (CardCrawlGame.mode == CardCrawlGame.GameMode.CHAR_SELECT) {
            if (CardCrawlGame.startOver || CardCrawlGame.loadingSave) {
                return "game_start";
            }
            return "char_select_runtime";
        }
        if (CardCrawlGame.mode == CardCrawlGame.GameMode.GAMEPLAY) {
            return "gameplay_runtime";
        }
        return safeName(CardCrawlGame.mode);
    }

    private static Snapshot snapshotLocked() {
        return new Snapshot(
            PREVIEW_REUSE_ENABLED,
            previewReuseCount,
            previewReuseVanillaCount,
            previewReuseModdedCount,
            summarizeCounter(previewReuseByClass),
            previewForcedRecreateCount,
            previewForcedRecreateVanillaCount,
            previewForcedRecreateModdedCount,
            summarizeCounter(previewForcedRecreateByClass),
            moddedCharacterOptionCount,
            moddedCharacterTextureCacheHits,
            moddedCharacterTextureCacheMisses,
            cachedCharacterOptionTextures.size()
        );
    }

    private static void resetCycleCountersLocked() {
        previewReuseCount = 0;
        previewReuseVanillaCount = 0;
        previewReuseModdedCount = 0;
        previewForcedRecreateCount = 0;
        previewForcedRecreateVanillaCount = 0;
        previewForcedRecreateModdedCount = 0;
        moddedCharacterOptionCount = 0;
        moddedCharacterTextureCacheHits = 0;
        moddedCharacterTextureCacheMisses = 0;
        previewReuseByClass.clear();
        previewForcedRecreateByClass.clear();
    }

    private static void incrementClassCounter(LinkedHashMap<String, Integer> counter, String key) {
        Integer previous = counter.get(key);
        counter.put(key, previous == null ? 1 : previous.intValue() + 1);
    }

    private static String summarizeCounter(LinkedHashMap<String, Integer> counter) {
        if (counter.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(96);
        int index = 0;
        for (Map.Entry<String, Integer> entry : counter.entrySet()) {
            if (index > 0) {
                builder.append("|");
            }
            builder.append(entry.getKey()).append(":").append(entry.getValue());
            index++;
            if (index >= CLASS_SUMMARY_LIMIT) {
                if (counter.size() > index) {
                    builder.append("|...");
                }
                break;
            }
        }
        return builder.toString();
    }

    private static Texture loadCachedCharacterOptionTexture(String path) {
        if (path == null || path.length() == 0) {
            return null;
        }
        synchronized (LOCK) {
            Texture cached = cachedCharacterOptionTextures.get(path);
            if (cached != null) {
                if (moddedCharacterOptionsBuildActive) {
                    moddedCharacterTextureCacheHits++;
                }
                return cached;
            }
        }
        Texture loaded = ImageMaster.loadImage(path);
        synchronized (LOCK) {
            Texture cached = cachedCharacterOptionTextures.get(path);
            if (cached != null) {
                if (moddedCharacterOptionsBuildActive) {
                    moddedCharacterTextureCacheHits++;
                }
                return cached;
            }
            if (moddedCharacterOptionsBuildActive) {
                moddedCharacterTextureCacheMisses++;
            }
            if (loaded != null) {
                cachedCharacterOptionTextures.put(path, loaded);
            }
            return loaded;
        }
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

    private static String safeName(Enum<?> value) {
        return value == null ? "null" : value.name();
    }

    private static String safePlayerClassName(AbstractPlayer.PlayerClass value) {
        return value == null ? "null" : value.name();
    }

    static final class Snapshot {
        final boolean enabled;
        final int previewReuseCount;
        final int previewReuseVanillaCount;
        final int previewReuseModdedCount;
        final String previewReuseByClassSummary;
        final int previewForcedRecreateCount;
        final int previewForcedRecreateVanillaCount;
        final int previewForcedRecreateModdedCount;
        final String previewForcedRecreateByClassSummary;
        final int moddedCharacterOptionCount;
        final int moddedCharacterTextureCacheHits;
        final int moddedCharacterTextureCacheMisses;
        final int cachedCharacterOptionTextureCount;

        private Snapshot(
            boolean enabled,
            int previewReuseCount,
            int previewReuseVanillaCount,
            int previewReuseModdedCount,
            String previewReuseByClassSummary,
            int previewForcedRecreateCount,
            int previewForcedRecreateVanillaCount,
            int previewForcedRecreateModdedCount,
            String previewForcedRecreateByClassSummary,
            int moddedCharacterOptionCount,
            int moddedCharacterTextureCacheHits,
            int moddedCharacterTextureCacheMisses,
            int cachedCharacterOptionTextureCount
        ) {
            this.enabled = enabled;
            this.previewReuseCount = previewReuseCount;
            this.previewReuseVanillaCount = previewReuseVanillaCount;
            this.previewReuseModdedCount = previewReuseModdedCount;
            this.previewReuseByClassSummary = previewReuseByClassSummary;
            this.previewForcedRecreateCount = previewForcedRecreateCount;
            this.previewForcedRecreateVanillaCount = previewForcedRecreateVanillaCount;
            this.previewForcedRecreateModdedCount = previewForcedRecreateModdedCount;
            this.previewForcedRecreateByClassSummary = previewForcedRecreateByClassSummary;
            this.moddedCharacterOptionCount = moddedCharacterOptionCount;
            this.moddedCharacterTextureCacheHits = moddedCharacterTextureCacheHits;
            this.moddedCharacterTextureCacheMisses = moddedCharacterTextureCacheMisses;
            this.cachedCharacterOptionTextureCount = cachedCharacterOptionTextureCount;
        }
    }
}
