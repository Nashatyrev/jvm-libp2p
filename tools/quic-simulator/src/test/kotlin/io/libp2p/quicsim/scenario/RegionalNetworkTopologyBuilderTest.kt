package io.libp2p.quicsim.scenario

import io.libp2p.quicsim.udpnetwork.Bandwidth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class RegionalNetworkTopologyBuilderTest {
    @Test
    fun `builds the 65 node regional router topology`() {
        val topology = RegionalNetworkTopologyBuilder()
            .addRandomHosts()
            .build()
        val routerIds = NetworkRegion.values().associateBy { routerId(it) }
        val hostIds = topology.hosts.map { it.id }.toSet()
        val routerLinks = topology.links.filter { it.from in routerIds && it.to in routerIds }
        val accessLinks = topology.links - routerLinks.toSet()

        assertEquals(65, topology.hosts.size)
        assertEquals(routerIds.keys, topology.routers.map { it.id }.toSet())
        assertEquals(30, routerLinks.size)
        assertEquals(130, accessLinks.size)

        topology.hosts.forEach { host ->
            val outgoing = accessLinks.single { it.from == host.id }
            val incoming = accessLinks.single { it.to == host.id }
            val region = routerIds.getValue(outgoing.to)

            assertEquals(outgoing.to, incoming.from)
            assertEquals(RegionalNetworkTopologyBuilder.accessLatency(region), outgoing.latency)
            assertEquals(outgoing.latency, incoming.latency)
            assertEquals(outgoing.bandwidthBytesPerSecond, incoming.bandwidthBytesPerSecond)
        }

        assertEquals(
            3,
            topology.hosts.count { host ->
                accessLinks.single { it.from == host.id }.bandwidthBytesPerSecond ==
                    RegionalNetworkTopologyBuilder.SUPERNODE_BANDWIDTH_BYTES_PER_SECOND
            }
        )
        assertEquals(
            62,
            topology.hosts.count { host ->
                accessLinks.single { it.from == host.id }.bandwidthBytesPerSecond ==
                    RegionalNetworkTopologyBuilder.VALIDATOR_BANDWIDTH_BYTES_PER_SECOND
            }
        )
        assertTrue(accessLinks.all { it.from in hostIds || it.to in hostIds })

        routerLinks.forEach { link ->
            val from = routerIds.getValue(link.from)
            val to = routerIds.getValue(link.to)
            assertEquals(Bandwidth.INFINITE_BANDWIDTH, link.bandwidthBytesPerSecond)
            assertEquals(RegionalNetworkTopologyBuilder.latency(from, to), link.latency)
        }
    }

    @Test
    fun `uses every requested matrix latency`() {
        val expectedMillis = listOf(
            listOf(20, 60, 80, 150, 120, 180),
            listOf(60, 20, 130, 110, 160, 200),
            listOf(80, 130, 15, 100, 170, 80),
            listOf(150, 110, 100, 20, 250, 160),
            listOf(120, 160, 170, 250, 25, 220),
            listOf(180, 200, 80, 160, 220, 30)
        )

        NetworkRegion.values().forEachIndexed { fromIndex, from ->
            NetworkRegion.values().forEachIndexed { toIndex, to ->
                assertEquals(
                    expectedMillis[fromIndex][toIndex].milliseconds,
                    RegionalNetworkTopologyBuilder.latency(from, to)
                )
            }
        }
    }

    @Test
    fun `access latency is half of intraregion latency`() {
        assertEquals(10.milliseconds, RegionalNetworkTopologyBuilder.accessLatency(NetworkRegion.US_EAST))
        assertEquals(7.5.milliseconds, RegionalNetworkTopologyBuilder.accessLatency(NetworkRegion.EUROPE))
        assertEquals(12.5.milliseconds, RegionalNetworkTopologyBuilder.accessLatency(NetworkRegion.SOUTH_AMERICA))
    }

    @Test
    fun `topology companion creates hosts in supplied regions`() {
        val topology = QuicNetworkTopology.regional(
            hostRegions = listOf(NetworkRegion.US_EAST, NetworkRegion.AFRICA),
            hostId = { "peer-$it" }
        )

        assertEquals(listOf("peer-0", "peer-1"), topology.hosts.map { it.id })
        assertEquals(10.milliseconds, topology.links.single { it.from == "peer-0" }.latency)
        assertEquals(15.milliseconds, topology.links.single { it.from == "peer-1" }.latency)
    }

    @Test
    fun `rejects duplicate host ids`() {
        val builder = RegionalNetworkTopologyBuilder()
            .addHost("node-0", NetworkRegion.US_EAST)

        assertThrows(IllegalArgumentException::class.java) {
            builder.addHost("node-0", NetworkRegion.EUROPE)
        }
    }

    private fun routerId(region: NetworkRegion): String =
        "router-" + region.name.lowercase().replace('_', '-')
}
