package io.libp2p.quicsim.scenario

import io.libp2p.quicsim.udpnetwork.Bandwidth
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class RegionalNetworkDescriptor<R>(
    val regions: List<R>,
    val routerId: (R) -> String,
    val routerLatency: (from: R, to: R) -> Duration,
    val accessLatency: (R) -> Duration = { routerLatency(it, it) / 2 },
    val routerBandwidth: Bandwidth = Bandwidth(Bandwidth.INFINITE)
) {
    init {
        require(regions.isNotEmpty()) { "Regional network must contain at least one region" }
        require(regions.size == regions.toSet().size) { "Regions must be unique: $regions" }

        val routerIds = regions.map(routerId)
        require(routerIds.size == routerIds.toSet().size) { "Regional router ids must be unique: $routerIds" }
        regions.forEach { region ->
            require(!accessLatency(region).isNegative()) { "Access latency must not be negative for $region" }
        }
        regions.forEach { from ->
            regions.forEach { to ->
                require(!routerLatency(from, to).isNegative()) {
                    "Router latency must not be negative from $from to $to"
                }
            }
        }
    }

    companion object {

        enum class ContinentRegion {
            US_EAST,
            US_WEST,
            EUROPE,
            ASIA,
            SOUTH_AMERICA,
            AFRICA
        }

        val WORLD_DESCRIPTOR_1: RegionalNetworkDescriptor<ContinentRegion> = run {
            val latencies = arrayOf(
                longArrayOf(20, 60, 80, 150, 120, 180),
                longArrayOf(60, 20, 130, 110, 160, 200),
                longArrayOf(80, 130, 15, 100, 170, 80),
                longArrayOf(150, 110, 100, 20, 250, 160),
                longArrayOf(120, 160, 170, 250, 25, 220),
                longArrayOf(180, 200, 80, 160, 220, 30)
            )

            fun latency(from: ContinentRegion, to: ContinentRegion): Duration =
                latencies[from.ordinal][to.ordinal].milliseconds

            fun routerId(region: ContinentRegion): String =
                "router-" + region.name.lowercase().replace('_', '-')

            RegionalNetworkDescriptor(
                regions = ContinentRegion.values().toList(),
                routerId = ::routerId,
                routerLatency = ::latency,
            )
        }
    }
}