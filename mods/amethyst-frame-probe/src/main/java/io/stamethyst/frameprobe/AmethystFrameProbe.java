package io.stamethyst.frameprobe;

import basemod.BaseMod;
import basemod.interfaces.PostRenderSubscriber;
import basemod.interfaces.PostUpdateSubscriber;
import com.badlogic.gdx.backends.lwjgl.FrameRingBuffer;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;

import java.io.File;

/**
 * Entry point for the amethyst-frame-probe bundled mod.
 *
 * <p>Active only when the system property {@code amethyst.gdx.frame_ring=true}
 * is set. When the ring is disabled this class still initialises but all
 * update/render callbacks are no-ops.
 *
 * <p>The mod is deliberately small: it wires together three independent
 * components ({@link FrameHud}, {@link IncidentWriter}, game-context patches)
 * so each can be reviewed and reverted on its own.
 */
@SpireInitializer
public class AmethystFrameProbe implements PostRenderSubscriber, PostUpdateSubscriber {

    private static final String PROP_STS_ROOT = "user.dir";
	private static final String PROP_FRAME_HUD = "amethyst.gdx.frame_hud";

    private final FrameHud       hud;
    private final IncidentWriter writer;
    private final boolean        active;
	private final boolean        hudVisible;

    public static void initialize() {
        BaseMod.subscribe(new AmethystFrameProbe());
    }

    public AmethystFrameProbe() {
        active = FrameRingBuffer.ENABLED;
		hudVisible = Boolean.parseBoolean(System.getProperty(PROP_FRAME_HUD, "true"));
        if (!active) {
            hud    = null;
            writer = null;
            System.out.println("[frame-probe] frame_ring not enabled – probe inactive");
            return;
        }
        File stsRoot = new File(System.getProperty(PROP_STS_ROOT, "."));
        writer = new IncidentWriter(stsRoot);
        writer.start();
        java.io.File snapshotFile = new java.io.File(
            System.getProperty("amethyst.bridge.launcher_perf_snapshot", ""));
        hud = new FrameHud(writer, FrameRingBuffer.BUDGET_NS, snapshotFile);
        System.out.println(
            "[frame-probe] active budgetNs=" + FrameRingBuffer.BUDGET_NS
            + " outputDir=" + stsRoot.getAbsolutePath());
    }

    @Override
    public void receivePostUpdate() {
        if (!active) return;
        hud.update();
    }

    @Override
    public void receivePostRender(SpriteBatch sb) {
        if (!active || !hudVisible) return;
        hud.render(sb);
    }
}
