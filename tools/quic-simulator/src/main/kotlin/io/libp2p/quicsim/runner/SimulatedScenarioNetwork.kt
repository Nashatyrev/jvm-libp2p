package io.libp2p.quicsim.runner

import io.libp2p.quicsim.scenario.QuicNetworkTopology
import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.UdpSimLink
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import io.libp2p.quicsim.udpnetwork.impl.BasicUdpSimNetwork
import io.libp2p.quicsim.udpnetwork.impl.FifoUdpSimQueueDiscipline

internal fun QuicNetworkTopology.toUdpSimNetwork(): UdpSimNetwork {
    val udpHosts = hosts.associate { it.id to UdpSimNode(it.id) }
    val udpRouters = routers.associate { it.id to UdpSimNode(it.id) }
    val udpNodesById = udpHosts + udpRouters
    return BasicUdpSimNetwork(
        nodes = hosts.map { udpHosts.getValue(it.id) },
        links = links.map { link ->
            UdpSimLink(
                from = udpNodesById.getValue(link.from),
                to = udpNodesById.getValue(link.to),
                qdisc = FifoUdpSimQueueDiscipline(
                    bandwidth = Bandwidth(link.bandwidthBytesPerSecond),
                    latency = link.latency,
                    maxQueueWaitTime = link.maxQueueWaitTime
                )
            )
        }
    )
}
