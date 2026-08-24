package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.UdpSimBandwidthQueue
import io.netty.channel.socket.DatagramPacket
import kotlin.time.Duration

/** A pass-through stage for links whose bandwidth is intentionally not modelled. */
class UnshapedUdpSimBandwidthQueue : UdpSimBandwidthQueue {
    private var pendingPackets = mutableListOf<DatagramPacket>()
    override val bandwidth get() = Bandwidth(Bandwidth.INFINITE)

    override val maxQueueWaitTime: Duration = Duration.INFINITE

    override fun receivePackets(packets: List<DatagramPacket>) {
        pendingPackets += packets
    }

    override fun emitPackets(): List<DatagramPacket> {
        val ret = pendingPackets
        pendingPackets = mutableListOf()
        return ret
    }

    override fun advance(advanceDuration: Duration) = Unit

    override fun executePending() = Unit

    override fun nextTaskDuration(): Duration? = null
}
