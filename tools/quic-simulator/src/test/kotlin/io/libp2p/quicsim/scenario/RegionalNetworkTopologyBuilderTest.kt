package io.libp2p.quicsim.scenario

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class RegionalNetworkTopologyBuilderTest {
    @Test
    fun `builds full mesh with regional latencies and infinite bandwidth`() {
        val regions = NetworkRegion.values().toList()
        val topology = RegionalNetworkTopologyBuilder()
            .also { builder ->
                regions.forEach { region ->
                    builder.addHosts(
                        region,
                        listOf("${region.name}-0", "${region.name}-1")
                    )
                }
            }
            .build()
        val regionByHost = regions.flatMap { region ->
            listOf("${region.name}-0", "${region.name}-1").map { it to region }
        }.toMap()

        assertEquals(12, topology.hosts.size)
        assertEquals(emptyList<QuicNetworkRouter>(), topology.routers)
        assertEquals(12 * 11, topology.links.size)
        topology.links.forEach { link ->
            assertEquals(
                RegionalNetworkTopologyBuilder.latency(
                    regionByHost.getValue(link.from),
                    regionByHost.getValue(link.to)
                ),
                link.latency
            )
            assertEquals(Long.MAX_VALUE, link.bandwidthBytesPerSecond)
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
    fun `topology companion creates hosts in supplied regions`() {
        val topology = QuicNetworkTopology.regional(
            hostRegions = listOf(NetworkRegion.US_EAST, NetworkRegion.AFRICA),
            hostId = { "peer-$it" }
        )

        assertEquals(listOf("peer-0", "peer-1"), topology.hosts.map { it.id })
        assertEquals(180.milliseconds, topology.links.single { it.from == "peer-0" }.latency)
        assertEquals(180.milliseconds, topology.links.single { it.from == "peer-1" }.latency)
    }

    @Test
    fun `rejects duplicate host ids`() {
        val builder = RegionalNetworkTopologyBuilder()
            .addHost("node-0", NetworkRegion.US_EAST)

        assertThrows(IllegalArgumentException::class.java) {
            builder.addHost("node-0", NetworkRegion.EUROPE)
        }
    }
}
