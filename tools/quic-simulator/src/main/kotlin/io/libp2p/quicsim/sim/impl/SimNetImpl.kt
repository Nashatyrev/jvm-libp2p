package io.libp2p.quicsim.sim.impl

import io.libp2p.quicsim.core.DispatchingPacketProcessor
import io.libp2p.quicsim.core.DispatchingPacketProcessor2
import io.libp2p.quicsim.sim.SimNet
import io.netty.channel.socket.DatagramPacket
import kotlin.time.Duration

class SimNetImpl(
    override val allNodes: List<SimNodeImpl>
) : SimNet<DatagramPacket> {

    val nodesByIp =
        allNodes.associateBy { it.ip }
    val nodePacketDispatcher =
        DispatchingPacketProcessor2(nodesByIp) { it.recipient().hostString }

    override fun deliver(inboundData: List<DatagramPacket>): List<DatagramPacket> =
        nodePacketDispatcher.deliver(inboundData)

    override fun advance(advanceDuration: Duration) =
        nodePacketDispatcher.advance(advanceDuration)

    override fun executePending() =
        nodePacketDispatcher.executePending()

    override fun nextTaskDuration(): Duration? =
        nodePacketDispatcher.nextTaskDuration()
}
