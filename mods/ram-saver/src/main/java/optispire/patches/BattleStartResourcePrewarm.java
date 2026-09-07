package optispire.patches;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.vfx.combat.BattleStartEffect;
import optispire.RamSaverDiag;

public final class BattleStartResourcePrewarm {
    private static final int STEP_COUNT = 16;
    private static final int SPLASH_UPDATE_DELAY = 40;

    private static final GlyphLayout GLYPH_LAYOUT = new GlyphLayout();
    private static int nextStep = 0;
    private static int splashUpdateCount = 0;

    private BattleStartResourcePrewarm() {
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
        if (nextStep >= STEP_COUNT || !FirstCombatPrewarmBudget.tryStep()) {
            return;
        }
        if (prewarmStep(nextStep, "background")) {
            nextStep++;
        }
    }

    private static boolean prewarmStep(int step, String reason) {
        switch (step) {
            case 0:
                return prewarmSound("BATTLE_START_1", reason);
            case 1:
                return prewarmSound("BATTLE_START_2", reason);
            case 2:
                return prewarmSound("BATTLE_START_BOSS", reason);
            case 3:
                return prewarmSound("BUFF_1", reason);
            case 4:
                return prewarmSound("BUFF_2", reason);
            case 5:
                return prewarmSound("BUFF_3", reason);
            case 6:
                return prewarmSound("DEBUFF_1", reason);
            case 7:
                return prewarmSound("DEBUFF_2", reason);
            case 8:
                return prewarmSound("DEBUFF_3", reason);
            case 9:
                return prewarmBattleStartSwordRegion(reason);
            case 10:
                return prewarmBattleStartGlyph(FontHelper.bannerNameFont, battleStartText(0), reason);
            case 11:
                return prewarmBattleStartGlyph(FontHelper.bannerNameFont, battleStartText(1), reason);
            case 12:
                return prewarmBattleStartGlyph(FontHelper.bannerNameFont, battleStartText(2), reason);
            case 13:
                return prewarmBattleStartGlyph(FontHelper.turnNumFont, "1" + battleStartText(3), reason);
            case 14:
                return prewarmBattleStartGlyph(
                        FontHelper.turnNumFont,
                        "1" + BattleStartEffect.getOrdinalNaming(1) + battleStartText(3),
                        reason
                );
            case 15:
                return prewarmBattleStartGlyph(FontHelper.turnNumFont, battleStartText(3) + " 1", reason);
            default:
                return true;
        }
    }

    private static boolean prewarmSound(String key, String reason) {
        if (CardCrawlGame.sound == null || Gdx.audio == null || Gdx.files == null) {
            return false;
        }

        long started = RamSaverDiag.enabled() ? System.nanoTime() : 0L;
        try {
            CardCrawlGame.sound.playV(key, 0.0f);
            CardCrawlGame.sound.stop(key);
            logStep(started, reason, "sound:" + key);
            return true;
        }
        catch (RuntimeException ignored) {
            return true;
        }
    }

    private static boolean prewarmBattleStartSwordRegion(String reason) {
        if (ImageMaster.vfxAtlas == null) {
            return false;
        }

        long started = RamSaverDiag.enabled() ? System.nanoTime() : 0L;
        try {
            // findRegion alone does not materialize a page; the atlas page is already covered by CombatTexturePrewarm.
            ImageMaster.vfxAtlas.findRegion("combat/battleStartSword");
            logStep(started, reason, "region:combat/battleStartSword");
            return true;
        }
        catch (RuntimeException ignored) {
            return true;
        }
    }

    private static boolean prewarmBattleStartGlyph(BitmapFont font, String text, String reason) {
        if (font == null || text == null || text.length() == 0) {
            return font != null;
        }

        long started = RamSaverDiag.enabled() ? System.nanoTime() : 0L;
        try {
            GLYPH_LAYOUT.setText(font, text);
            logStep(started, reason, "glyph:" + RamSaverDiag.safe(text));
            return true;
        }
        catch (RuntimeException ignored) {
            return true;
        }
    }

    private static String battleStartText(int index) {
        if (CardCrawlGame.languagePack == null) {
            return null;
        }
        String[] text = BattleStartEffect.TEXT;
        if (text == null || index < 0 || index >= text.length) {
            return null;
        }
        return text[index];
    }

    private static void logStep(long started, String reason, String key) {
        if (!RamSaverDiag.enabled()) {
            return;
        }
        RamSaverDiag.logDuration(
                "battle_start_resource_prewarm_step",
                key,
                started,
                "reason=" + reason + " completed=" + (nextStep + 1) + "/" + STEP_COUNT,
                false
        );
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
