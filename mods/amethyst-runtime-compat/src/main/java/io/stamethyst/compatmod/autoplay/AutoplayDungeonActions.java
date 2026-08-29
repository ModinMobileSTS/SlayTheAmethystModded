package io.stamethyst.compatmod.autoplay;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.map.DungeonMap;
import com.megacrit.cardcrawl.map.MapEdge;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.MonsterGroup;
import com.megacrit.cardcrawl.neow.NeowEvent;
import com.megacrit.cardcrawl.neow.NeowRoom;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.EventRoom;
import com.megacrit.cardcrawl.rooms.MonsterRoomBoss;
import com.megacrit.cardcrawl.rooms.RestRoom;
import com.megacrit.cardcrawl.screens.DungeonMapScreen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Random;

/**
 * Drives in-dungeon behavior: random card play in combat, end-of-turn, navigate the map,
 * and acknowledge non-combat rooms / rewards so the run keeps advancing.
 *
 * <p>The actual game loop is a sequence of states (CardCrawlGame.mode = GAMEPLAY plus
 * AbstractDungeon.screen for overlays) that we dispatch on:
 * <ul>
 *   <li>{@code COMBAT_REWARD / CARD_REWARD / BOSS_REWARD / COMBAT_REWARD} → press the
 *       overlay proceed button to skip rewards and move on.</li>
 *   <li>{@code MAP} → pick the next available connected node (preferring combat-bearing
 *       paths so the autoplay session actually fights things).</li>
 *   <li>{@code GRID / HAND_SELECT / CARD_REWARD / TRANSFORM / …} → press proceed when
 *       it's safe; these usually self-resolve once the action queue drains.</li>
 *   <li>Combat phase {@code COMBAT} on the current room → play a random playable card,
 *       or call End Turn when nothing remains playable.</li>
 *   <li>Non-combat rooms (event, shop, campfire, neow) → use the proceed button to
 *       advance to the next room.</li>
 * </ul>
 */
final class AutoplayDungeonActions {
    private static final Random RNG = new Random();
    private static final long MAP_SELECTION_RETRY_MS = 1500L;
    private static final int STANDARD_BOSS_ENTRY_ROW = 14;
    private static final int FINAL_ACT_BOSS_ENTRY_ROW = 2;
    private static MapRoomNode pendingMapNode;
    private static long pendingMapSelectionMillis;
    private static boolean pendingBossSelection;
    private static long pendingBossSelectionMillis;
    private static Field neowScreenNumField;
    private static Method neowButtonEffectMethod;

    private AutoplayDungeonActions() {
    }

    static void tick() {
        if (CardCrawlGame.mode != CardCrawlGame.GameMode.GAMEPLAY) {
            return;
        }
        AbstractPlayer player = AbstractDungeon.player;
        if (player == null) {
            return;
        }
        if (AbstractDungeon.isScreenUp) {
            handleOverlayScreen();
            return;
        }
        pendingMapNode = null;
        pendingBossSelection = false;
        AbstractRoom room = safeGetCurrentRoom();
        if (room == null) {
            return;
        }
        if (room.phase == AbstractRoom.RoomPhase.COMBAT) {
            handleCombat(player, room);
            return;
        }
        if (handleNeowRoom(room)) {
            return;
        }
        if (skipOptionalRoomIfNeeded(room)) {
            return;
        }
        // Event / shop / campfire / treasure / neow / monster post-combat → proceed.
        clickProceedButtonIfVisible("non-combat room phase=" + room.phase);
    }

    // region overlay screens (rewards, map, transforms, …)

    private static void handleOverlayScreen() {
        AbstractDungeon.CurrentScreen screen = AbstractDungeon.screen;
        if (screen == null) {
            return;
        }
        if (AutoplayChoiceScreenActions.handleOpenChoiceScreen("normal")) {
            return;
        }
        if (screen == AbstractDungeon.CurrentScreen.MAP) {
            handleMapScreen();
            return;
        }
        pendingMapNode = null;
        pendingBossSelection = false;
        if (screen == AbstractDungeon.CurrentScreen.COMBAT_REWARD
            || screen == AbstractDungeon.CurrentScreen.BOSS_REWARD) {
            clickProceedButtonIfVisible("reward screen " + screen);
            return;
        }
        if (screen == AbstractDungeon.CurrentScreen.CARD_REWARD
            || screen == AbstractDungeon.CurrentScreen.GRID
            || screen == AbstractDungeon.CurrentScreen.HAND_SELECT
            || screen == AbstractDungeon.CurrentScreen.TRANSFORM
            || screen == AbstractDungeon.CurrentScreen.CHOOSE_ONE) {
            // These are "pick from a list" screens; in autoplay we skip the choice by
            // letting the proceed/cancel button auto-dismiss when available.
            clickProceedButtonIfVisible("choice screen " + screen);
            return;
        }
        // SETTINGS / *_VIEW / DEATH / VICTORY etc. — never touch.
    }

    private static void handleMapScreen() {
        DungeonMapScreen mapScreen = AbstractDungeon.dungeonMapScreen;
        if (mapScreen != null && mapScreen.clicked) {
            return; // map already transitioning
        }
        completeSkippedStartupRoomBeforeMapSelection();
        if (isBossSelectionPending()) {
            return;
        }
        if (pendingMapNode != null) {
            if (AbstractDungeon.nextRoom == pendingMapNode
                || currentTimeMillis() - pendingMapSelectionMillis < MAP_SELECTION_RETRY_MS) {
                return;
            }
            pendingMapNode = null;
        }

        ArrayList<ArrayList<MapRoomNode>> map = AbstractDungeon.map;
        if (map == null || map.isEmpty()) {
            return;
        }

        MapRoomNode chosen = pickNextMapNode(map);
        if (chosen == null) {
            if (canEnterBossRoom(map) && enterBossRoom(mapScreen)) {
                pendingBossSelection = true;
                pendingBossSelectionMillis = currentTimeMillis();
                return;
            }
            AutoplayLog.debug("map screen: no candidate node found this tick");
            return;
        }
        if (clickMapNode(chosen)) {
            pendingMapNode = chosen;
            pendingMapSelectionMillis = currentTimeMillis();
        }
    }

    private static boolean isBossSelectionPending() {
        if (!pendingBossSelection) {
            return false;
        }
        if (AbstractDungeon.nextRoom != null
            || currentTimeMillis() - pendingBossSelectionMillis < MAP_SELECTION_RETRY_MS) {
            return true;
        }
        pendingBossSelection = false;
        return false;
    }

    private static MapRoomNode pickNextMapNode(ArrayList<ArrayList<MapRoomNode>> map) {
        MapRoomNode current = safeGetCurrentMapNode();
        // Before the first room is chosen, current is the "synthetic" pre-act-1 node at y=-1.
        // The available row is map.get(0) in that case.
        int targetY = (current == null || !AbstractDungeon.firstRoomChosen)
            ? 0
            : current.y + 1;
        if (targetY < 0 || targetY >= map.size()) {
            return null;
        }
        ArrayList<MapRoomNode> row = map.get(targetY);
        if (row == null || row.isEmpty()) {
            return null;
        }
        ArrayList<MapRoomNode> candidates = new ArrayList<>();
        for (MapRoomNode candidate : row) {
            if (candidate == null) {
                continue;
            }
            if (!isReachableFromCurrent(current, candidate)) {
                continue;
            }
            candidates.add(candidate);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(RNG.nextInt(candidates.size()));
    }

    private static boolean isReachableFromCurrent(MapRoomNode current, MapRoomNode candidate) {
        if (candidate == null) {
            return false;
        }
        if (!AbstractDungeon.firstRoomChosen) {
            // Any node with edges on row 0 is a valid entry into act 1.
            return candidate.hasEdges();
        }
        if (current == null) {
            return false;
        }
        if (!current.isConnectedTo(candidate)) {
            return false;
        }
        return true;
    }

    private static boolean canEnterBossRoom(ArrayList<ArrayList<MapRoomNode>> map) {
        MapRoomNode current = safeGetCurrentMapNode();
        if (current == null) {
            return false;
        }
        AbstractRoom room = safeGetCurrentRoom();
        if (room == null || room.phase != AbstractRoom.RoomPhase.COMPLETE) {
            return false;
        }
        if ("TheEnding".equals(AbstractDungeon.id)) {
            return current.y == FINAL_ACT_BOSS_ENTRY_ROW;
        }
        return current.y == STANDARD_BOSS_ENTRY_ROW
            || (map != null && !map.isEmpty() && current.y >= map.size() - 1);
    }

    private static boolean clickMapNode(MapRoomNode node) {
        if (node == null) {
            return false;
        }
        try {
            DungeonMapScreen mapScreen = AbstractDungeon.dungeonMapScreen;
            if (mapScreen == null || node.hb == null) {
                AutoplayLog.warn("map node hb missing for node x=" + node.x + " y=" + node.y, null);
                return false;
            }

            // MapRoomNode does not consume Hitbox.clicked. Its native selection path checks
            // whether the node is hovered while DungeonMapScreen.clicked is true.
            mapScreen.clicked = false;
            node.update();
            InputHelper.mX = Math.round(node.hb.cX);
            InputHelper.mY = Math.round(node.hb.cY);
            mapScreen.clicked = true;
            node.update();
            if (mapScreen.clicked) {
                mapScreen.clicked = false;
                AutoplayLog.warn(
                    "map: node selection was not consumed x=" + node.x
                        + " y=" + node.y
                        + " state=" + describeMapState(node),
                    null
                );
                return selectMapNodeDirectly(node, "node-click-fallback");
            }
            AutoplayLog.info(
                "map: selected node x=" + node.x
                    + " y=" + node.y
                    + " firstRoomChosen=" + AbstractDungeon.firstRoomChosen
                    + " hb=(" + Math.round(node.hb.cX) + "," + Math.round(node.hb.cY) + ")"
            );
            return true;
        } catch (Throwable t) {
            AutoplayLog.warn("map: failed to click node", t);
            return false;
        }
    }

    private static boolean enterBossRoom(DungeonMapScreen mapScreen) {
        if (clickBossNode(mapScreen)) {
            return true;
        }
        return selectBossRoomDirectly("boss-click-fallback");
    }

    private static boolean clickBossNode(DungeonMapScreen mapScreen) {
        try {
            if (mapScreen == null || mapScreen.map == null || mapScreen.map.bossHb == null) {
                AutoplayLog.warn("map boss hb missing", null);
                return false;
            }

            DungeonMap dungeonMap = mapScreen.map;
            InputHelper.justClickedLeft = false;
            mapScreen.clicked = false;
            dungeonMap.update();

            Hitbox hb = dungeonMap.bossHb;
            InputHelper.mX = Math.round(hb.cX);
            InputHelper.mY = Math.round(hb.cY);
            InputHelper.justClickedLeft = true;
            dungeonMap.update();

            if (AbstractDungeon.nextRoom == null
                || !(AbstractDungeon.nextRoom.room instanceof MonsterRoomBoss)) {
                InputHelper.justClickedLeft = false;
                AutoplayLog.warn(
                    "map: boss selection was not consumed current="
                        + describeMapNode(safeGetCurrentMapNode())
                        + " state=" + describeMapState(null)
                        + " hb=(" + Math.round(hb.cX) + "," + Math.round(hb.cY) + ")",
                    null
                );
                return false;
            }

            AutoplayLog.info(
                "map: selected boss node current="
                    + describeMapNode(safeGetCurrentMapNode())
                    + " next=" + describeMapNode(AbstractDungeon.nextRoom)
                    + " hb=(" + Math.round(hb.cX) + "," + Math.round(hb.cY) + ")"
            );
            return true;
        } catch (Throwable t) {
            InputHelper.justClickedLeft = false;
            AutoplayLog.warn("map: failed to click boss node", t);
            return false;
        }
    }

    private static boolean selectMapNodeDirectly(MapRoomNode node, String reason) {
        try {
            if (!canSelectMapNodeDirectly(node)) {
                AutoplayLog.warn(
                    "map: direct node selection rejected x="
                        + (node == null ? "<null>" : node.x)
                        + " y=" + (node == null ? "<null>" : node.y)
                        + " reason=" + reason
                        + " state=" + describeMapState(node),
                    null
                );
                return false;
            }

            DungeonMapScreen mapScreen = AbstractDungeon.dungeonMapScreen;
            MapRoomNode current = safeGetCurrentMapNode();
            boolean wasFirstRoom = !AbstractDungeon.firstRoomChosen && node.y == 0;

            if (mapScreen != null) {
                mapScreen.clicked = false;
                mapScreen.clickTimer = 0.0F;
                if (wasFirstRoom) {
                    mapScreen.dismissable = true;
                }
            }
            if (wasFirstRoom) {
                AbstractDungeon.firstRoomChosen = true;
            }
            markCurrentPathTaken(current, node);
            AbstractDungeon.nextRoom = node;
            addMapPathEntry(node);
            addMetricPathTaken(node);
            startSelectedRoomTransition();

            AutoplayLog.info(
                "map: selected node direct x=" + node.x
                    + " y=" + node.y
                    + " reason=" + reason
                    + " firstRoom=" + wasFirstRoom
                    + " current=" + describeMapNode(current)
                    + " next=" + describeMapNode(AbstractDungeon.nextRoom)
            );
            return true;
        } catch (Throwable t) {
            AutoplayLog.warn("map: direct node selection failed", t);
            return false;
        }
    }

    private static boolean canSelectMapNodeDirectly(MapRoomNode node) {
        if (node == null || node.room == null) {
            return false;
        }
        if (AbstractDungeon.nextRoom != null) {
            return false;
        }
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.MAP) {
            return false;
        }
        AbstractRoom room = safeGetCurrentRoom();
        if (!isRoomCompleteForMapSelection(room, node)) {
            return false;
        }
        MapRoomNode current = safeGetCurrentMapNode();
        if (!AbstractDungeon.firstRoomChosen && node.y == 0) {
            return node.hasEdges();
        }
        return current != null && current.isConnectedTo(node);
    }

    private static void completeSkippedStartupRoomBeforeMapSelection() {
        AbstractRoom room = safeGetCurrentRoom();
        if (room instanceof NeowRoom
            && room.phase != AbstractRoom.RoomPhase.COMPLETE
            && !AbstractDungeon.firstRoomChosen
            && AbstractDungeon.screen == AbstractDungeon.CurrentScreen.MAP) {
            room.phase = AbstractRoom.RoomPhase.COMPLETE;
            AbstractRoom.waitTimer = 0.0F;
            AutoplayLog.info("map: completed skipped neow room before first-node selection");
        }
    }

    private static boolean isRoomCompleteForMapSelection(AbstractRoom room, MapRoomNode node) {
        if (room == null) {
            return false;
        }
        if (room.phase == AbstractRoom.RoomPhase.COMPLETE) {
            return true;
        }
        return room instanceof NeowRoom
            && !AbstractDungeon.firstRoomChosen
            && node != null
            && node.y == 0;
    }

    private static boolean selectBossRoomDirectly(String reason) {
        try {
            if (!canSelectBossRoomDirectly()) {
                AutoplayLog.warn(
                    "map: direct boss selection rejected reason="
                        + reason
                        + " state=" + describeMapState(null),
                    null
                );
                return false;
            }

            MapRoomNode current = safeGetCurrentMapNode();
            markAllCurrentEdgesTaken(current);
            InputHelper.justClickedLeft = false;
            fadeOutTempBgmIfPossible();

            MapRoomNode bossNode = new MapRoomNode(-1, 15);
            bossNode.room = new MonsterRoomBoss();
            AbstractDungeon.nextRoom = bossNode;
            addBossPathEntry();
            AbstractDungeon.nextRoomTransitionStart();

            DungeonMapScreen mapScreen = AbstractDungeon.dungeonMapScreen;
            if (mapScreen != null && mapScreen.map != null && mapScreen.map.bossHb != null) {
                mapScreen.map.bossHb.hovered = false;
            }

            AutoplayLog.info(
                "map: selected boss node direct reason=" + reason
                    + " current=" + describeMapNode(current)
                    + " next=" + describeMapNode(AbstractDungeon.nextRoom)
            );
            return true;
        } catch (Throwable t) {
            AutoplayLog.warn("map: direct boss selection failed", t);
            return false;
        }
    }

    private static boolean canSelectBossRoomDirectly() {
        if (AbstractDungeon.nextRoom != null) {
            return false;
        }
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.MAP) {
            return false;
        }
        AbstractRoom room = safeGetCurrentRoom();
        if (room == null || room.phase != AbstractRoom.RoomPhase.COMPLETE) {
            return false;
        }
        MapRoomNode current = safeGetCurrentMapNode();
        if (current == null) {
            return false;
        }
        if ("TheEnding".equals(AbstractDungeon.id)) {
            return current.y == FINAL_ACT_BOSS_ENTRY_ROW;
        }
        return current.y == STANDARD_BOSS_ENTRY_ROW;
    }

    private static void markCurrentPathTaken(MapRoomNode current, MapRoomNode next) {
        if (current == null) {
            return;
        }
        current.taken = true;
        if (next == null) {
            return;
        }
        MapEdge connectedEdge = current.getEdgeConnectedTo(next);
        if (connectedEdge != null) {
            connectedEdge.markAsTaken();
        }
    }

    private static void markAllCurrentEdgesTaken(MapRoomNode current) {
        if (current == null) {
            return;
        }
        current.taken = true;
        if (current.getEdges() == null) {
            return;
        }
        for (MapEdge edge : current.getEdges()) {
            if (edge != null) {
                edge.markAsTaken();
            }
        }
    }

    private static void addMapPathEntry(MapRoomNode node) {
        if (node == null || AbstractDungeon.pathX == null || AbstractDungeon.pathY == null) {
            return;
        }
        AbstractDungeon.pathX.add(node.x);
        AbstractDungeon.pathY.add(node.y);
    }

    private static void addBossPathEntry() {
        if (AbstractDungeon.pathX == null || AbstractDungeon.pathY == null) {
            return;
        }
        if (AbstractDungeon.pathY.size() > 1 && !AbstractDungeon.pathX.isEmpty()) {
            AbstractDungeon.pathX.add(AbstractDungeon.pathX.get(AbstractDungeon.pathX.size() - 1));
            AbstractDungeon.pathY.add(AbstractDungeon.pathY.get(AbstractDungeon.pathY.size() - 1) + 1);
            return;
        }
        AbstractDungeon.pathX.add(1);
        AbstractDungeon.pathY.add(15);
    }

    private static void addMetricPathTaken(MapRoomNode node) {
        if (node == null || node.room == null || CardCrawlGame.metricData == null) {
            return;
        }
        CardCrawlGame.metricData.path_taken.add(node.room.getMapSymbol());
    }

    private static void startSelectedRoomTransition() {
        if (AbstractDungeon.isDungeonBeaten) {
            return;
        }
        AbstractDungeon.nextRoomTransitionStart();
        fadeOutTempBgmIfPossible();
    }

    private static void fadeOutTempBgmIfPossible() {
        if (CardCrawlGame.music != null) {
            CardCrawlGame.music.fadeOutTempBGM();
        }
    }

    private static String describeMapNode(MapRoomNode node) {
        if (node == null) {
            return "<null>";
        }
        return "(" + node.x + "," + node.y + ")";
    }

    private static String describeMapState(MapRoomNode candidate) {
        AbstractRoom room = safeGetCurrentRoom();
        MapRoomNode current = safeGetCurrentMapNode();
        Hitbox hb = candidate == null ? null : candidate.hb;
        return "screen=" + AbstractDungeon.screen
            + " screenUp=" + AbstractDungeon.isScreenUp
            + " fadingOut=" + AbstractDungeon.isFadingOut
            + " roomPhase=" + (room == null ? "<null>" : room.phase)
            + " firstRoomChosen=" + AbstractDungeon.firstRoomChosen
            + " current=" + describeMapNode(current)
            + " candidate=" + describeMapNode(candidate)
            + " connected=" + (current != null && candidate != null && current.isConnectedTo(candidate))
            + " nextRoom=" + describeMapNode(AbstractDungeon.nextRoom)
            + " clicked=" + (AbstractDungeon.dungeonMapScreen != null && AbstractDungeon.dungeonMapScreen.clicked)
            + " hb=" + describeHitbox(hb)
            + " input=(" + InputHelper.mX + "," + InputHelper.mY + ")";
    }

    private static String describeHitbox(Hitbox hb) {
        if (hb == null) {
            return "<null>";
        }
        return "("
            + Math.round(hb.x)
            + ","
            + Math.round(hb.y)
            + ","
            + Math.round(hb.cX)
            + ","
            + Math.round(hb.cY)
            + ",hovered="
            + hb.hovered
            + ",justHovered="
            + hb.justHovered
            + ")";
    }

    private static long currentTimeMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    private static AbstractRoom safeGetCurrentRoom() {
        try {
            return AbstractDungeon.getCurrRoom();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static MapRoomNode safeGetCurrentMapNode() {
        try {
            return AbstractDungeon.getCurrMapNode();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    // endregion

    // region optional room skipping

    private static boolean skipOptionalRoomIfNeeded(AbstractRoom room) {
        if (room instanceof RestRoom && room.phase != AbstractRoom.RoomPhase.COMPLETE) {
            completeOptionalRoom(room, "campfire");
            return true;
        }
        if (room instanceof EventRoom && room.phase == AbstractRoom.RoomPhase.EVENT) {
            EventRoom eventRoom = (EventRoom) room;
            if (eventRoom.event != null && eventRoom.event.combatTime) {
                return false;
            }
            completeOptionalRoom(room, "event");
            return true;
        }
        return false;
    }

    private static boolean handleNeowRoom(AbstractRoom room) {
        if (!(room instanceof NeowRoom) || AbstractDungeon.firstRoomChosen) {
            return false;
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.MAP) {
            return false;
        }
        if (!(room.event instanceof NeowEvent)) {
            AutoplayLog.warn("neow: room has no NeowEvent", null);
            return true;
        }

        try {
            NeowEvent event = (NeowEvent) room.event;
            Field screenField = getNeowScreenNumField();
            Method buttonMethod = getNeowButtonEffectMethod();
            int before = screenField.getInt(event);
            buttonMethod.invoke(event, 0);
            int after = screenField.getInt(event);
            AutoplayLog.info(
                "neow: advanced native dialog option=0 screen=" + before + "->" + after
            );
        } catch (Throwable t) {
            AutoplayLog.warn("neow: failed to advance native dialog", t);
        }
        return true;
    }

    private static Field getNeowScreenNumField() throws Exception {
        if (neowScreenNumField == null) {
            neowScreenNumField = NeowEvent.class.getDeclaredField("screenNum");
            neowScreenNumField.setAccessible(true);
        }
        return neowScreenNumField;
    }

    private static Method getNeowButtonEffectMethod() throws Exception {
        if (neowButtonEffectMethod == null) {
            neowButtonEffectMethod = NeowEvent.class.getDeclaredMethod(
                "buttonEffect", int.class);
            neowButtonEffectMethod.setAccessible(true);
        }
        return neowButtonEffectMethod;
    }

    private static void completeOptionalRoom(AbstractRoom room, String roomKind) {
        try {
            AbstractRoom.waitTimer = 0.0F;
            room.phase = AbstractRoom.RoomPhase.COMPLETE;
            showProceedButtonIfPossible();
            AutoplayLog.info("room: skipped " + roomKind + " room");
            clickProceedButtonIfVisible("skipped " + roomKind + " room");
        } catch (Throwable t) {
            AutoplayLog.warn("room: failed to skip " + roomKind + " room", t);
        }
    }

    private static void showProceedButtonIfPossible() {
        if (AbstractDungeon.overlayMenu == null || AbstractDungeon.overlayMenu.proceedButton == null) {
            return;
        }
        AbstractDungeon.overlayMenu.proceedButton.show();
    }

    // region combat

    private static boolean _testCrashInjected = false;

    private static void handleCombat(AbstractPlayer player, AbstractRoom room) {
        MonsterGroup monsters = room.monsters;
        if (monsters == null || monsters.areMonstersBasicallyDead()) {
            return; // engine is about to flip the room to COMPLETE; just wait
        }
        GameActionManager actionManager = AbstractDungeon.actionManager;
        if (actionManager == null) {
            return;
        }
        if (!actionManager.actions.isEmpty()
            || !actionManager.cardQueue.isEmpty()
            || actionManager.currentAction != null) {
            return; // an action chain is running; let it finish
        }
        // Inject test crash cards on first combat when debug property is set
        if (!_testCrashInjected && Boolean.getBoolean("amethyst.autoplay.inject_test_crash")) {
            _testCrashInjected = true;
            try {
                AbstractCard permCard = (AbstractCard) Class.forName("io.stamethyst.testcrash.cards.CrashPermissionCard").newInstance();
                AbstractCard crashCard = (AbstractCard) Class.forName("io.stamethyst.testcrash.cards.TestCrashCard").newInstance();
                permCard.upgrade();
                crashCard.upgrade();
                crashCard.costForTurn = 0;
                crashCard.cost = 0;
                player.hand.addToBottom(crashCard);
                player.hand.addToBottom(permCard);
                AutoplayLog.info("combat: injected test crash cards");
            } catch (Exception e) {
                AutoplayLog.warn("combat: failed to inject test crash cards", e);
            }
        }
        if (actionManager.turnHasEnded || !actionManager.phase.equals(
            GameActionManager.Phase.WAITING_ON_USER)) {
            return;
        }
        if (player.hand == null) {
            return;
        }

        AbstractCard playable = pickRandomPlayableCard(player, monsters);
        if (playable != null) {
            playCard(player, playable, monsters);
            return;
        }
        endTurn(actionManager);
    }

    private static AbstractCard pickRandomPlayableCard(
        AbstractPlayer player,
        MonsterGroup monsters
    ) {
        CardGroup hand = player.hand;
        if (hand == null || hand.group == null || hand.group.isEmpty()) {
            return null;
        }
        ArrayList<AbstractCard> playable = new ArrayList<>();
        for (AbstractCard card : hand.group) {
            if (card == null) {
                continue;
            }
            AbstractMonster sampleTarget = pickRandomAliveMonster(monsters);
            // canUse already null-checks sampleTarget for non-targeted cards.
            try {
                if (card.canUse(player, sampleTarget)) {
                    playable.add(card);
                }
            } catch (Throwable t) {
                AutoplayLog.debug(
                    "card.canUse threw card=" + card.cardID
                        + " reason=" + t.getClass().getSimpleName()
                );
            }
        }
        if (playable.isEmpty()) {
            return null;
        }
        return playable.get(RNG.nextInt(playable.size()));
    }

    private static void playCard(
        AbstractPlayer player,
        AbstractCard card,
        MonsterGroup monsters
    ) {
        AbstractMonster target = null;
        if (cardNeedsSingleTarget(card)) {
            target = pickRandomAliveMonster(monsters);
            if (target == null) {
                AutoplayLog.debug("card needs target but no monster alive card=" + card.cardID);
                return;
            }
        }
        int energy = 0;
        try {
            energy = com.megacrit.cardcrawl.ui.panels.EnergyPanel.totalCount;
        } catch (Throwable ignored) {
            // fall through with energy=0; useCard will gate on real energy values
        }
        try {
            player.useCard(card, target, energy);
            AutoplayLog.info(
                "combat: played card=" + card.cardID
                    + " target=" + (target == null ? "<none>" : target.id)
            );
        } catch (Throwable t) {
            AutoplayLog.warn("combat: useCard threw card=" + card.cardID, t);
        }
    }

    private static boolean cardNeedsSingleTarget(AbstractCard card) {
        if (card == null) {
            return false;
        }
        return card.target == AbstractCard.CardTarget.ENEMY
            || card.target == AbstractCard.CardTarget.SELF_AND_ENEMY;
    }

    private static AbstractMonster pickRandomAliveMonster(MonsterGroup monsters) {
        if (monsters == null || monsters.monsters == null) {
            return null;
        }
        ArrayList<AbstractMonster> alive = new ArrayList<>();
        for (AbstractMonster m : monsters.monsters) {
            if (m == null) {
                continue;
            }
            if (m.isDying || m.isDead || m.escaped || m.halfDead) {
                continue;
            }
            if (m.currentHealth <= 0) {
                continue;
            }
            alive.add(m);
        }
        if (alive.isEmpty()) {
            return null;
        }
        return alive.get(RNG.nextInt(alive.size()));
    }

    private static void endTurn(GameActionManager actionManager) {
        try {
            actionManager.callEndTurnEarlySequence();
            AutoplayLog.info("combat: ended turn (no playable card)");
        } catch (Throwable t) {
            AutoplayLog.warn("combat: callEndTurnEarlySequence threw", t);
        }
    }

    // endregion

    // region shared helpers

    private static void clickProceedButtonIfVisible(String reason) {
        if (AbstractDungeon.overlayMenu == null || AbstractDungeon.overlayMenu.proceedButton == null) {
            return;
        }
        com.megacrit.cardcrawl.ui.buttons.ProceedButton button =
            AbstractDungeon.overlayMenu.proceedButton;
        com.megacrit.cardcrawl.helpers.Hitbox hb = readHitbox(button);
        if (hb == null) {
            return;
        }
        if (hb.clicked) {
            return;
        }
        hb.clicked = true;
        AutoplayLog.info("proceed: pressed (" + reason + ")");
    }

    private static com.megacrit.cardcrawl.helpers.Hitbox readHitbox(Object target) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField("hb");
            f.setAccessible(true);
            return (com.megacrit.cardcrawl.helpers.Hitbox) f.get(target);
        } catch (Throwable t) {
            AutoplayLog.debug(
                "could not access hb on " + target.getClass().getSimpleName()
                    + " reason=" + t.getClass().getSimpleName()
            );
            return null;
        }
    }

    // endregion
}
