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
import optispire.HandTextureResidency;
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

    @Test public void gameplayBeforeMapNodeExistsDoesNotCrashResidencyUpdate() throws Exception {
        // Keep the real game methods (including getCurrRoom's null-node dereference), but omit
        // unrelated static screen/audio/Twitch construction. Isolate both dungeon and patch so
        // their static references resolve to the same fixture; all GDX/card/residency APIs are real.
        ClassLoader fixture = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals("com.megacrit.cardcrawl.dungeons.AbstractDungeon")
                        && !name.startsWith(PlayerCardTextureResidency.class.getName())
                        && !name.startsWith(FirstCombatPrewarmBudget.class.getName())
                        && !name.equals(PrewarmTextureRegistry.class.getName())) return super.loadClass(name, resolve);
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        javassist.ClassPool pool = new javassist.ClassPool(true);
                        pool.insertClassPath(new javassist.LoaderClassPath(getParent()));
                        javassist.CtClass type = pool.get(name);
                        if (name.equals("com.megacrit.cardcrawl.dungeons.AbstractDungeon"))
                            type.removeConstructor(type.getClassInitializer());
                        byte[] bytes = type.toBytecode();
                        type.detach();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (Exception error) { throw new ClassNotFoundException(name, error); }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        };
        Class<?> dungeon = fixture.loadClass("com.megacrit.cardcrawl.dungeons.AbstractDungeon");
        java.lang.reflect.Method update = fixture.loadClass(PlayerCardTextureResidency.UpdatePatch.class.getName()).getMethod("Postfix");
        java.lang.reflect.Method battle = fixture.loadClass(PlayerCardTextureResidency.BattleDeckPatch.class.getName()).getMethod("Postfix");
        update.invoke(null); // GAMEPLAY before player and map node construction.
        dungeon.getField("player").set(null, player);
        Texture portrait = texture("initializing-run.png");
        player.masterDeck.group.add(card(portrait));
        update.invoke(null); // Player exists, node still absent: also exercises update's second lookup.
        battle.invoke(null);
        assertTrue(RunTextureResidency.isProtected(portrait.getRamSaverKey()));
        com.megacrit.cardcrawl.map.MapRoomNode node = (com.megacrit.cardcrawl.map.MapRoomNode)
                unsafe.allocateInstance(com.megacrit.cardcrawl.map.MapRoomNode.class);
        dungeon.getField("currMapNode").set(null, node);
        update.invoke(null); // Map node exists, room not yet installed.
        com.megacrit.cardcrawl.rooms.AbstractRoom room = (com.megacrit.cardcrawl.rooms.AbstractRoom)
                unsafe.allocateInstance(com.megacrit.cardcrawl.rooms.MonsterRoom.class);
        room.phase = com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT;
        node.room = room;
        Texture next = texture("initializing-combat.png");
        player.drawPile.group.add(card(next));
        battle.invoke(null);
        assertTrue("Combat tracking resumes once the room is available", RunTextureResidency.isProtected(next.getRamSaverKey()));
        int before = generated;
        update.invoke(null);
        assertEquals("Combat must not trigger speculative uploads", before, generated);
        fixture.loadClass(PlayerCardTextureResidency.EndRunPatch.class.getName()).getMethod("Prefix").invoke(null);
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

    @Test public void scarceBudgetKeepsOrdinaryDrawPileOrderAheadOfMasterDeck() throws Exception {
        Texture bottom = texture("budget-bottom.png", 512);
        Texture top = texture("budget-top.png", 512);
        Texture master = texture("budget-master.png", 512);
        player.drawPile.group.add(card(bottom));
        player.drawPile.group.add(card(top));
        player.masterDeck.group.add(card(master));
        PlayerCardTextureResidency.reconcile(player, true, true);
        assertTrue(RunTextureResidency.isProtected(bottom.getRamSaverKey()));
        assertFalse(RunTextureResidency.isProtected(top.getRamSaverKey()));
        assertFalse(RunTextureResidency.isProtected(master.getRamSaverKey()));
    }

    @Test public void inUseAndLimboCardsDoNotDisplaceTheDrawPile() throws Exception {
        Texture inUse = texture("budget-in-use.png", 256);
        Texture limbo = texture("budget-limbo.png", 256);
        Texture draw = texture("budget-draw.png", 512);
        player.cardInUse = card(inUse);
        player.limbo.group.add(card(limbo));
        player.drawPile.group.add(card(draw));
        PlayerCardTextureResidency.reconcile(player, true, true);
        assertFalse(RunTextureResidency.isProtected(inUse.getRamSaverKey()));
        assertFalse(RunTextureResidency.isProtected(limbo.getRamSaverKey()));
        assertTrue(RunTextureResidency.isProtected(draw.getRamSaverKey()));
    }

    @Test public void runPressureLeavesFirstCombatWorkAndItsNormalCadenceIndependent() {
        RunTextureResidency.updatePressure(Long.MAX_VALUE, 1, 100, 0L);
        FirstCombatPrewarmBudget.beginFrame(0L, false, false);
        assertTrue(FirstCombatPrewarmBudget.tryStep());
        assertFalse("A successful step still consumes the shared frame budget", FirstCombatPrewarmBudget.tryStep());
        RunTextureResidency.updatePressure(0L, 1, 100, 31_000_000_000L);
        assertFalse("Pressure recovery does not reset an already consumed step", FirstCombatPrewarmBudget.tryStep());
    }

    @Test public void runPressureStillAllowsFirstCombatTextureMaterialization() throws Exception {
        Texture texture = texture("first-combat-under-run-pressure.png", 512);
        player.drawPile.group.add(card(texture));
        PlayerCardTextureResidency.reconcile(player, true, true);
        RunTextureResidency.updatePressure(Long.MAX_VALUE, 1, 100, 0L);
        assertFalse(RunTextureResidency.isProtected(texture.getRamSaverKey()));
        FirstCombatPrewarmBudget.beginFrame(0L, false, false);
        assertTrue(FirstCombatPrewarmBudget.tryStep());
        assertTrue(RamSaver.prewarmTexture(texture.getRamSaverKey(), false, optispire.TextureDecodeService.FIRST_COMBAT));
        assertEquals(1, generated);
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

    @Test public void handSurvivesBudgetDenialAndPressureWithoutDrawing() throws Exception {
        Texture portrait = texture("hand-over-budget.png", 1024);
        player.hand.group.add(card(portrait));
        PlayerCardTextureResidency.reconcile(player, true, true);
        assertFalse(RunTextureResidency.isProtected(portrait.getRamSaverKey()));
        assertTrue(HandTextureResidency.isProtected(portrait.getRamSaverKey()));
        Texture real = portrait.getRealTexture();
        int handle = real.getTextureObjectHandle();
        RunTextureResidency.updatePressure(Long.MAX_VALUE, 95, 100, 0L);
        for (int i = 0; i < 200; i++) tick();
        assertTrue(live.contains(handle));
        assertEquals(1, generated);
        assertTrue(deleted.isEmpty());
    }

    @Test public void handOwnershipTracksDirectEditsBeforeTheNextRunRescan() throws Exception {
        Texture first = texture("hand-direct-first.png", 1024);
        Texture next = texture("hand-direct-next.png", 1024);
        player.hand.group.add(card(first));
        PlayerCardTextureResidency.reconcile(player, true, true);
        first.getRealTexture(); next.getRealTexture();
        // Mimic mods that bypass every patched CardGroup method, within the 500 ms run scan interval.
        set(PlayerCardTextureResidency.class, "nextScan", Long.MAX_VALUE);
        player.hand.group.clear(); player.hand.group.add(card(next));
        PlayerCardTextureResidency.reconcile(player, true, true);
        assertFalse(HandTextureResidency.isProtected(first.getRamSaverKey()));
        assertTrue(HandTextureResidency.isProtected(next.getRamSaverKey()));
        for (int i = 0; i < 20; i++) tick();
        assertNull(RamSaver.getAsset(first.getRamSaverKey(), false));
        assertNotNull(RamSaver.getAsset(next.getRamSaverKey(), false));
    }

    @Test public void handProtectionPersistsOutsideCombatAndReleasesAfterLastSharedOwnerLeaves() throws Exception {
        Texture portrait = texture("hand-shared-outside-combat.png", 1024);
        TestCard first = card(portrait), second = card(portrait);
        player.hand.group.add(first); player.hand.group.add(second);
        PlayerCardTextureResidency.reconcile(player, true, true);
        Texture real = portrait.getRealTexture(); int handle = real.getTextureObjectHandle();
        PlayerCardTextureResidency.reconcile(player, false, true);
        assertTrue(HandTextureResidency.isProtected(portrait.getRamSaverKey()));
        player.hand.group.remove(first);
        PlayerCardTextureResidency.reconcile(player, false, true);
        for (int i = 0; i < 20; i++) tick();
        assertTrue(live.contains(handle));
        player.hand.group.clear();
        PlayerCardTextureResidency.reconcile(player, false, true);
        assertFalse(HandTextureResidency.isProtected(portrait.getRamSaverKey()));
        for (int i = 0; i < 20; i++) tick();
        assertFalse(live.contains(handle));
    }

    @Test public void handLatePortraitAndSamplerChangesAreProtectedWithoutWaiting() throws Exception {
        Texture first = texture("hand-before-field-change.png", 1024);
        Texture next = texture("hand-after-field-change.png", 1024);
        TestCard card = card(first); player.hand.group.add(card);
        PlayerCardTextureResidency.reconcile(player, true, true);
        set(PlayerCardTextureResidency.class, "nextScan", Long.MAX_VALUE);
        card.portrait = new TextureAtlas.AtlasRegion(next, 0, 0, 16, 16);
        PlayerCardTextureResidency.reconcile(player, true, true);
        assertFalse(HandTextureResidency.isProtected(first.getRamSaverKey()));
        next.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        assertTrue("The source protects a newly registered sampler key before any further scan",
                HandTextureResidency.isProtected(next.getRamSaverKey()));
    }

    @Test public void handScopeEndsOnPlayerChangeDeathAndLeavingGameplay() throws Exception {
        Texture portrait = texture("hand-scope.png", 1024);
        player.hand.group.add(card(portrait));
        PlayerCardTextureResidency.reconcile(player, true, true);
        assertTrue(HandTextureResidency.isProtected(portrait.getRamSaverKey()));
        AbstractPlayer other = (AbstractPlayer) unsafe.allocateInstance(Ironclad.class);
        other.hand = group();
        PlayerCardTextureResidency.reconcile(other, true, true);
        assertFalse(HandTextureResidency.isProtected(portrait.getRamSaverKey()));
        PlayerCardTextureResidency.reconcile(player, true, true);
        PlayerCardTextureResidency.reconcile(player, true, false);
        assertFalse(HandTextureResidency.isProtected(portrait.getRamSaverKey()));
        PlayerCardTextureResidency.reconcile(player, true, true);
        CardCrawlGame.mode = CardCrawlGame.GameMode.CHAR_SELECT;
        PlayerCardTextureResidency.UpdatePatch.Postfix();
        assertFalse(HandTextureResidency.isProtected(portrait.getRamSaverKey()));
    }

    @Test public void handCustomUiAndPreviewCyclesAreLazyAndDoNotRetainPopupOrLibraryTextures() throws Exception {
        Texture portrait = texture("hand-custom-portrait.png", 1024);
        Texture background = texture("hand-custom-background.png", 1024);
        Texture orb = texture("hand-custom-orb.png", 1024);
        Texture banner = texture("hand-custom-banner.png", 1024);
        Texture large = texture("hand-custom-popup.png", 1024);
        Texture preview = texture("hand-custom-preview.png", 1024);
        Texture library = texture("hand-custom-library.png", 1024);
        TestCustomCard card = (TestCustomCard) unsafe.allocateInstance(TestCustomCard.class);
        card.portrait = new TextureAtlas.AtlasRegion(portrait, 0, 0, 16, 16);
        card.frameLargeRegion = new TextureAtlas.AtlasRegion(large, 0, 0, 16, 16);
        card.small = new Texture[] {background, orb, banner};
        TestCard previewCard = card(preview);
        card.cardsToPreview = previewCard; previewCard.cardsToPreview = card;
        TestCard.library = library;
        try {
            player.hand.group.add(card);
            RunTextureResidency.updatePressure(Long.MAX_VALUE, 95, 100, 0L);
            PlayerCardTextureResidency.reconcile(player, true, true);
            for (Texture texture : new Texture[] {portrait, background, orb, banner, preview})
                assertTrue(HandTextureResidency.isProtected(texture.getRamSaverKey()));
            assertFalse(HandTextureResidency.isProtected(large.getRamSaverKey()));
            assertFalse(HandTextureResidency.isProtected(library.getRamSaverKey()));
            assertEquals("Collecting the hand must not upload textures", 0, generated);
            assertTrue(((java.util.Set<?>) field(PlayerCardTextureResidency.class, "handSeen")).isEmpty());
        } finally { TestCard.library = null; }
    }

    @Test public void handWithUnknownImageDimensionsIsProtectedBeforeDemandUpload() throws Exception {
        Texture portrait = new Texture(new MemoryFile("hand-unknown.gif", new byte[0]));
        TestCard card = (TestCard) unsafe.allocateInstance(TestCard.class);
        Field field = AbstractCard.class.getDeclaredField("portraitImg");
        field.setAccessible(true); field.set(card, portrait);
        player.hand.group.add(card);
        PlayerCardTextureResidency.reconcile(player, true, true);
        assertEquals(0L, portrait.getRamSaverEstimatedBytes());
        assertTrue(HandTextureResidency.isProtected(portrait.getRamSaverKey()));
        assertEquals(0, generated);
    }

    @Test public void handCoreAtlasPagesRemainProtectedUntilHandBecomesEmpty() throws Exception {
        Map<String, Long> core = (Map<String, Long>) field(PlayerCardTextureResidency.class, "coreTextures");
        Map<String, Long> saved = new LinkedHashMap<>(core);
        Texture ui = texture("hand-core-atlas.png", 2048);
        try {
            core.put(ui.getRamSaverKey(), ui.getRamSaverEstimatedBytes());
            player.hand.group.add(card(texture("hand-with-core.png", 1024)));
            PlayerCardTextureResidency.reconcile(player, true, true);
            Texture real = ui.getRealTexture(); int handle = real.getTextureObjectHandle();
            RunTextureResidency.updatePressure(Long.MAX_VALUE, 95, 100, 0L);
            for (int i = 0; i < 20; i++) tick();
            assertTrue(live.contains(handle));
            player.hand.group.clear();
            PlayerCardTextureResidency.reconcile(player, true, true);
            assertFalse(HandTextureResidency.isProtected(ui.getRamSaverKey()));
            for (int i = 0; i < 20; i++) tick();
            assertFalse(live.contains(handle));
        } finally { core.clear(); core.putAll(saved); }
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
