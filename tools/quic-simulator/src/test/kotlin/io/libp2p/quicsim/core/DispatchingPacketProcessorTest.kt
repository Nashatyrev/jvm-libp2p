package io.libp2p.quicsim.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class DispatchingPacketProcessorTest {

    @Test
    fun `dispatches packets by selected key preserving order within each delegate`() {
        val leftDelegate = RecordingPacketProcessor("left:")
        val rightDelegate = RecordingPacketProcessor("right:")
        val processor = DispatchingPacketProcessor(
            delegates = linkedMapOf(
                "left" to leftDelegate,
                "right" to rightDelegate
            ),
            selector = Packet::destination
        )

        val outbound = processor.deliver(
            listOf(
                Packet("left", "l1"),
                Packet("right", "r1"),
                Packet("left", "l2"),
                Packet("right", "r2"),
                Packet("left", "l3")
            )
        )

        assertEquals(
            listOf(
                Packet("left", "l1"),
                Packet("left", "l2"),
                Packet("left", "l3")
            ),
            leftDelegate.receivedInbound.flatten()
        )
        assertEquals(
            listOf(
                Packet("right", "r1"),
                Packet("right", "r2")
            ),
            rightDelegate.receivedInbound.flatten()
        )
        assertEquals(
            listOf(
                Packet("left", "left:l1"),
                Packet("left", "left:l2"),
                Packet("left", "left:l3"),
                Packet("right", "right:r1"),
                Packet("right", "right:r2")
            ),
            outbound
        )
    }

    @Test
    fun `returns outbound packets in delegate iteration order`() {
        val leftDelegate = RecordingPacketProcessor("left:")
        val rightDelegate = RecordingPacketProcessor("right:")
        val processor = DispatchingPacketProcessor(
            delegates = linkedMapOf(
                "right" to rightDelegate,
                "left" to leftDelegate
            ),
            selector = Packet::destination
        )

        val outbound = processor.deliver(
            listOf(
                Packet("left", "l1"),
                Packet("right", "r1")
            )
        )

        assertEquals(
            listOf(
                Packet("right", "right:r1"),
                Packet("left", "left:l1")
            ),
            outbound
        )
    }

    @Test
    fun `passes empty packet batches to delegates with no matching packets`() {
        val leftDelegate = RecordingPacketProcessor("left:")
        val rightDelegate = RecordingPacketProcessor("right:")
        val processor = DispatchingPacketProcessor(
            delegates = linkedMapOf(
                "left" to leftDelegate,
                "right" to rightDelegate
            ),
            selector = Packet::destination
        )

        val outbound = processor.deliver(listOf(Packet("left", "only")))

        assertEquals(listOf(listOf(Packet("left", "only"))), leftDelegate.receivedInbound)
        assertEquals(listOf(emptyList<Packet>()), rightDelegate.receivedInbound)
        assertEquals(listOf(Packet("left", "left:only")), outbound)
    }

    @Test
    fun `throws when a packet resolves to an unknown delegate key`() {
        val leftDelegate = RecordingPacketProcessor("left:")
        val processor = DispatchingPacketProcessor(
            delegates = mapOf("left" to leftDelegate),
            selector = Packet::destination
        )

        val exception = assertThrows(IllegalStateException::class.java) {
            processor.deliver(listOf(Packet("missing", "oops")))
        }

        assertEquals("Some packets cannot be delivered: [missing]", exception.message)
        assertEquals(emptyList<List<Packet>>(), leftDelegate.receivedInbound)
    }

    @Test
    fun `advanceAndExecuteAll delegates to every child processor`() {
        val leftDelegate = RecordingPacketProcessor("left:")
        val rightDelegate = RecordingPacketProcessor("right:")
        val processor = DispatchingPacketProcessor(
            delegates = mapOf(
                "left" to leftDelegate,
                "right" to rightDelegate
            ),
            selector = Packet::destination
        )

        processor.advanceAndExecuteAll(5.milliseconds)

        assertEquals(listOf(5.milliseconds), leftDelegate.advanceCalls)
        assertEquals(listOf(5.milliseconds), rightDelegate.advanceCalls)
    }

    @Test
    fun `nextTaskDuration returns the earliest scheduled task across delegates`() {
        val processor = DispatchingPacketProcessor(
            delegates = mapOf(
                "left" to RecordingPacketProcessor("left:", nextTask = 7.milliseconds),
                "right" to RecordingPacketProcessor("right:", nextTask = 3.milliseconds),
                "idle" to RecordingPacketProcessor("idle:", nextTask = null)
            ),
            selector = Packet::destination
        )

        assertEquals(3.milliseconds, processor.nextTaskDuration())
    }

    @Test
    fun `nextTaskDuration returns null when no delegate has scheduled work`() {
        val processor = DispatchingPacketProcessor(
            delegates = mapOf(
                "left" to RecordingPacketProcessor("left:", nextTask = null),
                "right" to RecordingPacketProcessor("right:", nextTask = null)
            ),
            selector = Packet::destination
        )

        assertNull(processor.nextTaskDuration())
    }

    private data class Packet(
        val destination: String,
        val payload: String
    )

    private class RecordingPacketProcessor(
        private val outboundPrefix: String,
        private val nextTask: Duration? = null
    ) : PacketProcessor<Packet> {
        val receivedInbound = mutableListOf<List<Packet>>()
        val advanceCalls = mutableListOf<Duration>()
        val outbound = mutableListOf<Packet>()

        override fun receivePackets(packets: List<Packet>) {
            receivedInbound += packets
            outbound += packets.map { it.copy(payload = outboundPrefix + it.payload) }
        }

        override fun emitPackets(): List<Packet> {
            val emitted = outbound.toList()
            outbound.clear()
            return emitted
        }

        override fun advance(advanceDuration: Duration) {
            advanceCalls += advanceDuration
        }

        override fun executePending() {}

        override fun nextTaskDuration(): Duration? = nextTask
    }
}
