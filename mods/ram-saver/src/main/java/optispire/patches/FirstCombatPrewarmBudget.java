package optispire.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.MonsterRoom;
import com.megacrit.cardcrawl.saveAndContinue.SaveFile;

/** One synchronous step per update across all first-combat prewarm patches, not a time deadline. */
final class FirstCombatPrewarmBudget {
    private static final long RECHECK_WINDOW_NANOS = 5_000_000_000L;
    private static boolean claimed;
    private static boolean stopped;
    private static long gameplayStarted = -1L;
    private static int texturePass = -1;

    private FirstCombatPrewarmBudget() { }

    @SpirePatch2(clz = CardCrawlGame.class, method = "update")
    public static class UpdatePatch {
        @SpirePrefixPatch
        public static void Prefix() {
            try {
                boolean gameplay = CardCrawlGame.mode == CardCrawlGame.GameMode.GAMEPLAY;
                AbstractRoom room = gameplay ? AbstractDungeon.getCurrRoom() : null;
                beginFrame(System.nanoTime(), gameplay,
                        room != null && room.phase == AbstractRoom.RoomPhase.COMBAT);
            }
            catch (RuntimeException ignored) {
                // The update hook also runs while the dungeon is being initialized.
                // Prewarming must never make the render loop fail during that window.
                claimed = false;
                texturePass = -1;
            }
        }
    }

    @SpirePatch2(clz = AbstractDungeon.class, method = "nextRoomTransition", paramtypez = {SaveFile.class})
    public static class TransitionPatch {
        @SpirePrefixPatch
        public static void Prefix() {
            if (AbstractDungeon.nextRoom != null && AbstractDungeon.nextRoom.room instanceof MonsterRoom) {
                stopped = true;
                texturePass = -1;
            }
        }
    }

    static void beginFrame(long now, boolean gameplay, boolean combat) {
        claimed = false;
        stopped |= combat;
        if (gameplay && gameplayStarted == -1L) gameplayStarted = now;
        // Completion means an attempt, not permanent residency. Recheck only once near run start.
        texturePass = stopped ? -1 : gameplay
                ? (now - gameplayStarted < RECHECK_WINDOW_NANOS ? 1 : -1)
                : (gameplayStarted == -1L ? 0 : -1);
    }

    static int texturePass() {
        return texturePass;
    }

    static boolean tryStep() {
        if (stopped || claimed) return false;
        claimed = true;
        return true;
    }
}
