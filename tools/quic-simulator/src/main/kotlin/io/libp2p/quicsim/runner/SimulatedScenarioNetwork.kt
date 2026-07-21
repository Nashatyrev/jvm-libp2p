package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.PacketEmitter.Companion.createPacketProcessorAdapter
import io.libp2p.quicsim.core.PacketReceiver.Companion.createPacketProcessorAdapter
import io.libp2p.quicsim.core.SerialPacketProcessor
import io.libp2p.quicsim.core.schedule.impl.LatencyQueueImpl
import io.libp2p.quicsim.scenario.QuicNetworkLink
import io.libp2p.quicsim.scenario.QuicNetworkHost
import io.libp2p.quicsim.scenario.QuicNetworkTopology
import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.UdpSimLink
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import io.libp2p.quicsim.udpnetwork.impl.BasicUdpSimNetwork
import io.libp2p.quicsim.udpnetwork.impl.CodelUdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.FifoUdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.FqCodelUdpSimBandwidthQueue
import io.netty.channel.socket.DatagramPacket

internal fun QuicNetworkTopology.toUdpSimNetwork(
    bandwidthQueueDiscipline: BandwidthQueueDiscipline = BandwidthQueueDiscipline.SHADOW_LIKE,
    hostIdMapper: (Int, QuicNetworkHost) -> String = { _, host -> host.id }
): UdpSimNetwork {
    val udpHosts = hosts.mapIndexed { index, host ->
        host.id to UdpSimNode(hostIdMapper(index, host))
    }.toMap()
    val udpRouters = routers.associate { it.id to UdpSimNode(it.id) }
    val udpNodesById = udpHosts + udpRouters
    val hostIds = udpHosts.keys
    return BasicUdpSimNetwork(
        nodes = hosts.map { udpHosts.getValue(it.id) },
        links = links.map { link ->
            link.toUdpSimLink(
                from = udpNodesById.getValue(link.from),
                to = udpNodesById.getValue(link.to),
                isFromEndpoint = link.isNodeOutbound(hostIds),
                bandwidthQueueDiscipline = bandwidthQueueDiscipline
            )
        }
    )
}

fun QuicNetworkLink.toUdpSimLink(
    from: UdpSimNode,
    to: UdpSimNode,
    isFromEndpoint: Boolean,
    bandwidthQueueDiscipline: BandwidthQueueDiscipline = BandwidthQueueDiscipline.SHADOW_LIKE
): UdpSimLink {
    val bandwidth = Bandwidth(this.bandwidthBytesPerSecond)
    val bandwidthQueue = when (bandwidthQueueDiscipline) {
        BandwidthQueueDiscipline.FIFO -> FifoUdpSimBandwidthQueue(bandwidth, this.maxQueueWaitTime)
        BandwidthQueueDiscipline.FQ_CODEL -> FqCodelUdpSimBandwidthQueue(bandwidth, this.maxQueueWaitTime)
        BandwidthQueueDiscipline.CODEL -> CodelUdpSimBandwidthQueue(bandwidth)
        BandwidthQueueDiscipline.SHADOW_LIKE -> if (isFromEndpoint) {
            FifoUdpSimBandwidthQueue(bandwidth, this.maxQueueWaitTime)
        } else {
            CodelUdpSimBandwidthQueue(bandwidth)
        }
    }
    val latencyQueue = LatencyQueueImpl<DatagramPacket>(this.latency)
    val qdisc = if (isFromEndpoint)
        SerialPacketProcessor(
            listOf(
                latencyQueue.emitter.createPacketProcessorAdapter(),
                bandwidthQueue
            )
        )
    else
        SerialPacketProcessor(
            listOf(
                bandwidthQueue,
                latencyQueue.receiver.createPacketProcessorAdapter()
            )
        )
    return UdpSimLink(
        from = from,
        to = to,
        bandwidthQueue = bandwidthQueue,
        latencyQueue = latencyQueue,
        qdisc = qdisc
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
