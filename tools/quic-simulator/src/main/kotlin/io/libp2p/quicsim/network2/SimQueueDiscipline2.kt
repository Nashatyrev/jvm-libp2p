package io.libp2p.quicsim.network2

import io.libp2p.quicsim.core.PacketProcessor
import io.libp2p.quicsim.network.SimPacket
import kotlin.time.Duration

interface SimQueueDiscipline2 : PacketProcessor<SimPacket> {

    val bandwidth: Bandwidth

    val latency: Duration

}