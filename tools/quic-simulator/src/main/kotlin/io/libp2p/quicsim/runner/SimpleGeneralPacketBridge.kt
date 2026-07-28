package io.libp2p.quicsim.runner

import io.libp2p.quicsim.runner.graph.TimedNetworkLink
import io.libp2p.quicsim.runner.graph.TimedNetworkVertex
import io.libp2p.quicsim.sim.SimNet
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.netty.channel.socket.DatagramPacket
import kotlin.time.Duration

class SimpleGeneralPacketBridge(
    val simNet: SimNet<DatagramPacket>,
    val udpNet: UdpSimNetwork,
) : AbstractSimPacketBridge() {

    abstract class GeneralNode : TimedNetworkVertex {
        override var time: Duration = Duration.ZERO
    }

    data class GeneralLink(
        override val left: GeneralNode,
        override val right: GeneralNode,
        override val latency: Duration,
    ) : TimedNetworkLink<GeneralNode>

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
