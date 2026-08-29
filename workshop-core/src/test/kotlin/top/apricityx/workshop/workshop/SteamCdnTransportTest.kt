package top.apricityx.workshop.workshop

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.apricityx.workshop.steam.protocol.CdnServer
import top.apricityx.workshop.steam.protocol.SteamDeclaredCdnHosts

class SteamCdnTransportTest {
    @After
    fun tearDown() {
        SteamDeclaredCdnHosts.clear()
    }

    private fun cdnServer(
        host: String,
        vHost: String = host,
        useAsProxy: Boolean = false,
        proxyRequestPathTemplate: String? = null,
    ) = CdnServer(
        type = "CDN",
        sourceId = 1,
        cellId = 0,
        load = 0,
        weightedLoad = 0f,
        numEntriesInClientList = 1,
        steamChinaOnly = true,
        host = host,
        vHost = vHost,
        useAsProxy = useAsProxy,
        proxyRequestPathTemplate = proxyRequestPathTemplate,
        httpsSupport = "optional",
        allowedAppIds = emptyList(),
        priorityClass = 0u,
    )

    @Test
    fun buildServerPool_registersSteamDeclaredHostsForCleartextAllowance() {
        val transport = SteamCdnTransport(okhttp3.OkHttpClient())

        transport.buildServerPool(
            appId = 646570u,
            contentServers = listOf(
                cdnServer(host = "st.dl.eccdnx.com"),
                cdnServer(host = "origin-host.steamchina.test", vHost = "xz.sycontroller.com"),
                cdnServer(
                    host = "proxy-host.steamchina.test",
                    vHost = "proxy.steamchina.test",
                    useAsProxy = true,
                    proxyRequestPathTemplate = "/proxy/%host%/%path%",
                ),
            ),
        )

        assertTrue(SteamDeclaredCdnHosts.isDeclared("st.dl.eccdnx.com"))
        assertTrue(SteamDeclaredCdnHosts.isDeclared("origin-host.steamchina.test"))
        assertTrue(SteamDeclaredCdnHosts.isDeclared("xz.sycontroller.com"))
        assertTrue(SteamDeclaredCdnHosts.isDeclared("proxy-host.steamchina.test"))
        assertTrue(SteamDeclaredCdnHosts.isDeclared("proxy.steamchina.test"))
    }

    @Test
    fun steamDeclaredCdnHosts_normalizesHosts() {
        SteamDeclaredCdnHosts.register("  XZ.SYCONTROLLER.COM ")

        assertTrue(SteamDeclaredCdnHosts.isDeclared("xz.sycontroller.com"))
        assertFalse(SteamDeclaredCdnHosts.isDeclared("steamcontent.com"))
    }
}
