package io.stamethyst.frameprobe;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Render-thread-writable snapshot of the current game state.
 *
 * Written by SpirePatch hooks on the game thread (which IS the render thread
 * in StS / LibGDX). Read by the drain loop inside the same thread.
 * No synchronization needed.
 */
public final class GameContext {

    public static final GameContext INSTANCE = new GameContext();

    private GameContext() {}

    // ── current room / dungeon ───────────────────────────────────────────────
    public volatile String roomClass   = "unknown";
    public volatile int    floorNumber = 0;
    public volatile int    actNumber   = 0;

    // ── last card played ─────────────────────────────────────────────────────
    public volatile String lastCardId    = null;
    /** frameId when the last card was played. -1 = none yet. */
    public volatile long   lastCardFrame = -1L;

    // ── last AbstractGameAction started ─────────────────────────────────────
    public volatile String lastActionClass = null;
    /** frameId when the last action started. */
    public volatile long   lastActionFrame = -1L;

    /** Tag string for active event (e.g. "card:Strike_R", "room:MonsterRoom"). */
    public volatile String activeTag = null;
    public volatile long   activeTagFrame = -1L;

    // ── helpers ──────────────────────────────────────────────────────────────

    public void onCardPlayed(String cardId, long frameId) {
        lastCardId    = cardId;
        lastCardFrame = frameId;
        activeTag     = "card:" + cardId;
        activeTagFrame = frameId;
    }

    public void onRoomEntered(String roomSimpleName, int floor, int act) {
        roomClass   = roomSimpleName;
        floorNumber = floor;
        actNumber   = act;
        activeTag   = "room:" + roomSimpleName;
        activeTagFrame = -1L;
    }

    public void onActionStarted(String actionSimpleName, long frameId) {
        lastActionClass = actionSimpleName;
        lastActionFrame = frameId;
    }

    /**
     * Builds a compact context suffix for a JSONL incident line.
     * Example: {@code "room":"MonsterRoom","floor":3,"act":1,"tag":"card:Strike_R"}
     */
    public String toJsonFragment() {
        StringBuilder sb = new StringBuilder();
        sb.append("\"room\":\"").append(escape(roomClass)).append('"');
        sb.append(",\"floor\":").append(floorNumber);
        sb.append(",\"act\":").append(actNumber);
        if (activeTag != null) {
            sb.append(",\"tag\":\"").append(escape(activeTag)).append('"');
        }
        if (lastActionClass != null) {
            sb.append(",\"action\":\"").append(escape(lastActionClass)).append('"');
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
