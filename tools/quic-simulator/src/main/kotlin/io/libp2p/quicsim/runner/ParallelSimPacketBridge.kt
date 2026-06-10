package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.ControllablePacketPump
import io.libp2p.quicsim.core.InOutProcessor
import io.libp2p.quicsim.core.MappingPacketProcessor.Companion.map
import io.libp2p.quicsim.core.schedule.AggregateControllable
import io.libp2p.quicsim.core.schedule.Controllable
import io.libp2p.quicsim.core.schedule.Controllable.Companion.advanceAndExecuteUntil
import io.libp2p.quicsim.sim.SimNet
import io.libp2p.quicsim.sim.SimNode
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkEngine
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import io.netty.channel.socket.DatagramPacket
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

class ParallelSimPacketBridge(
    val simNet: SimNet<DatagramPacket>,
    val udpNet: UdpSimNetworkEngine,
    val idAndIp: Collection<IdMapEntry>,
) : AbstractSimPacketBridge(idAndIp) {

    data class SimNodeWithUdpLinks(
        val simNode: SimNode<DatagramPacket>,
        val pump: ControllablePacketPump<DatagramPacket>
    )

    val latency = calcLatency()
    val allNodes = createAllNodes()

    private fun calcLatency(): Duration {
        val latencies = udpNet.network.links.map { it.latencyQueue.latency }.distinct()
        require(latencies.size == 1) { "All nodes must have the same latency" }
        return latencies.first()
    }

    private fun createAllNodes(): List<SimNodeWithUdpLinks> {
        val simNodeByIp = simNet.allNodes.associateBy { it.ip }
        val udpNodeById = udpNet.network.nodes.associateBy { it.id }
        val nodesWithLinks = idAndIp.map { (id, ip) ->
            val simNode = simNodeByIp[ip]!!
            val udpNode = udpNodeById[id]!!
            createSimNodeWithUdpLinks(simNode, udpNode)
        }
        return nodesWithLinks
    }

    private fun createSimNodeWithUdpLinks(simNode: SimNode<DatagramPacket>, udpNode: UdpSimNode): SimNodeWithUdpLinks {
        val inboundUdpLink = udpNet.network.links.first { it.to == udpNode }
        val outboundUdpLink = udpNet.network.links.first { it.from == udpNode }

        val inboundAheadProcessor = inboundUdpLink.latencyQueue.aheadProcessor
        val outboundAheadProcessor = outboundUdpLink.latencyQueue.aheadProcessor

        val aheadProcessor = InOutProcessor(inboundAheadProcessor, outboundAheadProcessor)
        val aheadProcessorSim =
            aheadProcessor.map(nettyDatagramToSimUdpPacketConverter, simUdpPacketToNettyDatagramConverter)
        val controllable = ControllablePacketPump(simNode, aheadProcessorSim)
        return SimNodeWithUdpLinks(simNode, controllable)
    }

    override fun advanceImpl(advanceDuration: Duration) {
        require(advanceDuration == latency || advanceDuration == Duration.ZERO)
        allNodes.forEach {
            it.pump.advanceAndExecuteUntil(advanceDuration)
        }
        udpNet.advanceAndExecuteUntil(advanceDuration)
    }

    override fun executePending() {
    }

    override fun nextTaskDuration(): Duration? =
        latency
}