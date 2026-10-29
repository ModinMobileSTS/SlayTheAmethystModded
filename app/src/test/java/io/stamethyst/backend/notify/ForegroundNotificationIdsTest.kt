package io.stamethyst.backend.notify

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundNotificationIdsTest {
    /**
     * `NotificationManager` keys a notification by id inside the package, so a duplicate silently
     * replaces the other service's entry: its text, icon, channel and actions disappear while the
     * service keeps running in the foreground. That is exactly how a live AI agent run could show
     * the Steam Cloud sync notification instead of its own.
     */
    @Test
    fun everyForegroundServiceUsesADistinctNotificationId() {
        val ids = listOf(
            "workshop download" to ForegroundNotificationIds.WORKSHOP_DOWNLOAD,
            "steam cloud sync" to ForegroundNotificationIds.STEAM_CLOUD_SYNC,
            "steam game presence" to ForegroundNotificationIds.STEAM_GAME_PRESENCE,
            "easytier connect" to ForegroundNotificationIds.EASY_TIER_CONNECT,
            "easytier vpn" to ForegroundNotificationIds.EASY_TIER_VPN,
            "ai agent" to ForegroundNotificationIds.AI_AGENT,
            "steam cloud sync result" to ForegroundNotificationIds.STEAM_CLOUD_SYNC_RESULT,
        )

        val duplicates = ids.groupBy { it.second }.filterValues { it.size > 1 }
        assertEquals("Duplicate notification ids: $duplicates", emptyMap<Int, List<Pair<String, Int>>>(), duplicates)
    }

    @Test
    fun idsStayInTheReservedHighRange() {
        // Notifications posted by the launcher must not collide with anything the runtime mod or a
        // bundled library posts under its own low ids.
        listOf(
            ForegroundNotificationIds.WORKSHOP_DOWNLOAD,
            ForegroundNotificationIds.STEAM_CLOUD_SYNC,
            ForegroundNotificationIds.STEAM_GAME_PRESENCE,
            ForegroundNotificationIds.EASY_TIER_CONNECT,
            ForegroundNotificationIds.EASY_TIER_VPN,
            ForegroundNotificationIds.AI_AGENT,
            ForegroundNotificationIds.STEAM_CLOUD_SYNC_RESULT,
        ).forEach { assertEquals(it >= 646_570, true) }
    }
}
