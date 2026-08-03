package io.libp2p.quicsim.runner

import com.google.common.collect.Comparators.min
import io.libp2p.quicsim.core.ControllablePacketRouter
import io.libp2p.quicsim.core.InOutProcessor
import io.libp2p.quicsim.core.schedule.Controllable
import io.libp2p.quicsim.runner.ParallelSimPacketBridge.SimNodeWithUdpLinks
import io.libp2p.quicsim.runner.graph.TimedNetworkLink
import io.libp2p.quicsim.runner.graph.TimedNetworkVertex
import io.libp2p.quicsim.sim.SimNet
import io.libp2p.quicsim.sim.SimNode
import io.libp2p.quicsim.udpnetwork.UdpSimLink
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import io.netty.channel.socket.DatagramPacket
import kotlin.time.Duration

class SimpleGeneralPacketBridge(
    val simNet: SimNet<DatagramPacket>,
    val udpNet: UdpSimNetwork,
) : AbstractSimPacketBridge() {

    abstract class GeneralNode(
        override val id: String
    ) : TimedNetworkVertex, Controllable {
        override var time: Duration = Duration.ZERO
    }

    class RouterNode(
        id: String,
        val router: ControllablePacketRouter<DatagramPacket>
    ) : GeneralNode(id), Controllable by router {

    }

    class EndpointNode(
        val simNode: SimNode<DatagramPacket>
    ) : GeneralNode("Node-" + simNode.ip), Controllable by simNode {

    }

    data class GeneralLink(
        val udpSimLinkL2R: UdpSimLink,
        val udpSimLinkR2L: UdpSimLink,
        override val left: GeneralNode,
        override val right: GeneralNode,
    ) : TimedNetworkLink<GeneralNode> {
        override val latency: Duration =
            min(udpSimLinkL2R.latencyQueue.minimalLatency, udpSimLinkR2L.latencyQueue.minimalLatency)
    }

    private fun createSimNodeWithUdpLinks(simNode: SimNode<DatagramPacket>, udpNode: UdpSimNode): SimNodeWithUdpLinks {
        val inboundUdpLink = udpNet.links.first { it.to == udpNode }
        val outboundUdpLink = udpNet.links.first { it.from == udpNode }

        val inboundEmitter = inboundUdpLink.latencyQueue.emitter
        val outboundReceiver = outboundUdpLink.latencyQueue.receiver

        val aheadProcessor = InOutProcessor(inboundEmitter, outboundReceiver)
        val controllable =
            ControllablePacketRouter.createSimplePump(simNode, aheadProcessor)
        return SimNodeWithUdpLinks(udpNode.id, simNode, controllable)
    }


    fun advanceWhile(
        predicate: () -> Boolean,
        afterTimeAdvanced: (Duration) -> Unit = {}
    ) {

    }

    override fun advanceImpl(advanceDuration: Duration) {
    }

    override fun executePending() {
    }

    override fun nextTaskDuration(): Duration? = TODO()
}
