package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.core.PacketProcessor
import kotlin.time.Duration

abstract class PacketProcessorAdapter<TPacket> : PacketProcessor<TPacket> {
    private var cumulativeAdvanceMutable: Duration = Duration.Companion.ZERO
    val cumulativeAdvance get() = cumulativeAdvanceMutable

    override fun advance(advanceDuration: Duration) {
        cumulativeAdvanceMutable += advanceDuration
    }
}