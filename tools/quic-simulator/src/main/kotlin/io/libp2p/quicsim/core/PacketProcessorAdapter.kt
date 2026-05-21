package io.libp2p.quicsim.core

import io.libp2p.etc.types.lazyVar
import io.libp2p.quicsim.udpnetwork.UdpSimQueueDiscipline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

abstract class PacketProcessorAdapter<TPacket> : PacketProcessor<TPacket> {
    private var cumulativeAdvanceMutable: Duration = ZERO
    val cumulativeAdvance get() = cumulativeAdvanceMutable

    private var nextDuration by lazyVar { nextTaskDurationImpl() }
    var deliverRequests = 0L
    var deliverCalls = 0L
    var advanceCalls = 0L
    var advanceRequests = 0L
    var nextDurationCalls = 0L
    var inboundCount = 0L
    var outboundCount = 0L

    override fun deliver(inboundData: List<TPacket>): List<TPacket> {
        inboundCount += inboundData.size
        deliverRequests++
        if (inboundData.isEmpty() && (nextDuration == null || nextDuration!! > ZERO)) {
            return emptyList()
        }
        deliverCalls++
        val ret = deliverImpl(inboundData)
        nextDuration = nextTaskDurationImpl()
        outboundCount += ret.size
        return ret
    }

    override fun advance(advanceDuration: Duration) {
        advanceRequests++
        cumulativeAdvanceMutable += advanceDuration
        if (nextDuration == null) {
            return
        }
        val nexDur = nextDuration!!
        val newNexDur = nexDur - advanceDuration
        nextDuration = newNexDur
        if (newNexDur > ZERO) {
            return
        }
    }

    override fun executePending() {
        if (nextDuration == null || nextDuration!! > ZERO) {
            return
        }
        advanceCalls++
        nextDurationCalls++
        executePendingImpl()
        nextDuration = nextTaskDurationImpl()
    }

    override fun nextTaskDuration(): Duration? {
        return nextDuration
    }

//    override fun deliver(inboundData: List<TPacket>): List<TPacket> {
//        deliverCalls++
//        return deliverImpl(inboundData)
//    }
//    override fun nextTaskDuration(): Duration? {
//        nextDurationCalls++
//        return nextTaskDurationImpl()
//    }
//    override fun advanceAndExecuteAll(advanceDuration: Duration) {
//        advanceCalls++
//        currentTime += advanceDuration
//        advanceAndExecuteAllImpl(advanceDuration)
//    }

    abstract fun deliverImpl(inboundData: List<TPacket>): List<TPacket>
    abstract fun executePendingImpl()
    abstract fun nextTaskDurationImpl(): Duration?

}
