package io.stamethyst.compatmod.presence;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.saveAndContinue.SaveFile;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;

/**
 * Intercepts floor-transition events in the vanilla dungeon to keep the
 * rich-presence IPC file up to date via {@link RichPresenceBridge}.
 *
 * <p>Patch domain: {@code rich_presence}
 * <p>Fix: reports current character and floor to the launcher whenever
 * the player moves to a new floor, so Steam Rich Presence reflects live
 * game state.
 * <p>Patch classes:
 * <ul>
 *   <li>{@link NextRoomTransitionStartPatch} — new runs and all subsequent floor advances</li>
 *   <li>{@link NextRoomTransitionFromSavePatch} — resuming an existing run from a save file</li>
 * </ul>
 */
public final class RichPresencePatches {
    private RichPresencePatches() {
    }

    /** Rewrites the main-menu snapshot after every game boot and return to the menu. */
    @SpirePatch2(
        clz = MainMenuScreen.class,
        method = SpirePatch.CONSTRUCTOR,
        paramtypez = {boolean.class}
    )
    public static class MainMenuScreenConstructorPatch {
        public static void Postfix() {
            RichPresenceBridge.updateMainMenuState();
        }
    }

    /**
     * Fires after the static {@code AbstractDungeon.nextRoomTransitionStart()} which
     * increments {@code floorNum} and wires up the next room before the dungeon enters
     * it — covers new runs and every subsequent floor advance.
     */
    @SpirePatch2(clz = AbstractDungeon.class, method = "nextRoomTransitionStart")
    public static class NextRoomTransitionStartPatch {
        public static void Postfix() {
            RichPresenceBridge.updateDungeonState();
        }
    }

    /**
     * Fires after {@code AbstractDungeon.nextRoomTransition(SaveFile)} which restores
     * dungeon state when resuming a run from a save file. Without this patch the IPC
     * file is never written for save-loaded runs until the player advances a floor.
     */
    @SpirePatch2(clz = AbstractDungeon.class, method = "nextRoomTransition",
            paramtypez = {SaveFile.class})
    public static class NextRoomTransitionFromSavePatch {
        public static void Postfix() {
            RichPresenceBridge.updateDungeonState();
        }
    }
}
