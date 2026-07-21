package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.core.PacketProcessor
import io.netty.channel.socket.DatagramPacket
import kotlin.time.Duration

interface UdpSimBandwidthQueue : PacketProcessor<DatagramPacket> {
    val bandwidth: Bandwidth
    val maxQueueWaitTime: Duration
}
