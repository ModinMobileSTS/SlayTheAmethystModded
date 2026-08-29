package io.stamethyst.frameprobe;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

/**
 * Minimal SpirePatch2 hooks that write game context into {@link GameContext}.
 *
 * Design rules:
 *  - Each patch does ONE thing only (no monolithic class).
 *  - All hooks are Postfix (or Prefix where the info is only available before),
 *    so they never interfere with the original logic.
 *  - No allocation in the hot path: class name is derived via
 *    getClass().getSimpleName(), which is cached by the JVM after first call.
 */
public final class FrameProbePatches {

    private FrameProbePatches() {}

    // ── Card played ──────────────────────────────────────────────────────────
    // AbstractCard.use() is abstract; patch UseCardAction.update() instead.
    // UseCardAction is the concrete action that calls card.use() and is
    // enqueued for every card play — its targetCard field gives us the card.

    @SpirePatch2(
        clz = com.megacrit.cardcrawl.actions.utility.UseCardAction.class,
        method = "update"
    )
    public static class CardUsePatch {
        public static void Prefix(com.megacrit.cardcrawl.actions.utility.UseCardAction __instance) {
            if (__instance == null) return;
            try {
                java.lang.reflect.Field f = com.megacrit.cardcrawl.actions.utility.UseCardAction.class
                    .getDeclaredField("targetCard");
                f.setAccessible(true);
                AbstractCard card = (AbstractCard) f.get(__instance);
                if (card == null) return;
                GameContext.INSTANCE.onCardPlayed(
                    card.cardID != null ? card.cardID : card.getClass().getSimpleName(),
                    resolveFrameId());
            } catch (Throwable ignored) {}
        }
    }

    // ── Room entered ─────────────────────────────────────────────────────────

    @SpirePatch2(
        clz = AbstractDungeon.class,
        method = "nextRoomTransition",
        paramtypez = {}
    )
    public static class RoomTransitionPatch {
        public static void Postfix() {
            try {
                AbstractRoom room = AbstractDungeon.getCurrRoom();
                if (room == null) return;
                int floor = AbstractDungeon.floorNum;
                int act   = AbstractDungeon.actNum;
                GameContext.INSTANCE.onRoomEntered(
                    room.getClass().getSimpleName(), floor, act);
            } catch (Throwable ignored) {}
        }
    }

    // ── AbstractGameAction dequeued (GameActionManager.getNextAction) ─────────
    // AbstractGameAction.update() is abstract so Javassist cannot patch it.
    // getNextAction() is concrete and is called once per action when it is
    // popped from the queue — equivalent for context-tagging purposes.

    @SpirePatch2(
        clz = com.megacrit.cardcrawl.actions.GameActionManager.class,
        method = "getNextAction"
    )
    public static class ActionDequeuePatch {
        public static void Postfix() {
            try {
                com.megacrit.cardcrawl.actions.AbstractGameAction current =
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager != null
                        ? com.megacrit.cardcrawl.dungeons.AbstractDungeon.actionManager.currentAction
                        : null;
                if (current == null) return;
                GameContext.INSTANCE.onActionStarted(
                    current.getClass().getSimpleName(),
                    resolveFrameId());
            } catch (Throwable ignored) {}
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the current LibGDX frameId via the Gdx.graphics API.
     * Falls back to 0 if called before graphics is initialised.
     */
    private static long resolveFrameId() {
        try {
            com.badlogic.gdx.Graphics g = com.badlogic.gdx.Gdx.graphics;
            if (g != null) return g.getFrameId();
        } catch (Throwable ignored) {}
        return 0L;
    }
}
