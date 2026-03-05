package io.libp2p.quicsim.core

import io.libp2p.quicsim.core.schedule.AggregateControllable
import kotlin.time.Duration

class Orchestrator(
    private val network: SimCoreNet,
) : AggregateControllable(network.allNodes + network) {

    override fun advanceAndExecuteAll(advanceDuration: Duration) {
        super.advanceAndExecuteAll(advanceDuration)
        pumpPackets()
    }

    fun pumpPackets() {
        var allNodesOutboundPackets: List<SimCorePacket> = emptyList()
        do {
            val inboundPackets: List<SimCorePacket> = network.deliver(allNodesOutboundPackets)
            val nodeInboundPackets = inboundPackets
                .groupBy { network.getDestinationNode(it) }
            allNodesOutboundPackets = network.allNodes
                .flatMap { node ->
                    node.deliver(nodeInboundPackets[node] ?: emptyList())
                }
        } while (allNodesOutboundPackets.isNotEmpty())
    }
}