package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.core.PacketEmitter
import io.libp2p.quicsim.core.PacketReceiver
import kotlin.time.Duration

class LatencyQueue2<TPacket>(
    latency: Duration
) {

    /**
     * Packet emitter may be advanced independently but not further than one latency ahead of receiver
     */
    val emitter: PacketEmitter<TPacket> = TODO()

    /**
     * Packet receiver may be advanced independently to any point in the future. Submitted packets
     * are enqueued until [emitter] catch up the packets
     */
    val receiver: PacketReceiver<TPacket> = TODO()

}