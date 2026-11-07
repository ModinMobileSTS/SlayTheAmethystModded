package optispire.patches;

import basemod.abstracts.CustomCard;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.actions.common.DrawCardAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import optispire.RamSaver;
import optispire.RamSaverDiag;
import optispire.RunTextureResidency;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Own only resources reachable from this run's cards, never BaseMod's global image library. */
public final class PlayerCardTextureResidency {
    private static final long RESCAN_NANOS = 500_000_000L;
    private static final boolean BASEMOD_AVAILABLE = baseModAvailable();
    private static final Map<Class<?>, List<Field>> TEXTURE_FIELDS = new HashMap<>();
    private static final LinkedHashMap<String, Long> candidates = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Long> drawTextures = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Long> upcomingTextures = new LinkedHashMap<>();
    private static DrawCardAction lastDrawAction;
    private static DrawCardAction waitingDraw;
    private static long drawWaitStarted;
    private static int drawWaitUpdates;
    private static AbstractPlayer owner;
    private static long nextScan;
    private static boolean dirty = true;
    private static boolean lastCombat;
    private static boolean coreRegistered;
    private static final LinkedHashMap<String, Long> coreTextures = new LinkedHashMap<>();

    private PlayerCardTextureResidency() { }

    @SpirePatch2(clz = CardCrawlGame.class, method = "update")
    public static class UpdatePatch {
        public static void Postfix() { update(); }
    }

    @SpirePatch2(clz = CardCrawlGame.class, method = "startOver")
    public static class EndRunPatch {
        public static void Prefix() { clear(); }
    }

    @SpirePatch2(clz = AbstractPlayer.class, method = "preBattlePrep")
    public static class BattleDeckPatch {
        public static void Postfix() {
            dirty = true;
            refresh();
        }
    }

    @SpirePatch2(clz = CardGroup.class, method = "addToTop")
    @SpirePatch2(clz = CardGroup.class, method = "addToBottom")
    @SpirePatch2(clz = CardGroup.class, method = "addToHand")
    @SpirePatch2(clz = CardGroup.class, method = "addToRandomSpot")
    @SpirePatch2(clz = CardGroup.class, method = "clear")
    @SpirePatch2(clz = CardGroup.class, method = "removeCard", paramtypez = {AbstractCard.class})
    public static class DeckChangedPatch {
        public static void Postfix(CardGroup __instance) {
            if (CardCrawlGame.mode != CardCrawlGame.GameMode.GAMEPLAY) return;
            AbstractPlayer player = AbstractDungeon.player;
            markDirty(__instance, player);
        }
    }

    static void markDirty(CardGroup group, AbstractPlayer player) {
        if (group != null && player != null && (group == player.masterDeck || group == player.hand
                || group == player.drawPile || group == player.discardPile
                || group == player.exhaustPile || group == player.limbo)) dirty = true;
    }

    /** Pause the action clock, not the render loop, while admitted missing cards are warmed. */
    @SpirePatch2(clz = DrawCardAction.class, method = "update")
    public static class BeforeDrawPatch {
        public static SpireReturn<Void> Prefix(DrawCardAction __instance) {
            if (lastDrawAction != __instance) {
                lastDrawAction = __instance;
                dirty = true; // Also catch mods that directly appended to CardGroup.group.
            }
            refresh();
            return deferDraw(__instance, owner)
                    ? SpireReturn.Return(null) : SpireReturn.Continue();
        }
    }

    static boolean deferDraw(DrawCardAction action, AbstractPlayer player) {
        if (waitingDraw != action) {
            waitingDraw = action;
            drawWaitStarted = System.nanoTime();
            drawWaitUpdates = 0;
        }
        if (owner == null || player != owner || action.isDone || action.amount <= 0
                || player.hasPower("No Draw") || player.hand.size() >= 10) return false;
        // Backpressure or slow I/O must not hold the draw action indefinitely. Demand still loads safely.
        if (++drawWaitUpdates > 120 || System.nanoTime() - drawWaitStarted >= 500_000_000L) return false;
        if (!RunTextureResidency.hasPending(null)) return false;
        upcomingTextures.clear();
        upcomingTextures.putAll(coreTextures);
        Set<AbstractCard> seen = Collections.newSetFromMap(new IdentityHashMap<AbstractCard, Boolean>());
        int count = Math.min(action.amount, 10 - player.hand.size());
        // CardGroup's top is the final element. Do not warm an entire large deck just to draw one card.
        for (int i = player.drawPile.size() - 1; i >= 0 && count > 0; i--, count--) {
            collectCard(player.drawPile.group.get(i), upcomingTextures, seen, 0);
        }
        // Before a shuffle any discard can be drawn, but residency budget still bounds the work.
        if (count > 0) collectGroup(player.discardPile, upcomingTextures, seen);
        return deferDraw(upcomingTextures);
    }

    private static boolean deferDraw(Map<String, Long> textures) {
        if (!RunTextureResidency.hasPending(textures.keySet())) return false;
        if (FirstCombatPrewarmBudget.tryTextureStep()) RunTextureResidency.prewarmOne(textures.keySet());
        // Leave the upload frame free of newly started draw animation work, including the last step.
        return true;
    }

    private static void update() {
        RunTextureResidency.samplePressure();
        refresh();
        if (owner == null || Gdx.gl == null) return;
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        // Keep speculative uploads out of active card animations; decode itself is CPU-only.
        if ((room == null || room.phase != AbstractRoom.RoomPhase.COMBAT)
                && RunTextureResidency.hasPending(null) && FirstCombatPrewarmBudget.tryTextureStep()) {
            RunTextureResidency.prewarmOne(null);
        }
    }

    private static void refresh() {
        // Do not initialize AbstractDungeon (and all its GL-backed screens) during splash/card edits.
        if (CardCrawlGame.mode != CardCrawlGame.GameMode.GAMEPLAY) {
            reconcile(null, false, false);
            return;
        }
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        reconcile(AbstractDungeon.player, room != null && room.phase == AbstractRoom.RoomPhase.COMBAT,
                AbstractDungeon.screen != AbstractDungeon.CurrentScreen.DEATH
                        && AbstractDungeon.screen != AbstractDungeon.CurrentScreen.VICTORY);
    }

    static void reconcile(AbstractPlayer player, boolean combat, boolean active) {
        if (!RunTextureResidency.enabled() || !active || player == null) {
            if (owner != null) clear();
            return;
        }
        long now = System.nanoTime();
        if (owner != player) {
            clear();
            owner = player;
        }
        dirty |= combat != lastCombat;
        if (!dirty && now < nextScan) return;
        dirty = false;
        lastCombat = combat;
        nextScan = now + RESCAN_NANOS;
        candidates.clear();
        registerCoreTextures();
        candidates.putAll(coreTextures);
        drawTextures.clear();
        drawTextures.putAll(coreTextures);
        Set<AbstractCard> seen = Collections.newSetFromMap(new IdentityHashMap<AbstractCard, Boolean>());
        if (combat) {
            // The last element is drawn first. Reserve scarce bytes for the actual next cards.
            for (int i = player.drawPile.size() - 1; i >= 0; i--) {
                collectCard(player.drawPile.group.get(i), drawTextures, seen, 0);
            }
            // A draw may shuffle the discard pile before taking its next card.
            collectGroup(player.discardPile, drawTextures, seen);
            seen.clear();
            collectGroup(player.hand, candidates, seen);
            collectCard(player.cardInUse, candidates, seen, 0);
            collectGroup(player.limbo, candidates, seen);
            candidates.putAll(drawTextures);
        }
        collectGroup(player.masterDeck, candidates, seen);
        if (combat) {
            collectGroup(player.exhaustPile, candidates, seen);
            collectGroup(player.limbo, candidates, seen);
            collectCard(player.cardInUse, candidates, seen, 0);
        }
        RunTextureResidency.replaceCandidates(candidates);
    }

    private static void registerCoreTextures() {
        if (coreRegistered || Gdx.files == null) return;
        coreRegistered = true;
        for (String path : new String[] {"cardui/cardui.png", "cardui/cardui2.png",
                "cardui/cardui3.png", "cardui/cardui4.png", "orbs/orb.png"}) {
            try {
                String atlas = path.startsWith("orbs/") ? "orbs/orb.atlas" : "cardui/cardui.atlas";
                String key = PrewarmTextureRegistry.register(path, atlas);
                int[] size = RamSaver.getCachedTextureSize(key);
                if (key != null && size != null) coreTextures.put(key, estimate(key, size[0], size[1]));
            } catch (RuntimeException error) {
                RamSaverDiag.logRepeat("run_core_texture_failed", path, error.toString());
            }
        }
    }

    private static void collectGroup(CardGroup group, Map<String, Long> target, Set<AbstractCard> seen) {
        if (group != null && group.group != null) {
            for (AbstractCard card : group.group) collectCard(card, target, seen, 0);
        }
    }

    private static void collectCard(AbstractCard card, Map<String, Long> target, Set<AbstractCard> seen, int depth) {
        if (card == null || !seen.add(card)) return;
        collectFields(card, target);
        if (BASEMOD_AVAILABLE) BaseModSmallUi.collect(card, target);
        // Mods can form preview cycles. The identity guard also deduplicates shared card instances.
        if (depth < 8) collectCard(card.cardsToPreview, target, seen, depth + 1);
    }

    /** Instance Texture/TextureRegion fields only: no static caches and no arbitrary mod getters. */
    static void collectFields(Object card, Map<String, Long> target) {
        List<Field> fields = TEXTURE_FIELDS.get(card.getClass());
        if (fields == null) {
            fields = new ArrayList<>();
            for (Class<?> type = card.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (type.getName().equals("basemod.abstracts.CustomCard") && field.getName().contains("Large")) continue;
                    if (!Modifier.isStatic(field.getModifiers()) && (Texture.class.isAssignableFrom(field.getType())
                            || TextureRegion.class.isAssignableFrom(field.getType()))) {
                        field.setAccessible(true);
                        fields.add(field);
                    }
                }
            }
            TEXTURE_FIELDS.put(card.getClass(), fields);
        }
        for (Field field : fields) {
            try {
                Object value = field.get(card);
                collectTexture(value instanceof TextureRegion ? ((TextureRegion) value).getTexture()
                        : (Texture) value, target);
            } catch (IllegalAccessException | RuntimeException error) {
                RamSaverDiag.logRepeat("run_card_field_failed", field.getName(), error.toString());
            }
        }
    }

    private static void collectTexture(Texture texture, Map<String, Long> target) {
        if (texture == null || texture.getRamSaverKey() == null) return;
        String key = texture.getRamSaverKey();
        if (target.containsKey(key)) return;
        // PNG/JPEG/header metadata only: collecting candidates must not materialize an image.
        target.put(key, texture.getRamSaverEstimatedBytes());
    }

    private static long estimate(String key, int width, int height) {
        long bytes = (long) width * height * 4L;
        RamSaver.FileTextureSupplier supplier = RamSaver.getTextureSupplier(key);
        // Cache keys include mipmap policy, so variants are charged independently.
        return supplier != null && supplier.usesMipMaps() ? bytes + bytes / 3L : bytes;
    }

    private static boolean baseModAvailable() {
        try {
            Class.forName("basemod.abstracts.CustomCard", false, PlayerCardTextureResidency.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    /** Keep the optional BaseMod class reference out of the vanilla card tracking path. */
    private static final class BaseModSmallUi {
        static void collect(AbstractCard card, Map<String, Long> target) {
            if (!(card instanceof CustomCard)) return;
            CustomCard custom = (CustomCard) card;
            // These are the small, combat-visible variants; never request the 500x380 portrait.
            try {
                collectTexture(custom.getBackgroundSmallTexture(), target);
                collectTexture(custom.getOrbSmallTexture(), target);
                collectTexture(custom.getBannerSmallTexture(), target);
            } catch (RuntimeException error) {
                RamSaverDiag.logRepeat("run_card_ui_failed", card.cardID, error.toString());
            }
        }
    }

    private static void clear() {
        RunTextureResidency.clear();
        candidates.clear();
        drawTextures.clear();
        upcomingTextures.clear();
        lastDrawAction = null;
        waitingDraw = null;
        owner = null;
        nextScan = 0L;
        dirty = true;
        lastCombat = false;
    }
}
