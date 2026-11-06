package io.stamethyst.godot;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

/** Android lifecycle boundaries supplement the real file-lock and cleanup unit tests. */
public class GodotComponentLeaseContractTest {
    private String source(String path) throws Exception {
        File file = new File("src/main/java/io/stamethyst/" + path);
        if (!file.isFile()) file = new File("app", file.getPath());
        return new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test public void engineClaimsComponentsBeforeReadingTheReleaseAndKeepsThemUntilProcessDeath() throws Exception {
        String activity = source("godot/GodotMinigameActivity.java");
        int acquire = activity.indexOf("SlingBreakStorage.tryAcquireComponents(this)");
        assertTrue(acquire > 0);
        assertTrue(acquire < activity.indexOf("SlingBreakInstallation.read(this)"));
        assertTrue(activity.contains("private java.io.Closeable componentLease;"));
        assertTrue(activity.contains("if (componentLease == null) throw"));
        // onPause/onStop/onDestroy must not unlock while native code may still run.
        assertFalse(activity.contains("componentLease.close()"));
        assertTrue(activity.contains("android.os.Process.killProcess(android.os.Process.myPid())"));
    }

    @Test public void installerRequiresExclusiveComponentsBeforeAnyDownloadOrActivation() throws Exception {
        String service = source("backend/minigame/SlingBreakService.kt");
        int acquire = service.indexOf("SlingBreakStorage.tryAcquireComponents(context)");
        assertTrue(acquire > service.indexOf("lockFile.channel.lock().use"));
        assertTrue(service.contains("?: throw IOException(context.getString(R.string.godot_components_busy))"));
        assertTrue(service.contains("lease.use { installWithComponentsLocked("));
        assertTrue(acquire < service.indexOf("SlingBreakInstallation.activateWhenReady("));
    }
}
