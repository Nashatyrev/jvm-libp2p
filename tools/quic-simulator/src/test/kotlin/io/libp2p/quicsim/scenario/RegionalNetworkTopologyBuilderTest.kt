package io.libp2p.quicsim.scenario

import io.libp2p.quicsim.udpnetwork.Bandwidth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class RegionalNetworkTopologyBuilderTest {
    @Test
    fun `builds the 65 node regional router topology`() {
        val topology = RegionalNetworkTopologyBuilder(REGIONAL_DESCRIPTOR)
            .addRandomScenarioHosts()
            .build()
        val routerIds = TestRegion.values().associateBy { routerId(it) }
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
            assertEquals(accessLatency(region), outgoing.latency)
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
            assertEquals(latency(from, to), link.latency)
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

        TestRegion.values().forEachIndexed { fromIndex, from ->
            TestRegion.values().forEachIndexed { toIndex, to ->
                assertEquals(
                    expectedMillis[fromIndex][toIndex].milliseconds,
                    REGIONAL_DESCRIPTOR.routerLatency(from, to)
                )
            }
        }
    }

    @Test
    fun `access latency is half of intraregion latency`() {
        assertEquals(10.milliseconds, REGIONAL_DESCRIPTOR.accessLatency(TestRegion.US_EAST))
        assertEquals(7.5.milliseconds, REGIONAL_DESCRIPTOR.accessLatency(TestRegion.EUROPE))
        assertEquals(12.5.milliseconds, REGIONAL_DESCRIPTOR.accessLatency(TestRegion.SOUTH_AMERICA))
    }

    @Test
    fun `topology companion creates hosts in supplied regions`() {
        val topology = QuicNetworkTopology.regional(
            descriptor = REGIONAL_DESCRIPTOR,
            hostRegions = listOf(TestRegion.US_EAST, TestRegion.AFRICA),
            bandwidthBytesPerSecond = VALIDATOR_BANDWIDTH_BYTES_PER_SECOND,
            hostId = { "peer-$it" }
        )

        assertEquals(listOf("peer-0", "peer-1"), topology.hosts.map { it.id })
        assertEquals(10.milliseconds, topology.links.single { it.from == "peer-0" }.latency)
        assertEquals(15.milliseconds, topology.links.single { it.from == "peer-1" }.latency)
    }

    @Test
    fun `rejects duplicate host ids`() {
        val builder = RegionalNetworkTopologyBuilder(REGIONAL_DESCRIPTOR)
            .addHost("node-0", TestRegion.US_EAST, VALIDATOR_BANDWIDTH_BYTES_PER_SECOND)

        assertThrows(IllegalArgumentException::class.java) {
            builder.addHost("node-0", TestRegion.EUROPE, VALIDATOR_BANDWIDTH_BYTES_PER_SECOND)
        }
    }

    @Test
    fun `rejects hosts in unknown regions`() {
        val descriptor = RegionalNetworkDescriptor(
            regions = listOf(TestRegion.US_EAST),
            routerId = ::routerId,
            routerLatency = ::latency,
            accessLatency = ::accessLatency
        )
        val builder = RegionalNetworkTopologyBuilder(descriptor)

        assertThrows(IllegalArgumentException::class.java) {
            builder.addHost("node-0", TestRegion.EUROPE, VALIDATOR_BANDWIDTH_BYTES_PER_SECOND)
        }
    }
}

internal enum class TestRegion {
    US_EAST,
    US_WEST,
    EUROPE,
    ASIA,
    SOUTH_AMERICA,
    AFRICA
}

internal const val HOST_COUNT: Int = 65
internal const val RANDOM_SEED: Int = 1
internal const val SUPERNODE_FRACTION: Double = 0.05
internal const val SUPERNODE_BANDWIDTH_BYTES_PER_SECOND: Long = 125_000_000L
internal const val VALIDATOR_BANDWIDTH_BYTES_PER_SECOND: Long = 6_250_000L

private val REGION_WEIGHTS = listOf(
    TestRegion.US_EAST to 0.30,
    TestRegion.EUROPE to 0.25,
    TestRegion.ASIA to 0.20,
    TestRegion.US_WEST to 0.15,
    TestRegion.SOUTH_AMERICA to 0.05,
    TestRegion.AFRICA to 0.05
)

private val LATENCY_MILLIS = arrayOf(
    longArrayOf(20, 60, 80, 150, 120, 180),
    longArrayOf(60, 20, 130, 110, 160, 200),
    longArrayOf(80, 130, 15, 100, 170, 80),
    longArrayOf(150, 110, 100, 20, 250, 160),
    longArrayOf(120, 160, 170, 250, 25, 220),
    longArrayOf(180, 200, 80, 160, 220, 30)
)

internal fun latency(from: TestRegion, to: TestRegion): Duration =
    LATENCY_MILLIS[from.ordinal][to.ordinal].milliseconds

internal fun accessLatency(region: TestRegion): Duration = latency(region, region) / 2

internal fun routerId(region: TestRegion): String =
    "router-" + region.name.lowercase().replace('_', '-')

internal val REGIONAL_DESCRIPTOR = RegionalNetworkDescriptor(
    regions = TestRegion.values().toList(),
    routerId = ::routerId,
    routerLatency = ::latency,
    accessLatency = ::accessLatency
)

internal fun RegionalNetworkTopologyBuilder<TestRegion>.addRandomScenarioHosts(
    hostCount: Int = HOST_COUNT,
    seed: Int = RANDOM_SEED,
    hostId: (Int) -> String = { "node-$it" }
): RegionalNetworkTopologyBuilder<TestRegion> = apply {
    require(hostCount > 0) { "hostCount must be positive" }
    val random = Random(seed)
    val supernodeCount = (hostCount * SUPERNODE_FRACTION).roundToInt()
    val supernodeIndexes = (0 until hostCount).shuffled(random).take(supernodeCount).toSet()
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

private fun randomRegion(random: Random): TestRegion {
    val randomValue = random.nextDouble()
    var cumulativeWeight = 0.0
    REGION_WEIGHTS.forEach { (region, weight) ->
        cumulativeWeight += weight
        if (randomValue < cumulativeWeight) return region
    }
    return REGION_WEIGHTS.last().first
}
