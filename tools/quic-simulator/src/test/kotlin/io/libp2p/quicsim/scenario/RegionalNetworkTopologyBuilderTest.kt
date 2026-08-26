package io.libp2p.quicsim.scenario

import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.ContinentRegion
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.WORLD_DESCRIPTOR_1
import io.libp2p.quicsim.udpnetwork.Bandwidth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class RegionalNetworkTopologyBuilderTest {
    @Test
    fun `builds the 65 node regional router topology`() {
        val topology = RegionalNetworkTopologyBuilder(WORLD_DESCRIPTOR_1)
            .addRandomScenarioHosts()
            .build()
        val routerIds = WORLD_DESCRIPTOR_1.regions.associateBy(WORLD_DESCRIPTOR_1.routerId)
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
            assertEquals(WORLD_DESCRIPTOR_1.accessLatency(region), outgoing.latency)
            assertEquals(outgoing.latency, incoming.latency)
            assertEquals(outgoing.bandwidthBytesPerSecond, incoming.bandwidthBytesPerSecond)
        }

        assertEquals(
            3,
            topology.hosts.count { host ->
                accessLinks.single { it.from == host.id }.bandwidthBytesPerSecond ==
                    SUPERNODE_BANDWIDTH_BYTES_PER_SECOND
            }
        )
        assertEquals(
            62,
            topology.hosts.count { host ->
                accessLinks.single { it.from == host.id }.bandwidthBytesPerSecond ==
                    VALIDATOR_BANDWIDTH_BYTES_PER_SECOND
            }
        )
        assertTrue(accessLinks.all { it.from in hostIds || it.to in hostIds })

        routerLinks.forEach { link ->
            val from = routerIds.getValue(link.from)
            val to = routerIds.getValue(link.to)
            assertEquals(Bandwidth.INFINITE, link.bandwidthBytesPerSecond)
            assertEquals(WORLD_DESCRIPTOR_1.routerLatency(from, to), link.latency)
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

        WORLD_DESCRIPTOR_1.regions.forEachIndexed { fromIndex, from ->
            WORLD_DESCRIPTOR_1.regions.forEachIndexed { toIndex, to ->
                assertEquals(
                    expectedMillis[fromIndex][toIndex].milliseconds,
                    WORLD_DESCRIPTOR_1.routerLatency(from, to)
                )
            }
        }
    }

    @Test
    fun `access latency is half of intraregion latency`() {
        assertEquals(10.milliseconds, WORLD_DESCRIPTOR_1.accessLatency(ContinentRegion.US_EAST))
        assertEquals(7.5.milliseconds, WORLD_DESCRIPTOR_1.accessLatency(ContinentRegion.EUROPE))
        assertEquals(12.5.milliseconds, WORLD_DESCRIPTOR_1.accessLatency(ContinentRegion.SOUTH_AMERICA))
    }

    @Test
    fun `topology companion creates hosts in supplied regions`() {
        val topology = QuicNetworkTopology.regional(
            descriptor = WORLD_DESCRIPTOR_1,
            hostRegions = listOf(ContinentRegion.US_EAST, ContinentRegion.AFRICA),
            bandwidthBytesPerSecond = VALIDATOR_BANDWIDTH_BYTES_PER_SECOND,
            hostId = { "peer-$it" }
        )

        assertEquals(listOf("peer-0", "peer-1"), topology.hosts.map { it.id })
        assertEquals(10.milliseconds, topology.links.single { it.from == "peer-0" }.latency)
        assertEquals(15.milliseconds, topology.links.single { it.from == "peer-1" }.latency)
    }

    @Test
    fun `rejects duplicate host ids`() {
        val builder = RegionalNetworkTopologyBuilder(WORLD_DESCRIPTOR_1)
            .addHost("node-0", ContinentRegion.US_EAST, VALIDATOR_BANDWIDTH_BYTES_PER_SECOND)

        assertThrows(IllegalArgumentException::class.java) {
            builder.addHost("node-0", ContinentRegion.EUROPE, VALIDATOR_BANDWIDTH_BYTES_PER_SECOND)
        }
    }

    @Test
    fun `rejects hosts in unknown regions`() {
        val descriptor = RegionalNetworkDescriptor(
            regions = listOf(ContinentRegion.US_EAST),
            routerId = WORLD_DESCRIPTOR_1.routerId,
            routerLatency = WORLD_DESCRIPTOR_1.routerLatency,
            accessLatency = WORLD_DESCRIPTOR_1.accessLatency
        )
        val builder = RegionalNetworkTopologyBuilder(descriptor)

        assertThrows(IllegalArgumentException::class.java) {
            builder.addHost("node-0", ContinentRegion.EUROPE, VALIDATOR_BANDWIDTH_BYTES_PER_SECOND)
        }
    }
}

internal const val HOST_COUNT: Int = 65
internal const val RANDOM_SEED: Int = 1
internal const val SUPERNODE_FRACTION: Double = 0.05
internal const val SUPERNODE_BANDWIDTH_BYTES_PER_SECOND: Long = 125_000_000L
internal const val VALIDATOR_BANDWIDTH_BYTES_PER_SECOND: Long = 6_250_000L

private val REGION_WEIGHTS = listOf(
    ContinentRegion.US_EAST to 0.30,
    ContinentRegion.EUROPE to 0.25,
    ContinentRegion.ASIA to 0.20,
    ContinentRegion.US_WEST to 0.15,
    ContinentRegion.SOUTH_AMERICA to 0.05,
    ContinentRegion.AFRICA to 0.05
)

internal fun RegionalNetworkTopologyBuilder<ContinentRegion>.addRandomScenarioHosts(
    hostCount: Int = HOST_COUNT,
    seed: Int = RANDOM_SEED,
    hostId: (Int) -> String = { "node-$it" },
    forcedSupernodeIndexes: Set<Int> = emptySet(),
    excludedSupernodeIndexes: Set<Int> = emptySet()
): RegionalNetworkTopologyBuilder<ContinentRegion> = apply {
    require(hostCount > 0) { "hostCount must be positive" }
    require((forcedSupernodeIndexes + excludedSupernodeIndexes).all { it in 0 until hostCount }) {
        "Forced and excluded supernode indexes must be in [0, $hostCount)"
    }
    require(forcedSupernodeIndexes.intersect(excludedSupernodeIndexes).isEmpty()) {
        "A supernode index cannot be both forced and excluded"
    }
    val random = Random(seed)
    val supernodeCount = (hostCount * SUPERNODE_FRACTION).roundToInt()
    require(forcedSupernodeIndexes.size <= supernodeCount) {
        "Forced supernode count must not exceed $supernodeCount"
    }
    val randomlySelectedSupernodes = (0 until hostCount).shuffled(random).take(supernodeCount)
    val supernodeIndexes = forcedSupernodeIndexes +
        randomlySelectedSupernodes
            .filter { it !in forcedSupernodeIndexes && it !in excludedSupernodeIndexes }
            .take(supernodeCount - forcedSupernodeIndexes.size)
    repeat(hostCount) { index ->
        addHost(
            id = hostId(index),
            region = randomRegion(random),
            bandwidthBytesPerSecond = if (index in supernodeIndexes) {
                SUPERNODE_BANDWIDTH_BYTES_PER_SECOND
            } else {
                VALIDATOR_BANDWIDTH_BYTES_PER_SECOND
            }
        )
    }
}

private fun randomRegion(random: Random): ContinentRegion {
    val randomValue = random.nextDouble()
    var cumulativeWeight = 0.0
    REGION_WEIGHTS.forEach { (region, weight) ->
        cumulativeWeight += weight
        if (randomValue < cumulativeWeight) return region
    }
    return REGION_WEIGHTS.last().first
}
