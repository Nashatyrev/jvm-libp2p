package io.libp2p.quicsim.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ControllablePacketRouterTest {

    @Test
    fun `simple pump routes packets between two processors until idle`() {
        val left = RecordingPacketProcessor(
            outbound = mutableListOf(Packet(route = 0, payload = "from-left"))
        )
        val right = RecordingPacketProcessor()
        val router = ControllablePacketRouter.createSimplePump(left, right)

        router.pumpPackets()

        assertEquals(
            listOf(emptyList<Packet>()),
            left.receivedInbound
        )
        assertEquals(
            listOf(listOf(Packet(route = 0, payload = "from-left"))),
            right.receivedInbound
        )
    }

    @Test
    fun `routes packets through multiple processors`() {
        val first = RecordingPacketProcessor(
            outbound = mutableListOf(Packet(route = 1, payload = "first-to-second"))
        )
        val second = RecordingPacketProcessor(
            transformInbound = { Packet(route = 2, payload = it.payload + "-to-third") }
        )
        val third = RecordingPacketProcessor()
        val router = ControllablePacketRouter(
            routeProcessors = listOf(first, second, third),
            routeSelector = { _, packet -> packet.route }
        )

        router.pumpPackets()

        assertEquals(listOf(emptyList<Packet>()), first.receivedInbound)
        assertEquals(listOf(listOf(Packet(route = 1, payload = "first-to-second"))), second.receivedInbound)
        assertEquals(listOf(listOf(Packet(route = 2, payload = "first-to-second-to-third"))), third.receivedInbound)
    }

    @Test
    @Timeout(1)
    fun `consumes inbound packets after routing them`() {
        val left = RecordingPacketProcessor(
            outbound = mutableListOf(Packet(route = 0, payload = "request"))
        )
        val right = RecordingPacketProcessor(
            transformInbound = { Packet(route = 0, payload = "response") }
        )
        val router = ControllablePacketRouter.createSimplePump(left, right)

        router.pumpPackets()

        assertEquals(
            listOf(emptyList(), listOf(Packet(route = 0, payload = "response"))),
            left.receivedInbound
        )
        assertEquals(
            listOf(listOf(Packet(route = 0, payload = "request")), emptyList()),
            right.receivedInbound
        )
    }

    @Test
    fun `delegates controllable methods to route processors`() {
        val left = RecordingPacketProcessor(nextTask = 10.milliseconds)
        val right = RecordingPacketProcessor(nextTask = 3.milliseconds)
        val router = ControllablePacketRouter.createSimplePump(left, right)

        router.advanceAndExecuteAll(5.milliseconds)

        assertEquals(listOf(5.milliseconds), left.advanceCalls)
        assertEquals(listOf(5.milliseconds), right.advanceCalls)
        assertEquals(3.milliseconds, router.nextTaskDuration())
    }

    @Test
    fun `nextTaskDuration returns null when all route processors are idle`() {
        val router = ControllablePacketRouter.createSimplePump(
            RecordingPacketProcessor(),
            RecordingPacketProcessor()
        )

        assertNull(router.nextTaskDuration())
    }

    private data class Packet(
        val route: RouteId,
        val payload: String
    )

    private class RecordingPacketProcessor(
        private val outbound: MutableList<Packet> = mutableListOf(),
        private val transformInbound: (Packet) -> Packet? = { null },
        private val nextTask: Duration? = null
    ) : PacketProcessor<Packet> {
        val receivedInbound = mutableListOf<List<Packet>>()
        val advanceCalls = mutableListOf<Duration>()

        override fun receivePackets(packets: List<Packet>) {
            receivedInbound += packets.toList()
            outbound += packets.mapNotNull(transformInbound)
        }

        override fun emitPackets(): List<Packet> {
            val emitted = outbound.toList()
            outbound.clear()
            return emitted
        }

        override fun advance(advanceDuration: Duration) {
            advanceCalls += advanceDuration
        }

        override fun executePending() {
        }

        override fun nextTaskDuration(): Duration? =
            nextTask
    }
}
