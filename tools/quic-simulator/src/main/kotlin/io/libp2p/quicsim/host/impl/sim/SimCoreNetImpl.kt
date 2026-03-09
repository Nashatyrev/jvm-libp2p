package io.libp2p.quicsim.host.impl.sim

import io.libp2p.quicsim.core.DispatchingPacketProcessor
import io.libp2p.quicsim.core.SimCoreNet
import io.netty.channel.socket.DatagramPacket
import kotlin.time.Duration

class SimCoreNetImpl(
    override val allNodes: List<EmbeddedNode>
) : SimCoreNet<DatagramPacket> {

    val nodesByIp =
        allNodes.associateBy { it.ip }
    val nodePacketDispatcher =
        DispatchingPacketProcessor(nodesByIp) { it.recipient().hostString }

    override fun deliver(inboundData: List<DatagramPacket>): List<DatagramPacket> =
        nodePacketDispatcher.deliver(inboundData)

    override fun advanceAndExecuteAll(advanceDuration: Duration) =
        nodePacketDispatcher.advanceAndExecuteAll(advanceDuration)

    override fun nextTaskDuration(): Duration? =
        nodePacketDispatcher.nextTaskDuration()
}