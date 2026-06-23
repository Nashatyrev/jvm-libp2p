package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.core.PacketProcessor
import kotlin.time.Duration

interface UdpSimBandwidthQueue : PacketProcessor<UdpSimPacket> {
    val bandwidth: Bandwidth
    val maxQueueWaitTime: Duration
}
