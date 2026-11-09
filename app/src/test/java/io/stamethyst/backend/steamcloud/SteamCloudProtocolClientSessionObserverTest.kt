package io.stamethyst.backend.steamcloud

import java.lang.reflect.Proxy
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import top.apricityx.workshop.steam.protocol.SessionContext
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.steam.protocol.SteamWebSocketFactory

class SteamCloudProtocolClientSessionObserverTest {
    @Test
    fun observesIdleDisconnectAndReconnectWithoutPollingOrGameChanges() {
        val initial = SessionContext(1, 76561198000000001L, 0u, 30)
        val state = MutableStateFlow<SessionContext?>(initial)
        val session = Proxy.newProxyInstance(
            SteamCmSession::class.java.classLoader,
            arrayOf(SteamCmSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getCurrentSession" -> state
                "close" -> null
                else -> error("Unexpected method ${method.name}")
            }
        } as SteamCmSession
        val httpClient = OkHttpClient()
        SteamCloudProtocolClient(httpClient, SteamWebSocketFactory(httpClient::newWebSocket), session).use { client ->
            val events = LinkedBlockingQueue<Boolean>()
            val observer = client.observeSessionChanges { events.add(true) }
            try {
                assertSame(initial, client.sessionIdentity)
                state.value = null
                assertNotNull(events.poll(2, TimeUnit.SECONDS))
                assertNull(client.sessionIdentity)

                val reconnected = initial.copy(sessionId = 2)
                state.value = reconnected
                assertNotNull(events.poll(2, TimeUnit.SECONDS))
                assertSame(reconnected, client.sessionIdentity)
            } finally {
                observer.close()
            }
            state.value = null
            assertNull(events.poll(100, TimeUnit.MILLISECONDS))
        }
    }
}
