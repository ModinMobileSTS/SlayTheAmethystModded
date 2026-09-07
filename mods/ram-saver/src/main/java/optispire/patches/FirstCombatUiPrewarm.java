package optispire.patches;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import optispire.RamSaverDiag;

import java.lang.reflect.Field;

public final class FirstCombatUiPrewarm {
    private static final int SPLASH_UPDATE_DELAY = 80;
    private static final String[] FIRST_ROOM_MONSTER_CLASSES = {
            "com.megacrit.cardcrawl.monsters.exordium.Cultist",
            "com.megacrit.cardcrawl.monsters.exordium.JawWorm",
            "com.megacrit.cardcrawl.monsters.exordium.LouseNormal",
            "com.megacrit.cardcrawl.monsters.exordium.LouseDefensive",
            "com.megacrit.cardcrawl.monsters.exordium.AcidSlime_S",
            "com.megacrit.cardcrawl.monsters.exordium.AcidSlime_M",
            "com.megacrit.cardcrawl.monsters.exordium.SpikeSlime_S",
            "com.megacrit.cardcrawl.monsters.exordium.SpikeSlime_M",
    };
    private static final String[] FIRST_ROOM_MONSTER_TEXTURES = {
            "images/monsters/theBottom/cultist/skeleton.png",
            "images/monsters/theBottom/jawWorm/skeleton.png",
            "images/monsters/theBottom/louseRed/skeleton.png",
            "images/monsters/theBottom/louseGreen/skeleton.png",
            "images/monsters/theBottom/slimeS/skeleton.png",
            "images/monsters/theBottom/slimeM/skeleton.png",
            "images/monsters/theBottom/slimeAltS/skeleton.png",
            "images/monsters/theBottom/slimeAltM/skeleton.png",
    };
    private static final String[] COMBAT_STATUS_TEXTURES = {
            "images/ui/combat/body7.png",
            "images/ui/combat/left7.png",
            "images/ui/combat/right7.png",
            "images/ui/combat/leftBg.png",
            "images/ui/combat/rightBg.png",
            "images/ui/combat/bodyBg.png",
            "images/ui/combat/block.png",
            "images/ui/combat/blockL.png",
            "images/ui/combat/blockR.png",
            "images/ui/combat/blockBody3.png",
            "images/ui/combat/blockRight3.png",
            "images/ui/combat/blockLeft3.png",
    };
    private static final String[] INTENT_TEXTURES = {
            "images/ui/intent/attack/attack_intent_1.png",
            "images/ui/intent/attack/attack_intent_2.png",
            "images/ui/intent/attack/attack_intent_3.png",
            "images/ui/intent/attack/attack_intent_4.png",
            "images/ui/intent/attack/attack_intent_5.png",
            "images/ui/intent/attack/attack_intent_6.png",
            "images/ui/intent/attack/attack_intent_7.png",
            "images/ui/intent/tip/1.png",
            "images/ui/intent/tip/2.png",
            "images/ui/intent/tip/3.png",
            "images/ui/intent/tip/4.png",
            "images/ui/intent/tip/5.png",
            "images/ui/intent/tip/6.png",
            "images/ui/intent/tip/7.png",
            "images/ui/intent/attackBuff.png",
            "images/ui/intent/attackDebuff.png",
            "images/ui/intent/attackDefend.png",
            "images/ui/intent/buff1.png",
            "images/ui/intent/buff1L.png",
            "images/ui/intent/buffVFX1.png",
            "images/ui/intent/buffVFX2.png",
            "images/ui/intent/buffVFX3.png",
            "images/ui/intent/debuff1.png",
            "images/ui/intent/debuff1L.png",
            "images/ui/intent/debuff2.png",
            "images/ui/intent/debuff2L.png",
            "images/ui/intent/debuffVFX1.png",
            "images/ui/intent/debuffVFX2.png",
            "images/ui/intent/debuffVFX3.png",
            "images/ui/intent/defend.png",
            "images/ui/intent/defendL.png",
            "images/ui/intent/defendBuff.png",
            "images/ui/intent/defendBuffL.png",
            "images/ui/intent/escape.png",
            "images/ui/intent/escapeL.png",
            "images/ui/intent/magic.png",
            "images/ui/intent/magicL.png",
            "images/ui/intent/sleep.png",
            "images/ui/intent/sleepL.png",
            "images/ui/intent/special.png",
            "images/ui/intent/specialL.png",
            "images/ui/intent/stun.png",
            "images/ui/intent/stunL.png",
            "images/ui/intent/unknown.png",
            "images/ui/intent/unknownL.png",
    };
    private static final String[] PREWARM_TEXTURES = buildPrewarmTextures();
    private static final GlyphLayout GLYPH_LAYOUT = new GlyphLayout();

    private static int splashUpdateCount = 0;
    private static int nextTextureIndex = 0;
    private static int attemptedPass = -1;
    private static boolean baseModGlowInitialized = false;
    private static boolean monsterIntentSwitchInitialized = false;
    private static boolean baseModCardDescriptionCnInitialized = false;
    private static boolean combatTextGlyphsPrewarmed = false;
    private static boolean stslibHealthBarReflectionPrewarmed = false;
    private static boolean playerSpineMeshPrewarmed = false;

    private FirstCombatUiPrewarm() {
    }

    @SpirePatch2(
            clz = CardCrawlGame.class,
            method = "update"
    )
    public static class CardCrawlGameUpdatePatch {
        public static void Postfix() {
            prewarmOneResourceDuringNonCombat();
        }
    }

    private static void prewarmOneResourceDuringNonCombat() {
        if (!shouldPrewarmDuringUpdate()) {
            return;
        }

        if (!baseModGlowInitialized) {
            if (!FirstCombatPrewarmBudget.tryStep()) return;
            baseModGlowInitialized = initializeClass(
                    "basemod.helpers.CardBorderGlowManager$RenderGlowPatch",
                    "basemod-glow"
            );
            return;
        }
        if (!monsterIntentSwitchInitialized) {
            if (!FirstCombatPrewarmBudget.tryStep()) return;
            monsterIntentSwitchInitialized = initializeClass(
                    "com.megacrit.cardcrawl.monsters.AbstractMonster$1",
                    "monster-intent-switch"
            );
            return;
        }
        if (!baseModCardDescriptionCnInitialized) {
            if (!FirstCombatPrewarmBudget.tryStep()) return;
            baseModCardDescriptionCnInitialized = initializeClass(
                    "basemod.patches.com.megacrit.cardcrawl.cards.AbstractCard.RenderCustomDynamicVariableCN",
                    "basemod-card-description-cn"
            );
            return;
        }

        int pass = FirstCombatPrewarmBudget.texturePass();
        if (pass >= 0 && Gdx.files != null) {
            if (pass != attemptedPass) {
                attemptedPass = pass;
                nextTextureIndex = 0;
            }
            if (nextTextureIndex < PREWARM_TEXTURES.length) {
                if (!FirstCombatPrewarmBudget.tryStep()) return;
                int index = nextTextureIndex++;
                String path = PREWARM_TEXTURES[index];
                String atlas = index < FIRST_ROOM_MONSTER_TEXTURES.length
                        ? path.substring(0, path.lastIndexOf('/') + 1) + "skeleton.atlas" : null;
                PrewarmTextureRegistry.prewarm(path, atlas, "first_combat_ui_prewarm_step");
                return;
            }
        }
        if (!combatTextGlyphsPrewarmed && combatFontsReady()) {
            if (!FirstCombatPrewarmBudget.tryStep()) return;
            combatTextGlyphsPrewarmed = prewarmCombatTextGlyphs("background");
            return;
        }
        if (!stslibHealthBarReflectionPrewarmed) {
            if (!FirstCombatPrewarmBudget.tryStep()) return;
            stslibHealthBarReflectionPrewarmed = prewarmStsLibHealthBarReflection("background");
            return;
        }
        if (!playerSpineMeshPrewarmed && AbstractDungeon.player != null
                && CardCrawlGame.psb != null && !CardCrawlGame.psb.isDrawing()) {
            if (!FirstCombatPrewarmBudget.tryStep()) return;
            playerSpineMeshPrewarmed = prewarmPlayerSpineMesh("background");
        }
    }

    private static boolean combatFontsReady() {
        return FontHelper.healthInfoFont != null && FontHelper.blockInfoFont != null
                && FontHelper.powerAmountFont != null && FontHelper.tipHeaderFont != null;
    }

    private static boolean prewarmCombatTextGlyphs(String reason) {
        if (FontHelper.healthInfoFont == null
                || FontHelper.blockInfoFont == null
                || FontHelper.powerAmountFont == null
                || FontHelper.tipHeaderFont == null) {
            return false;
        }

        long started = RamSaverDiag.enabled() ? System.nanoTime() : 0L;
        try {
            prewarmGlyphs(FontHelper.healthInfoFont, "0123456789/???");
            prewarmGlyphs(FontHelper.blockInfoFont, "0123456789");
            prewarmGlyphs(FontHelper.powerAmountFont, "0123456789");
            prewarmGlyphs(FontHelper.tipHeaderFont, firstRoomMonsterNameText());
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logDuration(
                        "first_combat_ui_prewarm_step",
                        "combat-text-glyphs",
                        started,
                        "reason=" + reason,
                        false
                );
            }
            return true;
        }
        catch (RuntimeException ignored) {
            return true;
        }
    }

    private static String firstRoomMonsterNameText() {
        StringBuilder text = new StringBuilder(
                "Cultist Jaw Worm Acid Slime Spike Slime Red Louse Green Louse Small Slimes 2 Louse (S) (M)"
        );
        if (CardCrawlGame.languagePack == null) {
            return text.toString();
        }

        for (String className : FIRST_ROOM_MONSTER_CLASSES) {
            appendMonsterName(text, className);
        }
        return text.toString();
    }

    private static void appendMonsterName(StringBuilder text, String className) {
        try {
            Class<?> monsterClass = Class.forName(className, true, FirstCombatUiPrewarm.class.getClassLoader());
            Field nameField = monsterClass.getField("NAME");
            Object name = nameField.get(null);
            if (name instanceof String && ((String) name).length() > 0) {
                text.append(' ').append(name);
            }
        }
        catch (Throwable ignored) {
            // English fallback text still covers the bundled baseline language.
        }
    }

    private static void prewarmGlyphs(BitmapFont font, String text) {
        if (font != null && text != null && text.length() > 0) {
            GLYPH_LAYOUT.setText(font, text);
        }
    }

    private static boolean prewarmStsLibHealthBarReflection(String reason) {
        long started = RamSaverDiag.enabled() ? System.nanoTime() : 0L;
        try {
            Class.forName(
                    "com.evacipated.cardcrawl.mod.stslib.patches.powerInterfaces.HealthBarRenderPowerPatch$FixRedHealthBar",
                    true,
                    FirstCombatUiPrewarm.class.getClassLoader()
            );
            Field targetHealthBarWidth = AbstractCreature.class.getDeclaredField("targetHealthBarWidth");
            targetHealthBarWidth.setAccessible(true);
            if (AbstractDungeon.player != null) {
                targetHealthBarWidth.getFloat(AbstractDungeon.player);
            }
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logDuration(
                        "first_combat_ui_prewarm_step",
                        "stslib-healthbar-reflection",
                        started,
                        "reason=" + reason,
                        false
                );
            }
            return true;
        }
        catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean prewarmPlayerSpineMesh(String reason) {
        AbstractPlayer player = AbstractDungeon.player;
        if (player == null || CardCrawlGame.psb == null || CardCrawlGame.psb.isDrawing()) {
            return false;
        }

        long started = RamSaverDiag.enabled() ? System.nanoTime() : 0L;
        FrameBuffer frameBuffer = null;
        SpriteBatch spriteBatch = null;
        boolean frameBufferBound = false;
        try {
            frameBuffer = new FrameBuffer(Pixmap.Format.RGBA8888, 1, 1, false);
            spriteBatch = new SpriteBatch(1);
            frameBuffer.begin();
            frameBufferBound = true;
            spriteBatch.begin();
            player.renderPlayerImage(spriteBatch);
            if (spriteBatch.isDrawing()) {
                spriteBatch.end();
            }
            frameBuffer.end();
            frameBufferBound = false;
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logDuration(
                        "first_combat_ui_prewarm_step",
                        "player-spine-mesh",
                        started,
                        "reason=" + reason,
                        false
                );
            }
            return true;
        }
        catch (Throwable ignored) {
            return true;
        }
        finally {
            if (spriteBatch != null && spriteBatch.isDrawing()) {
                try {
                    spriteBatch.end();
                }
                catch (RuntimeException ignored) {
                    // The prewarm path is best-effort; leave gameplay rendering untouched on failure.
                }
            }
            if (frameBufferBound && frameBuffer != null) {
                try {
                    frameBuffer.end();
                }
                catch (RuntimeException ignored) {
                    // The next normal frame will restore the primary framebuffer.
                }
            }
            if (spriteBatch != null) {
                try {
                    spriteBatch.dispose();
                }
                catch (RuntimeException ignored) {
                    // Best-effort cleanup only.
                }
            }
            if (frameBuffer != null) {
                try {
                    frameBuffer.dispose();
                }
                catch (RuntimeException ignored) {
                    // Best-effort cleanup only.
                }
            }
        }
    }

    private static boolean initializeClass(String className, String reason) {
        long started = RamSaverDiag.enabled() ? System.nanoTime() : 0L;
        try {
            Class.forName(className, true, FirstCombatUiPrewarm.class.getClassLoader());
            if (RamSaverDiag.enabled()) {
                RamSaverDiag.logDuration(
                        "first_combat_ui_prewarm_step",
                        className,
                        started,
                        "reason=" + reason,
                        false
                );
            }
            return true;
        }
        catch (Throwable ignored) {
            return true;
        }
    }

    private static String[] buildPrewarmTextures() {
        String[] textures = new String[
                FIRST_ROOM_MONSTER_TEXTURES.length + COMBAT_STATUS_TEXTURES.length + INTENT_TEXTURES.length
        ];
        System.arraycopy(FIRST_ROOM_MONSTER_TEXTURES, 0, textures, 0, FIRST_ROOM_MONSTER_TEXTURES.length);
        System.arraycopy(
                COMBAT_STATUS_TEXTURES,
                0,
                textures,
                FIRST_ROOM_MONSTER_TEXTURES.length,
                COMBAT_STATUS_TEXTURES.length
        );
        System.arraycopy(
                INTENT_TEXTURES,
                0,
                textures,
                FIRST_ROOM_MONSTER_TEXTURES.length + COMBAT_STATUS_TEXTURES.length,
                INTENT_TEXTURES.length
        );
        return textures;
    }

    private static boolean shouldPrewarmDuringUpdate() {
        try {
            if (CardCrawlGame.mode == CardCrawlGame.GameMode.SPLASH) {
                splashUpdateCount++;
                return splashUpdateCount >= SPLASH_UPDATE_DELAY;
            }
            if (CardCrawlGame.mode == CardCrawlGame.GameMode.CHAR_SELECT) {
                return true;
            }
            if (CardCrawlGame.mode != CardCrawlGame.GameMode.GAMEPLAY) {
                return false;
            }
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            return room == null || room.phase != AbstractRoom.RoomPhase.COMBAT;
        }
        catch (RuntimeException ignored) {
            return false;
        }
    }

}
