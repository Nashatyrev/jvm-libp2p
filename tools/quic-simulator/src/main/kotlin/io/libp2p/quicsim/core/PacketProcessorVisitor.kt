package io.libp2p.quicsim.core

import kotlin.time.Duration

interface PacketProcessorVisitor<TPacket> {

    fun onAdvance(advanceDuration: Duration) {}

    fun onExecutePending() {}

    fun onNextTaskDuration(nextTaskDuration: Duration?)  {}

    fun onDeliverInbound(inboundPacket: TPacket) {}

    fun onDeliverOutbound(outboundPacket: TPacket) {}

    companion object {
        fun <T> none() = object : PacketProcessorVisitor<T> {}
    }
}