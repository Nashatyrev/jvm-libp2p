package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.ControllablePacketRouter
import io.libp2p.quicsim.core.InOutProcessor
import io.libp2p.quicsim.core.schedule.Controllable
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
    udpNet: UdpSimNetwork
) : AbstractSimPacketBridge() {

    private val udpNetworkEngine = UdpSimNetworkEngineImpl4(udpNet)
    private val nodePumps = createNodePumps(simNet, udpNet)

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
        udpNet: UdpSimNetwork
    ): List<Controllable> {
        val simNodeByIp = simNet.allNodes.associateBy { it.ip }
        return udpNet.nodes.map { udpNode ->
            val simNode = simNodeByIp.getValue(udpNode.id)
            createSimNodeWithUdpLinks(simNode, udpNode, udpNet)
        }
    }

    private fun createSimNodeWithUdpLinks(
        simNode: SimNode<DatagramPacket>,
        udpNode: UdpSimNode,
        udpNet: UdpSimNetwork
    ): Controllable {
        val inboundUdpLink = udpNet.links.first { it.to == udpNode }
        val outboundUdpLink = udpNet.links.first { it.from == udpNode }

        val aheadProcessor = InOutProcessor(
            emitter = inboundUdpLink.latencyQueue.emitter,
            receiver = outboundUdpLink.latencyQueue.receiver
        )
        return ControllablePacketRouter.createSimplePump(simNode, aheadProcessor)
    }
}
