package io.libp2p.quicsim.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class MappingPacketProcessorTest {

    @Test
    fun `maps packets to delegate type and back`() {
        val delegate = RecordingInnerPacketProcessor()
        val processor = MappingPacketProcessor(
            delegate = delegate,
            mapToInner = { outer: String -> outer.toInt() },
            mapToOuter = { inner: Int -> inner.toString() }
        )

        processor.receivePackets(listOf("1", "2", "3"))
        val outbound = processor.emitPackets()

        assertEquals(listOf(1, 2, 3), delegate.receivedInbound)
        assertEquals(listOf("10", "20", "30"), outbound)
    }

    @Test
    fun `delegates controllable methods`() {
        val delegate = RecordingInnerPacketProcessor(nextTask = 7.milliseconds)
        val processor = MappingPacketProcessor(
            delegate = delegate,
            mapToInner = { outer: String -> outer.toInt() },
            mapToOuter = { inner: Int -> inner.toString() }
        )

        processor.advanceAndExecuteAll(5.milliseconds)

        assertEquals(listOf(5.milliseconds), delegate.advanceCalls)
        assertEquals(7.milliseconds, processor.nextTaskDuration())
    }

    private class RecordingInnerPacketProcessor(
        private val nextTask: Duration? = null
    ) : PacketProcessor<Int> {
        val receivedInbound = mutableListOf<Int>()
        val advanceCalls = mutableListOf<Duration>()
        val outbound = mutableListOf<Int>()

        override fun receivePackets(packets: List<Int>) {
            receivedInbound += packets
            outbound += packets.map { it * 10 }
        }

        override fun emitPackets(): List<Int> {
            val emitted = outbound.toList()
            outbound.clear()
            return emitted
        }

        override fun advance(advanceDuration: Duration) {
            advanceCalls += advanceDuration
        }

        override fun executePending() {
        }

        override fun nextTaskDuration(): Duration? = nextTask
    }
}
