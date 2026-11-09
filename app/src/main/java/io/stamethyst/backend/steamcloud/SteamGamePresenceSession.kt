package io.stamethyst.backend.steamcloud

import java.io.IOException

/** Replays session-scoped state after a CM reconnect, even when the game has not changed. */
internal class SteamGamePresenceSession(private val client: Client) {
    interface Client {
        val sessionIdentity: Any?
        fun logOn()
        fun setPersonaOnline()
        fun setGamePlayedAppId(appId: Long)
        fun setRichPresence(presence: Map<String, String>)
    }

    private var connected = false
    private var reportedSession: Any? = null
    private var dispatchState: RichPresenceDispatchState? = null

    /** Returns true when the game state was replayed to a new (or newly borrowed) session. */
    fun synchronize(presence: Map<String, String>): Boolean {
        // A newly borrowed shared handle must connect even if another feature already logged on.
        if (!connected || client.sessionIdentity == null) {
            client.logOn()
            connected = true
        }
        val session = client.sessionIdentity ?: throw IOException("Steam CM session is not logged on.")
        val restored = reportedSession !== session
        if (restored) {
            client.setPersonaOnline()
            client.setGamePlayedAppId(646570L)
            dispatchState = RichPresenceDispatchState(presence)
            reportedSession = session
        }
        val dispatch = checkNotNull(dispatchState)
        dispatch.update(presence)
        if (dispatch.shouldUpload) {
            client.setRichPresence(dispatch.pending())
            dispatch.markUploaded()
        }
        if (client.sessionIdentity !== session) {
            throw IOException("Steam CM session changed while publishing game presence.")
        }
        return restored
    }
}
