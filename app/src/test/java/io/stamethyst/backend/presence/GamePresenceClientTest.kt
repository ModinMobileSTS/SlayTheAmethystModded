package io.stamethyst.backend.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePresenceClientTest {
    @Test
    fun buildHeartbeatPayload_includesGameStateAndLaunchMode() {
        val payload = GamePresenceClient.buildHeartbeatPayload(
            identity = TestIdentity,
            launchMode = "normal",
            state = GamePresenceState.Game,
            playerName = "Apricityx"
        )

        assertEquals("android:test-device", payload.getString("client_id"))
        assertEquals("test-device", payload.getString("device_id"))
        assertEquals("android_id_sha256", payload.getString("id_type"))
        assertEquals("presence", payload.getString("type"))
        assertEquals("game", payload.getString("state"))
        assertEquals("normal", payload.getString("launch_mode"))
        assertEquals("Apricityx", payload.getString("player_name"))
        assertTrue(payload.has("device_model"))
        assertTrue(payload.has("android_version"))
        assertTrue(payload.has("sent_at"))
    }

    @Test
    fun buildHeartbeatPayload_includesLauncherState() {
        val payload = GamePresenceClient.buildHeartbeatPayload(
            identity = TestIdentity,
            launchMode = "launcher",
            state = GamePresenceState.Launcher,
            playerName = "Apricityx"
        )

        assertEquals("launcher", payload.getString("state"))
        assertEquals("launcher", payload.getString("launch_mode"))
    }

    @Test
    fun buildHeartbeatPayload_includesAiEditorState() {
        val payload = GamePresenceClient.buildHeartbeatPayload(
            identity = TestIdentity,
            launchMode = "launcher",
            state = GamePresenceState.AiEditor,
            playerName = "Apricityx"
        )

        assertEquals("ai_editor", payload.getString("state"))
        assertEquals("Apricityx", payload.getString("player_name"))
        assertEquals("android:test-device", payload.getString("client_id"))
    }

    @Test
    fun buildMinimalHeartbeatPayload_includesAiEditorState() {
        val payload = GamePresenceClient.buildMinimalHeartbeatPayload(
            identity = TestIdentity,
            state = GamePresenceState.AiEditor
        )

        assertEquals("ai_editor", payload.getString("state"))
        assertEquals("android:test-device", payload.getString("client_id"))
        assertTrue(!payload.has("player_name"))
    }

    @Test
    fun buildMinimalHeartbeatPayload_omitsStableMetadata() {
        val payload = GamePresenceClient.buildMinimalHeartbeatPayload(
            identity = TestIdentity,
            state = GamePresenceState.Game
        )

        assertEquals("presence", payload.getString("type"))
        assertEquals("android:test-device", payload.getString("client_id"))
        assertEquals("game", payload.getString("state"))
        assertTrue(payload.has("sent_at"))
        assertTrue(!payload.has("device_id"))
        assertTrue(!payload.has("id_type"))
        assertTrue(!payload.has("player_name"))
        assertTrue(!payload.has("app_version"))
        assertTrue(!payload.has("device_model"))
        assertTrue(!payload.has("android_version"))
    }

    @Test
    fun buildHeartbeatMetadataSignature_changesWhenMetadataChanges() {
        val first = GamePresenceClient.buildHeartbeatMetadataSignature(
            identity = TestIdentity,
            launchMode = "normal",
            playerName = "Apricityx"
        )
        val second = GamePresenceClient.buildHeartbeatMetadataSignature(
            identity = TestIdentity,
            launchMode = "normal",
            playerName = "Silent"
        )

        assertTrue(first.isNotEmpty())
        assertTrue(first != second)
    }

    @Test
    fun resolveHttpHeartbeatUrl_convertsPresenceWebSocketEndpoint() {
        assertEquals(
            "https://heartbeat.example.com/api/presence/heartbeat",
            GamePresenceClient.resolveHttpHeartbeatUrl("wss://heartbeat.example.com/api/presence/ws")
        )
        assertEquals(
            "http://heartbeat.example.com/api/presence/heartbeat",
            GamePresenceClient.resolveHttpHeartbeatUrl("ws://heartbeat.example.com/api/presence/ws")
        )
        assertEquals("", GamePresenceClient.resolveHttpHeartbeatUrl(""))
    }

    @Test
    fun buildHeartbeatWebSocketRequest_usesCloudControlEndpoint() {
        val request = GamePresenceClient.buildHeartbeatWebSocketRequest(
            "wss://heartbeat.example.com/api/presence/ws"
        )

        assertEquals(
            "https://heartbeat.example.com/api/presence/ws",
            request.url.toString()
        )
    }

    private object TestIdentity : GamePresenceIdentityPayload {
        override val clientId: String = "android:test-device"
        override val deviceId: String = "test-device"
        override val idType: String = "android_id_sha256"
    }
}
