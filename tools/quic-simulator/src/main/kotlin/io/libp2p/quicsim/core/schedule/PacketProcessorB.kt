package io.libp2p.quicsim.core.schedule

import io.libp2p.quicsim.core.PacketProcessor
import io.libp2p.quicsim.core.deliver
import kotlin.time.Duration

class PacketProcessorB<T>(
    private val delegate: PacketProcessor<T>,
) {

    private var cumulativeAdvance: Duration = Duration.ZERO
    private var outboundReady: List<T>? = null

    val nextTaskPoint: Duration? get() = nextTaskDuration()?.let { it + cumulativeAdvance }
    private val nexTaskPontOrInfinity get() = nextTaskPoint ?: Duration.INFINITE

    /**
     * Advances the time by [advanceDuration] and execute all tasks (if any) scheduled at this time point.
     * @throws IllegalStateException if there is any task scheduled for earlier time point
     */
    private fun advanceAndExecuteAll(advanceDuration: Duration) {
        cumulativeAdvance += advanceDuration
        delegate.advanceAndExecuteAll(advanceDuration)
    }

    fun advanceTillAndExecute(tillCumulative: Duration) {
        check(tillCumulative >= cumulativeAdvance)
        check(tillCumulative <= nexTaskPontOrInfinity)
        check(tillCumulative == cumulativeAdvance || outboundReady.isNullOrEmpty())
        advanceAndExecuteAll(tillCumulative - cumulativeAdvance)
    }

    private fun nextTaskDuration(): Duration? {
        val outboundLoc = outboundReady
        return if (outboundLoc.isNullOrEmpty()) {
            delegate.nextTaskDuration()
        } else {
            Duration.ZERO
        }

    }

    fun deliverOutbound(): List<T> {
        val outboundReadyLoc = outboundReady
        return if (outboundReadyLoc == null) {
            delegate.deliver(emptyList())
        } else {
            outboundReady = null
            outboundReadyLoc
        }
    }

    fun deliverInbound(inboundData: List<T>) {
        outboundReady = delegate.deliver(inboundData)
    }
}
