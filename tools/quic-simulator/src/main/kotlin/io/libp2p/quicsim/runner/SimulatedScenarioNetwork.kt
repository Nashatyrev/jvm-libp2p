package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.SerialPacketProcessor
import io.libp2p.quicsim.scenario.QuicNetworkLink
import io.libp2p.quicsim.scenario.QuicNetworkTopology
import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.UdpSimLink
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import io.libp2p.quicsim.udpnetwork.impl.BasicUdpSimNetwork
import io.libp2p.quicsim.udpnetwork.impl.FifoUdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.UdpSimLatencyDelay

internal fun QuicNetworkTopology.toUdpSimNetwork(): UdpSimNetwork {
    val udpHosts = hosts.associate { it.id to UdpSimNode(it.id) }
    val udpRouters = routers.associate { it.id to UdpSimNode(it.id) }
    val udpNodesById = udpHosts + udpRouters
    val hostIds = udpHosts.keys
    return BasicUdpSimNetwork(
        nodes = hosts.map { udpHosts.getValue(it.id) },
        links = links.map { link ->
            val bandwidthQueue = FifoUdpSimBandwidthQueue(Bandwidth(link.bandwidthBytesPerSecond), link.maxQueueWaitTime)
            val latencyQueue = UdpSimLatencyDelay(link.latency)
            val qdisc = if (link.isNodeOutbound(hostIds))
                SerialPacketProcessor(listOf(latencyQueue, bandwidthQueue))
            else
                SerialPacketProcessor(listOf(bandwidthQueue, latencyQueue))
            UdpSimLink(
                from = udpNodesById.getValue(link.from),
                to = udpNodesById.getValue(link.to),
                bandwidthQueue = bandwidthQueue,
                latencyQueue = latencyQueue,
                qdisc = qdisc
            )
        }
    )
}

private fun QuicNetworkLink.isNodeOutbound(
    hostIds: Set<String>
): Boolean =
    when {
        from in hostIds && to !in hostIds -> true
        to in hostIds -> false
        else -> false
    }
