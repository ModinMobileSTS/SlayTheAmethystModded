package io.stamethyst.backend.notify

/**
 * Notification ids for every foreground service and its result notifications in the launcher.
 *
 * `NotificationManager` identifies a notification by (tag, id) inside the package, so two services
 * that pick the same id overwrite each other: whichever posted last owns the entry, and the text,
 * icon, channel and actions of the other are gone even though its service is still in the
 * foreground. That is how a running AI task could end up with no notification while the Steam Cloud
 * sync notification was visible.
 *
 * Keep the ids here, unique, and never reuse one for a different service. A notification id carries
 * no meaning beyond identity, so reassigning it only affects an entry posted by a build that is
 * already gone.
 */
object ForegroundNotificationIds {
    const val WORKSHOP_DOWNLOAD = 646570
    const val STEAM_CLOUD_SYNC = 646571
    const val STEAM_GAME_PRESENCE = 646572
    const val EASY_TIER_CONNECT = 646573

    /** The VpnService owns its own foreground notification while a tunnel is up. */
    const val EASY_TIER_VPN = 646574

    /** The AI agent run is long lived and must stay visible for its whole duration. */
    const val AI_AGENT = 646575

    /** Not a foreground notification: service cleanup must not remove the synchronization result. */
    const val STEAM_CLOUD_SYNC_RESULT = 646576
}
