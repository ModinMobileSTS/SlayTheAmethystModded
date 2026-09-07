package optispire.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import optispire.RamSaverDiag;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.Document;
import java.awt.Toolkit;
import java.io.PrintStream;

public final class FirstCombatLogConsolePrewarm {
    private static final int SPLASH_UPDATE_DELAY = 24;

    private static int splashUpdateCount = 0;
    private static boolean completed = false;

    private FirstCombatLogConsolePrewarm() {
    }

    @SpirePatch2(
            clz = CardCrawlGame.class,
            method = "update"
    )
    public static class CardCrawlGameUpdatePatch {
        public static void Postfix() {
            prewarmDuringNonCombat();
        }
    }

    private static void prewarmDuringNonCombat() {
        if (completed || !shouldPrewarmDuringUpdate() || !FirstCombatPrewarmBudget.tryStep()) {
            return;
        }
        prewarm("background");
    }

    private static void prewarm(String reason) {
        long started = RamSaverDiag.enabled() ? System.nanoTime() : 0L;
        try {
            Class.forName(
                    "com.evacipated.cardcrawl.modthespire.ui.MessageConsole$ConsoleOutputStream",
                    false,
                    FirstCombatLogConsolePrewarm.class.getClassLoader()
            );
            prewarmSwingDocumentInsert();
            prewarmPrintStream(System.out);
            prewarmPrintStream(System.err);
        }
        catch (Throwable ignored) {
            // Treat failures as non-fatal; repeating this during gameplay would be worse than skipping it.
        }
        completed = true;
        if (RamSaverDiag.enabled()) {
            RamSaverDiag.logDuration(
                    "first_combat_log_console_prewarm_done",
                    "message-console",
                    started,
                    "reason=" + reason,
                    false
            );
        }
    }

    private static void prewarmSwingDocumentInsert() throws Exception {
        SwingUtilities.isEventDispatchThread();
        Toolkit.getDefaultToolkit().getSystemEventQueue();

        JTextArea textArea = new JTextArea();
        Document document = textArea.getDocument();
        document.insertString(0, " ", null);
        textArea.setCaretPosition(document.getLength());
        document.remove(0, document.getLength());
    }

    private static void prewarmPrintStream(PrintStream printStream) {
        if (printStream == null) {
            return;
        }
        printStream.print(" ");
        printStream.flush();
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
