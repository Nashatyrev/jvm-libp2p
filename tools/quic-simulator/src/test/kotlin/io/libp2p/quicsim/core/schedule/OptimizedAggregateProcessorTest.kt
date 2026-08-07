package io.libp2p.quicsim.core.schedule

import io.libp2p.quicsim.core.InOutProcessor
import io.libp2p.quicsim.core.PacketReceiver
import io.libp2p.quicsim.core.schedule.impl.LatencyQueueImpl
import io.libp2p.quicsim.core.schedule.impl.OptimizedAggregateProcessor
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class OptimizedAggregateProcessorTest {

    @Test
    fun `packet added to already scheduled emitter is not missed`() {
        val emitterQueue = LatencyQueueImpl<String>(10.milliseconds)
        val receiver = DelayedReceiver(100.milliseconds)
        val processor = OptimizedAggregateProcessor(
            listOf(
                InOutProcessor(emitterQueue.emitter, receiver)
            )
        )

        emitterQueue.receiver.receivePackets(listOf("earlier packet"))

        assertDoesNotThrow {
            processor.advance(processor.nextTaskDuration()!!)
        }
    }

    private class DelayedReceiver(
        private val initialDelay: Duration
    ) : PacketReceiver<String> {
        private var currentTime: Duration = Duration.ZERO
        private var pending = true

        override fun receivePackets(packets: List<String>) {
        }

        override fun advance(advanceDuration: Duration) {
            currentTime += advanceDuration
        }

        override fun executePending() {
            if (currentTime >= initialDelay) {
                pending = false
            }
        }

        override fun nextTaskDuration(): Duration? =
            if (pending) {
                initialDelay - currentTime
            } else {
                null
            }
    }
}
