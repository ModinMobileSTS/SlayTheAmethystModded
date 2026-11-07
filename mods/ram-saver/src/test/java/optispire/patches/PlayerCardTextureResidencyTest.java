package optispire.patches;

import basemod.abstracts.CustomCard;
import com.badlogic.gdx.graphics.RamSaverTestSupport;
import com.badlogic.gdx.graphics.RealTexture;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.megacrit.cardcrawl.actions.common.DrawCardAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.characters.Ironclad;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.localization.CharacterStrings;
import com.megacrit.cardcrawl.localization.LocalizedStrings;
import com.megacrit.cardcrawl.localization.UIStrings;
import com.megacrit.cardcrawl.localization.TutorialStrings;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import optispire.RamSaver;
import optispire.RunTextureResidency;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class PlayerCardTextureResidencyTest extends RamSaverTestSupport {
    private Unsafe unsafe;
    private CardCrawlGame.GameMode previousMode;
    private LocalizedStrings previousLanguage;
    private AbstractPlayer player;

    @Before public void setUpGame() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        unsafe = (Unsafe) f.get(null);
        previousLanguage = CardCrawlGame.languagePack;
        CardCrawlGame.languagePack = (LocalizedStrings) unsafe.allocateInstance(TestLanguage.class);
        previousMode = CardCrawlGame.mode;
        player = (AbstractPlayer) unsafe.allocateInstance(Ironclad.class);
        player.masterDeck = group(); player.hand = group(); player.drawPile = group();
        player.discardPile = group(); player.exhaustPile = group(); player.limbo = group();
        player.powers = new ArrayList<>();
        CardCrawlGame.mode = CardCrawlGame.GameMode.GAMEPLAY;
        PlayerCardTextureResidency.EndRunPatch.Prefix();
        set(FirstCombatPrewarmBudget.class, "claimed", false);
        set(FirstCombatPrewarmBudget.class, "cooldownFrames", 0);
        set(FirstCombatPrewarmBudget.class, "stopped", false);
    }

    @After public void tearDownGame() throws Exception {
        PlayerCardTextureResidency.EndRunPatch.Prefix();
        set(FirstCombatPrewarmBudget.class, "claimed", false);
        set(FirstCombatPrewarmBudget.class, "cooldownFrames", 0);
        set(FirstCombatPrewarmBudget.class, "stopped", false);
        set(FirstCombatPrewarmBudget.class, "gameplayStarted", -1L);
        set(FirstCombatPrewarmBudget.class, "texturePass", -1);
        CardCrawlGame.languagePack = previousLanguage;
        CardCrawlGame.mode = previousMode;
    }

    private CardGroup group() throws Exception {
        CardGroup group = (CardGroup) unsafe.allocateInstance(CardGroup.class);
        group.group = new ArrayList<>();
        return group;
    }

    private Texture texture(String path) {
        return texture(path, 16);
    }

    private Texture texture(String path, int size) {
        MemoryFile file = new MemoryFile(path, new byte[0]);
        String key = RamSaver.textureKey(file, null, false, Texture.TextureFilter.Nearest,
                Texture.TextureFilter.Nearest, Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        RamSaver.registerTexture(key, new RamSaver.FileTextureSupplier(file, null, false) {
            @Override public Texture get() { return new RealTexture(new MockData(size)); }
        });
        RamSaver.cacheTextureSize(key, size, size);
        return new Texture(file);
    }

    private TestCard card(Texture texture) throws Exception {
        TestCard card = (TestCard) unsafe.allocateInstance(TestCard.class);
        card.portrait = new TextureAtlas.AtlasRegion(texture, 0, 0, 16, 16);
        return card;
    }

    @Test public void allPlayerPilesProtectedButLibraryCardsAreNot() throws Exception {
        Texture[] cards = new Texture[6];
        CardGroup[] groups = {player.masterDeck, player.hand, player.drawPile,
                player.discardPile, player.exhaustPile, player.limbo};
        for (int i = 0; i < cards.length; i++) {
            cards[i] = texture("pile-" + i + ".png");
            groups[i].group.add(card(cards[i]));
        }
        Texture library = texture("library.png");
        TestCard.library = library;
        PlayerCardTextureResidency.reconcile(player, true, true);
        for (Texture texture : cards) {
            assertTrue(RunTextureResidency.isProtected(texture.getRamSaverKey()));
            texture.getRealTexture();
        }
        assertFalse(RunTextureResidency.isProtected(library.getRamSaverKey()));
        for (int i = 0; i < 50; i++) tick();
        assertEquals(6, live.size());
    }

    @Test public void generatedCardIsTrackedAndCombatOnlyCardsDropAfterBattle() throws Exception {
        Texture master = texture("master.png");
        Texture generatedCard = texture("generated.png");
        player.masterDeck.group.add(card(master));
        PlayerCardTextureResidency.reconcile(player, true, true);
        player.discardPile.group.add(card(generatedCard));
        PlayerCardTextureResidency.markDirty(player.discardPile, player);
        PlayerCardTextureResidency.reconcile(player, true, true);
        assertTrue(RunTextureResidency.isProtected(generatedCard.getRamSaverKey()));
        PlayerCardTextureResidency.reconcile(player, false, true);
        assertFalse(RunTextureResidency.isProtected(generatedCard.getRamSaverKey()));
        assertTrue(RunTextureResidency.isProtected(master.getRamSaverKey()));
    }

    @Test public void drawGateUsesOneUploadThenTwoCleanUpdatesAfterFirstCombatStopped() throws Exception {
        Texture first = texture("draw-first.png"), second = texture("draw-second.png");
        player.drawPile.group.add(card(first));
        player.drawPile.group.add(card(second));
        PlayerCardTextureResidency.reconcile(player, true, true);
        DrawCardAction action = (DrawCardAction) unsafe.allocateInstance(DrawCardAction.class);
        action.amount = 2;
        FirstCombatPrewarmBudget.beginFrame(0L, true, true);
        assertTrue(PlayerCardTextureResidency.deferDraw(action, player));
        assertEquals(1, generated);
        for (int frame = 1; frame <= 2; frame++) {
            FirstCombatPrewarmBudget.beginFrame(frame, true, true);
            assertTrue(PlayerCardTextureResidency.deferDraw(action, player));
            assertEquals(1, generated);
        }
        FirstCombatPrewarmBudget.beginFrame(3L, true, true);
        assertTrue(PlayerCardTextureResidency.deferDraw(action, player));
        assertEquals(2, generated);
        assertFalse(PlayerCardTextureResidency.deferDraw(action, player));
    }

    @Test public void drawPatchDoesNotDelayNoDrawZeroDrawOrFullHand() throws Exception {
        player.drawPile.group.add(card(texture("no-draw.png")));
        PlayerCardTextureResidency.reconcile(player, true, true);
        DrawCardAction action = (DrawCardAction) unsafe.allocateInstance(DrawCardAction.class);
        assertFalse(PlayerCardTextureResidency.deferDraw(action, player));
        action.amount = 1;
        for (int i = 0; i < 10; i++) player.hand.group.add(card(texture("hand-" + i + ".png")));
        assertFalse(PlayerCardTextureResidency.deferDraw(action, player));
        assertEquals(0, generated);
    }

    @Test public void noDrawPowerDoesNotWarmOrDelayAction() throws Exception {
        player.drawPile.group.add(card(texture("blocked-draw.png")));
        PlayerCardTextureResidency.reconcile(player, true, true);
        TestPower power = new TestPower();
        power.ID = "No Draw";
        player.powers.add(power);
        DrawCardAction action = (DrawCardAction) unsafe.allocateInstance(DrawCardAction.class);
        action.amount = 1;
        assertFalse(PlayerCardTextureResidency.deferDraw(action, player));
        assertEquals(0, generated);
    }

    @Test public void pendingWorkerDoesNotCompletePrewarmOrHoldDrawForever() throws Exception {
        optispire.BoundedTextureDecoderTest.ManualExecutor executor = new optispire.BoundedTextureDecoderTest.ManualExecutor();
        optispire.BoundedTextureDecoder<optispire.PngTextureDecoder.Pixels> queue =
                new optispire.BoundedTextureDecoder<>(executor, 1024 * 1024, 2);
        set(optispire.TextureDecodeService.class, "decoder", queue);
        try {
            MemoryFile file = new MemoryFile("pending-draw.png", optispire.PngFixtures.image(1, 1, 4, new byte[4], 0));
            Texture texture = new Texture(file);
            player.drawPile.group.add(card(texture));
            PlayerCardTextureResidency.reconcile(player, true, true);
            DrawCardAction action = (DrawCardAction) unsafe.allocateInstance(DrawCardAction.class);
            action.amount = 1;
            FirstCombatPrewarmBudget.beginFrame(0L, true, true);
            assertTrue(PlayerCardTextureResidency.deferDraw(action, player));
            assertEquals(0, generated);
            assertTrue(RunTextureResidency.hasPending(null));
            set(PlayerCardTextureResidency.class, "drawWaitUpdates", 120);
            assertFalse(PlayerCardTextureResidency.deferDraw(action, player));
            assertTrue("Timeout doesn't pretend it uploaded", RunTextureResidency.hasPending(null));
            queue.shutdown(); executor.drainWorker();
            assertEquals(0, queue.pendingCount());
        } finally {
            queue.shutdown();
            set(optispire.TextureDecodeService.class, "decoder", null);
        }
    }

    @Test public void oneCardDrawOnlyPrewarmsTheActualTopCard() throws Exception {
        Texture bottom = texture("bottom.png"), top = texture("top.png");
        player.drawPile.group.add(card(bottom));
        player.drawPile.group.add(card(top));
        PlayerCardTextureResidency.reconcile(player, true, true);
        DrawCardAction action = (DrawCardAction) unsafe.allocateInstance(DrawCardAction.class);
        action.amount = 1;
        FirstCombatPrewarmBudget.beginFrame(0L, true, true);
        assertTrue(PlayerCardTextureResidency.deferDraw(action, player));
        assertEquals(1, generated);
        assertNotNull(RamSaver.getAsset(top.getRamSaverKey(), false));
        assertNull(RamSaver.getAsset(bottom.getRamSaverKey(), false));
        assertFalse(PlayerCardTextureResidency.deferDraw(action, player));
    }

    @Test public void previewCyclesAreDeduplicatedAndRemovedCardsReleaseTheirImages() throws Exception {
        Texture first = texture("preview-first.png"), second = texture("preview-second.png");
        TestCard a = card(first), b = card(second);
        a.cardsToPreview = b; b.cardsToPreview = a;
        player.masterDeck.group.add(a);
        PlayerCardTextureResidency.reconcile(player, false, true);
        assertTrue(RunTextureResidency.isProtected(first.getRamSaverKey()));
        assertTrue(RunTextureResidency.isProtected(second.getRamSaverKey()));
        player.masterDeck.group.clear();
        PlayerCardTextureResidency.markDirty(player.masterDeck, player);
        PlayerCardTextureResidency.reconcile(player, false, true);
        assertFalse(RunTextureResidency.isProtected(first.getRamSaverKey()));
        assertFalse(RunTextureResidency.isProtected(second.getRamSaverKey()));
    }

    @Test public void scarceBudgetProtectsTopOfDrawPileAheadOfBottomAndMasterDeck() throws Exception {
        Texture bottom = texture("budget-bottom.png", 512);
        Texture top = texture("budget-top.png", 512);
        Texture master = texture("budget-master.png", 512);
        player.drawPile.group.add(card(bottom));
        player.drawPile.group.add(card(top));
        player.masterDeck.group.add(card(master));
        PlayerCardTextureResidency.reconcile(player, true, true);
        assertTrue(RunTextureResidency.isProtected(top.getRamSaverKey()));
        assertFalse(RunTextureResidency.isProtected(bottom.getRamSaverKey()));
        assertFalse(RunTextureResidency.isProtected(master.getRamSaverKey()));
    }

    @Test public void inUseAndLimboCardsTakePriorityOverDrawPileAndReserve() throws Exception {
        Texture inUse = texture("budget-in-use.png", 256);
        Texture limbo = texture("budget-limbo.png", 256);
        Texture draw = texture("budget-draw.png", 512);
        player.cardInUse = card(inUse);
        player.limbo.group.add(card(limbo));
        player.drawPile.group.add(card(draw));
        PlayerCardTextureResidency.reconcile(player, true, true);
        assertTrue(RunTextureResidency.isProtected(inUse.getRamSaverKey()));
        assertTrue(RunTextureResidency.isProtected(limbo.getRamSaverKey()));
        assertFalse(RunTextureResidency.isProtected(draw.getRamSaverKey()));
    }

    @Test public void globalPressurePausesFirstCombatWorkWithoutConsumingItsStep() {
        RunTextureResidency.updatePressure(Long.MAX_VALUE, 1, 100, 0L);
        FirstCombatPrewarmBudget.beginFrame(0L, false, false);
        assertFalse(FirstCombatPrewarmBudget.tryStep());
        RunTextureResidency.updatePressure(0L, 1, 100, 31_000_000_000L);
        assertTrue("The skipped work can resume without another cooldown", FirstCombatPrewarmBudget.tryStep());
    }

    @Test public void allPatchTargetsExistAndHaveUnambiguousSignatures() throws Exception {
        for (Class<?> patchClass : PlayerCardTextureResidency.class.getDeclaredClasses()) {
            for (SpirePatch2 patch : patchClass.getAnnotationsByType(SpirePatch2.class)) {
                int count = 0;
                for (java.lang.reflect.Method method : patch.clz().getDeclaredMethods()) {
                    if (!method.getName().equals(patch.method())) continue;
                    Class<?>[] types = patch.paramtypez();
                    boolean unspecified = types.length == 1 && types[0] == void.class;
                    if (unspecified || java.util.Arrays.equals(types, method.getParameterTypes())) count++;
                }
                assertEquals(patchClass.getSimpleName() + " -> " + patch.clz().getName() + "." + patch.method(), 1, count);
            }
        }
    }

    @Test public void switchingPlayerOrLeavingGameplayDropsPreviousRun() throws Exception {
        Texture old = texture("old-run.png");
        player.masterDeck.group.add(card(old));
        PlayerCardTextureResidency.reconcile(player, true, true);
        assertTrue(RunTextureResidency.isProtected(old.getRamSaverKey()));
        PlayerCardTextureResidency.reconcile(null, false, false);
        assertFalse(RunTextureResidency.isProtected(old.getRamSaverKey()));
        PlayerCardTextureResidency.reconcile(player, true, true);
        assertTrue(RunTextureResidency.isProtected(old.getRamSaverKey()));
        CardCrawlGame.mode = CardCrawlGame.GameMode.CHAR_SELECT;
        PlayerCardTextureResidency.UpdatePatch.Postfix();
        assertFalse(RunTextureResidency.isProtected(old.getRamSaverKey()));
    }

    @Test public void textureCollectorIsLazyDeduplicatedAndIgnoresStaticFields() throws Exception {
        Texture own = texture("own.png"), unrelated = texture("unrelated-library.png");
        FieldsFixture fixture = new FieldsFixture();
        fixture.texture = own;
        fixture.region = new TextureRegion(own, 0, 0, 16, 16);
        FieldsFixture.library = unrelated;
        Map<String, Long> collected = new LinkedHashMap<>();
        PlayerCardTextureResidency.collectFields(fixture, collected);
        assertEquals(1, collected.size());
        assertTrue(collected.containsKey(own.getRamSaverKey()));
        assertEquals(0, generated);
    }

    @Test public void customCardProtectsSmallUiWithoutTraversingBaseModLibraryOrLargeRegions() throws Exception {
        Texture portrait = texture("custom-portrait.png"), background = texture("custom-background.png");
        Texture orb = texture("custom-orb.png"), banner = texture("custom-banner.png");
        Texture large = texture("custom-large.png"), library = texture("custom-library.png");
        TestCustomCard card = (TestCustomCard) unsafe.allocateInstance(TestCustomCard.class);
        card.portrait = new TextureAtlas.AtlasRegion(portrait, 0, 0, 16, 16);
        card.frameLargeRegion = new TextureAtlas.AtlasRegion(large, 0, 0, 16, 16);
        card.small = new Texture[] {background, orb, banner};
        CustomCard.imgMap.put("unrelated-card", library);
        try {
            player.masterDeck.group.add(card);
            PlayerCardTextureResidency.reconcile(player, false, true);
            for (Texture texture : new Texture[] {portrait, background, orb, banner}) {
                assertTrue(RunTextureResidency.isProtected(texture.getRamSaverKey()));
            }
            assertFalse(RunTextureResidency.isProtected(large.getRamSaverKey()));
            assertFalse(RunTextureResidency.isProtected(library.getRamSaverKey()));
            assertEquals("Discovery is lazy even for CustomCard", 0, generated);
        } finally {
            CustomCard.imgMap.remove("unrelated-card");
        }
    }

    public static class FieldsFixture {
        Texture texture;
        TextureRegion region;
        static Texture library;
    }

    public static class TestPower extends AbstractPower { }

    public static class TestCustomCard extends CustomCard {
        Texture[] small;
        private TestCustomCard() { super(null, null, (String) null, 0, null, CardType.SKILL,
                CardColor.RED, CardRarity.BASIC, CardTarget.NONE); }
        @Override public Texture getBackgroundSmallTexture() { return small[0]; }
        @Override public Texture getOrbSmallTexture() { return small[1]; }
        @Override public Texture getBannerSmallTexture() { return small[2]; }
        @Override public Texture getBackgroundLargeTexture() { throw new AssertionError("Large UI requested"); }
        public void use(AbstractPlayer player, AbstractMonster monster) { }
        public void upgrade() { }
    }

    public static class TestCard extends AbstractCard {
        static Texture library;
        private TestCard() { super(null, null, null, 0, null, CardType.SKILL, CardColor.RED,
                CardRarity.BASIC, CardTarget.NONE); }
        public void use(AbstractPlayer player, AbstractMonster monster) { }
        public void upgrade() { }
        public AbstractCard makeCopy() { return this; }
    }

    public static class TestLanguage extends LocalizedStrings {
        @Override public TutorialStrings getTutorialString(String key) {
            TutorialStrings strings = new TutorialStrings();
            strings.TEXT = new String[40];
            strings.LABEL = new String[40];
            return strings;
        }
        @Override public UIStrings getUIString(String key) {
            UIStrings strings = new UIStrings();
            strings.TEXT = new String[40];
            java.util.Arrays.fill(strings.TEXT, "test");
            return strings;
        }
        @Override public CharacterStrings getCharacterString(String key) {
            CharacterStrings strings = new CharacterStrings();
            strings.NAMES = new String[] {"test"};
            strings.TEXT = new String[40];
            return strings;
        }
    }
}
