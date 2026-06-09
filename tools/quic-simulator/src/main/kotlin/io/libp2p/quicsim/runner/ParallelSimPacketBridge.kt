package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.ControllablePacketPump
import io.libp2p.quicsim.core.InOutProcessor
import io.libp2p.quicsim.core.MappingPacketProcessor.Companion.map
import io.libp2p.quicsim.core.schedule.AggregateControllable
import io.libp2p.quicsim.core.schedule.Controllable
import io.libp2p.quicsim.sim.SimNet
import io.libp2p.quicsim.sim.SimNode
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkEngine
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import io.netty.channel.socket.DatagramPacket
import kotlin.time.Duration

class ParallelSimPacketBridge(
    private val simNet: SimNet<DatagramPacket>,
    private val udpNet: UdpSimNetworkEngine,
    idAndIp: Collection<IdMapEntry>,
) : AbstractSimPacketBridge(idAndIp) {

    data class SimNodeWithUdpLinks(
        val simNode: SimNode<DatagramPacket>,
        val pump: Controllable
    )

    fun createSimNodeWithUdpLinks(simNode: SimNode<DatagramPacket>, udpNode: UdpSimNode): SimNodeWithUdpLinks {
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
        TODO("Not yet implemented")
    }

    override fun nextTaskDuration(): Duration? {
        TODO("Not yet implemented")
    }
}