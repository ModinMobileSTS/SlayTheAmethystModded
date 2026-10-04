package top.apricityx.workshop.workshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.apricityx.workshop.steam.protocol.CdnServer

internal fun testCdnServer(host: String) = CdnServer(
    type = "CDN", sourceId = 1, cellId = 0, load = 0, weightedLoad = 0f,
    numEntriesInClientList = 1, steamChinaOnly = false, host = host, vHost = host,
    useAsProxy = false, proxyRequestPathTemplate = null, httpsSupport = "mandatory",
    allowedAppIds = emptyList(), priorityClass = 0u,
)

class SteamCdnServerSelectorTest {
    private val first = testCdnServer("first.test")
    private val second = testCdnServer("second.test")

    @Test
    fun deduplicatesWeightedHostsAndRespectsPerChunkExclusions() {
        val selector = SteamCdnServerSelector(listOf(first, first, second, first))
        assertEquals(listOf(first, second), selector.servers)
        assertEquals(second, selector.acquire(setOf(first.host), 1_000, 0)!!.server)
        assertNull(selector.acquire(setOf(first.host, second.host), 1_000, 0))
    }

    @Test
    fun concurrentWorkersSampleDifferentIdleEdges() {
        val selector = SteamCdnServerSelector(listOf(first, second))
        val lease = selector.acquire(emptySet(), 1_000_000, 0)!!
        assertEquals(first, lease.server)
        assertEquals(second, selector.acquire(emptySet(), 1_000_000, 0)!!.server)
    }

    @Test
    fun realThroughputOutweighsInitialSteamOrdering() {
        var now = 0L
        val selector = SteamCdnServerSelector(listOf(first, second)) { now }
        val slow = selector.acquire(emptySet(), 1_000_000, 0)!!
        now += 2_000_000_000L
        slow.succeeded(1_000_000)
        val fast = selector.acquire(setOf(first.host), 1_000_000, 0)!!
        now += 10_000_000L
        fast.succeeded(1_000_000)
        assertEquals(second, selector.acquire(emptySet(), 1_000_000, 0)!!.server)
    }

    @Test
    fun failedEdgeCoolsDownButBecomesEligibleAgain() {
        var now = 0L
        val selector = SteamCdnServerSelector(listOf(first, second)) { now }
        selector.acquire(emptySet(), 1_000, 0)!!.failed()
        val healthy = selector.acquire(emptySet(), 1_000, 0)!!
        assertEquals(second, healthy.server)
        healthy.cancelled()
        now += 31_000_000_000L
        assertEquals(first, selector.acquire(emptySet(), 1_000, 0)!!.server)
    }

    @Test
    fun allCoolingEdgesStillHaveALastResort() {
        val selector = SteamCdnServerSelector(listOf(first, second)) { 0L }
        selector.acquire(emptySet(), 1_000, 0)!!.failed()
        selector.acquire(emptySet(), 1_000, 0)!!.failed()
        assertEquals(first, selector.acquire(emptySet(), 1_000, 0)!!.server)
    }

    @Test
    fun cancellationReleasesCapacityWithoutPenalizingTheEdge() {
        val selector = SteamCdnServerSelector(listOf(first, second))
        val lease = selector.acquire(emptySet(), 1_000, 0)!!
        lease.cancelled()
        lease.cancelled() // release is idempotent
        assertEquals(first, selector.acquire(emptySet(), 1_000, 0)!!.server)
    }
}
