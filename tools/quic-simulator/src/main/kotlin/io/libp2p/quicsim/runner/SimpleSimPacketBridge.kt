package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.ControllablePacketPump
import io.libp2p.quicsim.core.InOutProcessor
import io.libp2p.quicsim.core.MappingPacketProcessor.Companion.map
import io.libp2p.quicsim.core.schedule.Controllable.Companion.advanceAndExecuteUntil
import io.libp2p.quicsim.sim.SimNet
import io.libp2p.quicsim.sim.SimNode
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl4
import io.netty.channel.socket.DatagramPacket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

class SimpleSimPacketBridge(
    simNet: SimNet<DatagramPacket>,
    udpNet: UdpSimNetwork,
    idAndIp: Collection<IdMapEntry>
) : AbstractSimPacketBridge(idAndIp) {

    private val udpNetworkEngine = UdpSimNetworkEngineImpl4(udpNet)
    private val nodePumps = createNodePumps(simNet, udpNet, idAndIp)

    override fun advanceImpl(advanceDuration: Duration) {
        nodePumps.forEach { pump ->
            if (advanceDuration == ZERO) {
                pump.executePending()
            } else {
                pump.advanceAndExecuteUntil(advanceDuration)
            }
        }
        udpNetworkEngine.advanceUntil(advanceDuration)
    }

    override fun executePending() {
    }

    override fun nextTaskDuration(): Duration? =
        (nodePumps.mapNotNull { it.nextTaskDuration() } + listOfNotNull(udpNetworkEngine.nextTaskDuration()))
            .minOrNull()

    private fun createNodePumps(
        simNet: SimNet<DatagramPacket>,
        udpNet: UdpSimNetwork,
        idAndIp: Collection<IdMapEntry>
    ): List<ControllablePacketPump<DatagramPacket>> {
        val simNodeByIp = simNet.allNodes.associateBy { it.ip }
        val udpNodeById = udpNet.nodes.associateBy { it.id }
        return idAndIp.map { (id, ip) ->
            val simNode = simNodeByIp.getValue(ip)
            val udpNode = udpNodeById.getValue(id)
            createSimNodeWithUdpLinks(simNode, udpNode, udpNet)
        }
    }

    private fun createSimNodeWithUdpLinks(
        simNode: SimNode<DatagramPacket>,
        udpNode: UdpSimNode,
        udpNet: UdpSimNetwork
    ): ControllablePacketPump<DatagramPacket> {
        val inboundUdpLink = udpNet.links.first { it.to == udpNode }
        val outboundUdpLink = udpNet.links.first { it.from == udpNode }

        val aheadProcessor = InOutProcessor(
            emitter = inboundUdpLink.latencyQueue.emitter,
            receiver = outboundUdpLink.latencyQueue.receiver
        )
        val aheadProcessorSim =
            aheadProcessor.map(nettyDatagramToSimUdpPacketConverter, simUdpPacketToNettyDatagramConverter)
        return ControllablePacketPump(simNode, aheadProcessorSim)
    }
}
