package optispire.patches;

import org.junit.Test;

import static org.junit.Assert.*;

public class PrewarmRegressionTest {
    @Test
    public void combatAtlasPathMatchesTheDeclaredAtlas() {
        assertEquals("cardui/cardui.atlas", CombatTexturePrewarm.atlasPath("cardui/cardui.png"));
        assertEquals("cards/cards.atlas", CombatTexturePrewarm.atlasPath("cards/cards5.png"));
        assertEquals("powers/powers.atlas", CombatTexturePrewarm.atlasPath("powers/powers.png"));
        assertEquals("bottomScene/scene.atlas", CombatTexturePrewarm.atlasPath("bottomScene/scene3.jpg"));
        assertEquals("vfx/vfx.atlas", CombatTexturePrewarm.atlasPath("vfx/vfx.png"));
    }

    @Test
    public void prewarmBudgetAllowsOnlyOneStepPerFrame() {
        FirstCombatPrewarmBudget.beginFrame(0L, false, false);
        assertTrue(FirstCombatPrewarmBudget.tryStep());
        assertFalse(FirstCombatPrewarmBudget.tryStep());
        FirstCombatPrewarmBudget.beginFrame(1L, false, false);
        assertFalse(FirstCombatPrewarmBudget.tryStep());
        FirstCombatPrewarmBudget.beginFrame(2L, false, false);
        assertFalse(FirstCombatPrewarmBudget.tryStep());
        FirstCombatPrewarmBudget.beginFrame(3L, false, false);
        assertTrue(FirstCombatPrewarmBudget.tryStep());
    }
}
