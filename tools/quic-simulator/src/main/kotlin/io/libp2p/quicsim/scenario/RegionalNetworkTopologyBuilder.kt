package io.libp2p.quicsim.scenario

import io.libp2p.quicsim.udpnetwork.UdpSimNetworkDefaults
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
 * Builds a directed full-mesh topology whose link latency depends on the endpoint regions.
 *
 * Hosts in the same region use the diagonal entry of the regional latency matrix. All links
 * have effectively infinite bandwidth, so only latency affects packet delivery.
 */
class RegionalNetworkTopologyBuilder(
    private val maxQueueWaitTime: Duration = UdpSimNetworkDefaults.MAX_QUEUE_WAIT_TIME
) {
    private data class RegionalHost(
        val id: String,
        val region: NetworkRegion
    )

    private val hosts = mutableListOf<RegionalHost>()

    fun addHost(
        id: String,
        region: NetworkRegion
    ): RegionalNetworkTopologyBuilder = apply {
        require(hosts.none { it.id == id }) { "Host id must be unique: $id" }
        hosts += RegionalHost(id, region)
    }

    fun addHosts(
        region: NetworkRegion,
        hostIds: Iterable<String>
    ): RegionalNetworkTopologyBuilder = apply {
        hostIds.forEach { addHost(it, region) }
    }

    fun build(): QuicNetworkTopology {
        require(hosts.isNotEmpty()) { "Regional network must contain at least one host" }

        return QuicNetworkTopology(
            hosts = hosts.map { QuicNetworkHost(it.id) },
            links = hosts.flatMap { from ->
                hosts
                    .asSequence()
                    .filter { to -> to.id != from.id }
                    .map { to ->
                        QuicNetworkLink(
                            from = from.id,
                            to = to.id,
                            latency = latency(from.region, to.region),
                            bandwidthBytesPerSecond = INFINITE_BANDWIDTH_BYTES_PER_SECOND,
                            maxQueueWaitTime = maxQueueWaitTime
                        )
                    }
                    .toList()
            }
        )
    }

    companion object {
        const val INFINITE_BANDWIDTH_BYTES_PER_SECOND: Long = Long.MAX_VALUE

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
    }
}
