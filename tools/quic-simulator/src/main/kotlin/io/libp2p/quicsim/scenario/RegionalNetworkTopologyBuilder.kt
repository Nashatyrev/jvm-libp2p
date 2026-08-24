package io.libp2p.quicsim.scenario

import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkDefaults
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Geographic regions supported by [RegionalNetworkTopologyBuilder]. */
enum class NetworkRegion {
    US_EAST,
    US_WEST,
    EUROPE,
    ASIA,
    SOUTH_AMERICA,
    AFRICA
}

/**
 * Builds a regional router topology.
 *
 * Each host has a single bidirectional connection to its region's router. That access connection
 * has half the region's intraregion latency in each direction. Regional routers have a complete
 * directed mesh using the supplied regional latency matrix. Router links are unshaped and have
 * effectively infinite bandwidth.
 */
class RegionalNetworkTopologyBuilder(
    private val maxQueueWaitTime: Duration = UdpSimNetworkDefaults.MAX_QUEUE_WAIT_TIME
) {
    private data class RegionalHost(
        val id: String,
        val region: NetworkRegion,
        val bandwidthBytesPerSecond: Long
    )

    private val hosts = mutableListOf<RegionalHost>()

    fun addHost(
        id: String,
        region: NetworkRegion,
        bandwidthBytesPerSecond: Long = VALIDATOR_BANDWIDTH_BYTES_PER_SECOND
    ): RegionalNetworkTopologyBuilder = apply {
        require(hosts.none { it.id == id }) { "Host id must be unique: $id" }
        require(bandwidthBytesPerSecond > 0) { "bandwidthBytesPerSecond must be positive" }
        hosts += RegionalHost(id, region, bandwidthBytesPerSecond)
    }

    fun addHosts(
        region: NetworkRegion,
        hostIds: Iterable<String>,
        bandwidthBytesPerSecond: Long = VALIDATOR_BANDWIDTH_BYTES_PER_SECOND
    ): RegionalNetworkTopologyBuilder = apply {
        hostIds.forEach { addHost(it, region, bandwidthBytesPerSecond) }
    }

    /**
     * Adds hosts with the configured regional weights and a seeded supernode selection.
     *
     * The selection is intentionally independent of a host's region, matching the scenario.
     */
    fun addRandomHosts(
        hostCount: Int = DEFAULT_HOST_COUNT,
        seed: Int = DEFAULT_RANDOM_SEED,
        hostId: (Int) -> String = { "node-$it" }
    ): RegionalNetworkTopologyBuilder = apply {
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

    fun build(): QuicNetworkTopology {
        require(hosts.isNotEmpty()) { "Regional network must contain at least one host" }

        val routersByRegion = NetworkRegion.values().associateWith { region ->
            QuicNetworkRouter(region.routerId)
        }
        val accessLinks = hosts.flatMap { host ->
            val routerId = routersByRegion.getValue(host.region).id
            listOf(
                QuicNetworkLink(
                    from = host.id,
                    to = routerId,
                    latency = accessLatency(host.region),
                    bandwidthBytesPerSecond = host.bandwidthBytesPerSecond,
                    maxQueueWaitTime = maxQueueWaitTime
                ),
                QuicNetworkLink(
                    from = routerId,
                    to = host.id,
                    latency = accessLatency(host.region),
                    bandwidthBytesPerSecond = host.bandwidthBytesPerSecond,
                    maxQueueWaitTime = maxQueueWaitTime
                )
            )
        }
        val routerLinks = NetworkRegion.values().flatMap { from ->
            NetworkRegion.values()
                .asSequence()
                .filter { to -> to != from }
                .map { to ->
                    QuicNetworkLink(
                        from = routersByRegion.getValue(from).id,
                        to = routersByRegion.getValue(to).id,
                        latency = latency(from, to),
                        bandwidthBytesPerSecond = Bandwidth.INFINITE_BANDWIDTH
                    )
                }
                .toList()
        }

        return QuicNetworkTopology(
            hosts = hosts.map { QuicNetworkHost(it.id) },
            routers = routersByRegion.values.toList(),
            links = accessLinks + routerLinks
        )
    }

    companion object {
        const val DEFAULT_HOST_COUNT: Int = 65
        const val DEFAULT_RANDOM_SEED: Int = 1
        const val SUPERNODE_FRACTION: Double = 0.05
        const val SUPERNODE_BANDWIDTH_BYTES_PER_SECOND: Long = 125_000_000L
        const val VALIDATOR_BANDWIDTH_BYTES_PER_SECOND: Long = 6_250_000L

        private val REGION_WEIGHTS = listOf(
            NetworkRegion.US_EAST to 0.30,
            NetworkRegion.EUROPE to 0.25,
            NetworkRegion.ASIA to 0.20,
            NetworkRegion.US_WEST to 0.15,
            NetworkRegion.SOUTH_AMERICA to 0.05,
            NetworkRegion.AFRICA to 0.05
        )

        private val LATENCY_MILLIS = arrayOf(
            longArrayOf(20, 60, 80, 150, 120, 180),
            longArrayOf(60, 20, 130, 110, 160, 200),
            longArrayOf(80, 130, 15, 100, 170, 80),
            longArrayOf(150, 110, 100, 20, 250, 160),
            longArrayOf(120, 160, 170, 250, 25, 220),
            longArrayOf(180, 200, 80, 160, 220, 30)
        )

        fun latency(
            from: NetworkRegion,
            to: NetworkRegion
        ): Duration = LATENCY_MILLIS[from.ordinal][to.ordinal].milliseconds

        fun accessLatency(region: NetworkRegion): Duration = latency(region, region) / 2

        private fun randomRegion(random: Random): NetworkRegion {
            val randomValue = random.nextDouble()
            var cumulativeWeight = 0.0
            REGION_WEIGHTS.forEach { (region, weight) ->
                cumulativeWeight += weight
                if (randomValue < cumulativeWeight) return region
            }
            return REGION_WEIGHTS.last().first
        }
    }
}

private val NetworkRegion.routerId: String
    get() = "router-" + name.lowercase().replace('_', '-')
