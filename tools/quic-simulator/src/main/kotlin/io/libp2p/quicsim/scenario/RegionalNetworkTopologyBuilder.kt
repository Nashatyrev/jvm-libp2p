package io.libp2p.quicsim.scenario

import io.libp2p.quicsim.udpnetwork.UdpSimNetworkDefaults
import kotlin.time.Duration

/**
 * Builds a regional router topology.
 *
 * Each host has a single bidirectional connection to its region's router. That access connection
 * has the descriptor's access latency in each direction, and may be asymmetric in bandwidth — the
 * two directions are separate links, so an upload rate below the download rate is expressible.
 * Regional routers have a complete directed mesh using the descriptor's router latency and
 * bandwidth.
 */
class RegionalNetworkTopologyBuilder<R>(
    private val descriptor: RegionalNetworkDescriptor<R>,
    private val maxQueueWaitTime: Duration = UdpSimNetworkDefaults.MAX_QUEUE_WAIT_TIME
) {
    private data class RegionalHost<R>(
        val id: String,
        val region: R,
        val bandwidthBytesPerSecond: Long,
        val uploadBandwidthBytesPerSecond: Long
    )

    private val hosts = mutableListOf<RegionalHost<R>>()

    /**
     * [uploadBandwidthBytesPerSecond] applies to the host -> router direction and
     * [bandwidthBytesPerSecond] to router -> host, so an asymmetric access link (as most consumer
     * connections are) can be described. It defaults to the download rate, keeping the link
     * symmetric.
     */
    fun addHost(
        id: String,
        region: R,
        bandwidthBytesPerSecond: Long,
        uploadBandwidthBytesPerSecond: Long = bandwidthBytesPerSecond
    ): RegionalNetworkTopologyBuilder<R> = apply {
        require(region in descriptor.regions) { "Unknown region for host $id: $region" }
        require(hosts.none { it.id == id }) { "Host id must be unique: $id" }
        require(bandwidthBytesPerSecond > 0) { "bandwidthBytesPerSecond must be positive" }
        require(uploadBandwidthBytesPerSecond > 0) {
            "uploadBandwidthBytesPerSecond must be positive"
        }
        hosts += RegionalHost(id, region, bandwidthBytesPerSecond, uploadBandwidthBytesPerSecond)
    }

    fun addHosts(
        region: R,
        hostIds: Iterable<String>,
        bandwidthBytesPerSecond: Long,
        uploadBandwidthBytesPerSecond: Long = bandwidthBytesPerSecond
    ): RegionalNetworkTopologyBuilder<R> = apply {
        hostIds.forEach { addHost(it, region, bandwidthBytesPerSecond, uploadBandwidthBytesPerSecond) }
    }

    fun build(): QuicNetworkTopology {
        require(hosts.isNotEmpty()) { "Regional network must contain at least one host" }

        val routersByRegion = descriptor.regions.associateWith { region ->
            QuicNetworkRouter(descriptor.routerId(region))
        }
        val accessLinks = hosts.flatMap { host ->
            val routerId = routersByRegion.getValue(host.region).id
            listOf(
                QuicNetworkLink(
                    from = host.id,
                    to = routerId,
                    latency = descriptor.accessLatency(host.region),
                    // Uplink: what the host itself can push out.
                    bandwidthBytesPerSecond = host.uploadBandwidthBytesPerSecond,
                    maxQueueWaitTime = maxQueueWaitTime
                ),
                QuicNetworkLink(
                    from = routerId,
                    to = host.id,
                    latency = descriptor.accessLatency(host.region),
                    bandwidthBytesPerSecond = host.bandwidthBytesPerSecond,
                    maxQueueWaitTime = maxQueueWaitTime
                )
            )
        }
        val routerLinks = descriptor.regions.flatMap { from ->
            descriptor.regions
                .asSequence()
                .filter { to -> to != from }
                .map { to ->
                    QuicNetworkLink(
                        from = routersByRegion.getValue(from).id,
                        to = routersByRegion.getValue(to).id,
                        latency = descriptor.routerLatency(from, to),
                        bandwidthBytesPerSecond = descriptor.routerBandwidth.bytesPerSecond
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
}
