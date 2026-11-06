package io.stamethyst.godot;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

/** Guards the Android-only boundary that the pure state tests cannot exercise. */
public class GodotNativeUiContractTest {
    private String source(String path) throws Exception {
        File file = new File("src/main/java/io/stamethyst/" + path);
        if (!file.isFile()) file = new File("app", file.getPath());
        return new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test public void normalMinigameHasNoNativeStatusButtonOrProgressWidgets() throws Exception {
        String activity = source("godot/GodotMinigameActivity.java");
        for (String widget : new String[]{"TextView", "Button", "ProgressBar"}) {
            assertFalse(activity.contains("new " + widget + "("));
            assertFalse(activity.contains("private " + widget + " "));
        }
        assertTrue(activity.contains("exitState.pressBack(SystemClock.elapsedRealtime())"));
        assertTrue(activity.contains("exitState.acknowledgeSave()"));
        assertTrue(activity.contains("exitState.resetBack();"));
        assertTrue(activity.contains("return super.getResources().getString(resource)"));
        assertFalse(activity.contains(".setMessage(R.string."));
        assertFalse(activity.contains("Toast.makeText(this, R.string."));
    }

    @Test public void launcherReturnAndMainGameEntryHaveDifferentMessagesAndCallbacks() throws Exception {
        assertNotEquals(GodotBootProtocol.ENTER_MAIN_GAME, GodotBootProtocol.RETURN_TO_LAUNCHER);
        assertNotEquals(GodotBootProtocol.CANCEL_MAIN_GAME, GodotBootProtocol.RETURN_TO_LAUNCHER);
        String boot = source("godot/GodotMinigameBoot.java");
        assertTrue(boot.contains("GodotBootProtocol.CANCEL_MAIN_GAME"));
        assertTrue(boot.contains("completion.claimEntry()"));
        assertTrue(boot.contains("completion.claimLauncherReturn()"));
        assertTrue(boot.contains(".returnToLauncherFromGodot()"));
        String coordinator = source("GameSessionCoordinator.kt");
        int begin = coordinator.indexOf("fun returnToLauncherFromGodot()");
        String returning = coordinator.substring(begin, coordinator.indexOf("private fun requestBackExitToLauncher()", begin));
        assertTrue(returning.contains("requestBackExitToLauncher()"));
        assertTrue(returning.contains("activity.markGodotBootCancelled()"));
        assertTrue(returning.indexOf("activity.markGodotBootCancelled()") < returning.indexOf("requestBackExitToLauncher()"));
        assertTrue(returning.contains("android.os.Process.killProcess(android.os.Process.myPid())"));
        assertFalse(returning.contains("bootOverlayController.dismiss()"));
    }

    @Test public void lateJvmCallbacksCannotReleaseTheGuardBeforeTheShutdownTimer() throws Exception {
        String activity = source("StsGameActivity.kt");
        int begin = activity.indexOf("internal fun markGodotBootCancelled()");
        String cancelling = activity.substring(begin, activity.indexOf("private val filePickerLauncher", begin));
        assertTrue(cancelling.contains("synchronized(launchGuardLock)"));
        assertTrue(cancelling.contains("keepLaunchGuardUntilProcessDeath = true"));
        begin = activity.indexOf("private fun releaseLaunchGuard()");
        String releasing = activity.substring(begin, activity.indexOf("private fun buildMemoryEventExtras()", begin));
        assertTrue(releasing.contains("!launchGuardAcquired || keepLaunchGuardUntilProcessDeath"));
    }
}
