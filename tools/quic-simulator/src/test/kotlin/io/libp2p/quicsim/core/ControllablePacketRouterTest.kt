package io.libp2p.quicsim.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import io.libp2p.quicsim.core.schedule.impl.LatencyQueueImpl
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ControllablePacketRouterTest {

    @Test
    fun `simple pump routes packets between two processors until idle`() {
        val left = recordingRoute(
            outbound = mutableListOf(Packet(route = 0, payload = "from-left"))
        )
        val right = recordingRoute()
        val router = ControllablePacketRouter.createSimplePump(left.processor, right.processor)

        router.pumpPackets()

        assertEquals(
            emptyList<List<Packet>>(),
            left.receivedInbound
        )
        assertEquals(
            listOf(listOf(Packet(route = 0, payload = "from-left"))),
            right.receivedInbound
        )
    }

    @Test
    fun `routes packets through multiple processors`() {
        val first = recordingRoute(
            outbound = mutableListOf(Packet(route = 1, payload = "first-to-second"))
        )
        val second = recordingRoute(
            transformInbound = { Packet(route = 2, payload = it.payload + "-to-third") }
        )
        val third = recordingRoute()
        val router = ControllablePacketRouter(
            routeProcessors = listOf(first.processor, second.processor, third.processor),
            routeSelector = { _, packet -> packet.route }
        )

        router.advanceAndExecuteAll(Duration.ZERO)
        router.advanceAndExecuteAll(Duration.ZERO)

        assertEquals(emptyList<List<Packet>>(), first.receivedInbound)
        assertEquals(listOf(listOf(Packet(route = 1, payload = "first-to-second"))), second.receivedInbound)
        assertEquals(listOf(listOf(Packet(route = 2, payload = "first-to-second-to-third"))), third.receivedInbound)
    }

    @Test
    @Timeout(1)
    fun `consumes inbound packets after routing them`() {
        val left = recordingRoute(
            outbound = mutableListOf(Packet(route = 0, payload = "request"))
        )
        val right = recordingRoute(
            transformInbound = { Packet(route = 0, payload = "response") }
        )
        val router = ControllablePacketRouter.createSimplePump(left.processor, right.processor)

        router.pumpPackets()

        assertEquals(
            listOf(listOf(Packet(route = 0, payload = "response"))),
            left.receivedInbound
        )
        assertEquals(
            listOf(listOf(Packet(route = 0, payload = "request"))),
            right.receivedInbound
        )
    }

    @Test
    fun `delegates controllable methods to route processors`() {
        val first = recordingRoute(nextTask = 10.milliseconds)
        val second = recordingRoute(nextTask = 3.milliseconds)
        val third = recordingRoute(nextTask = 3.milliseconds)
        val router = ControllablePacketRouter(
            routeProcessors = listOf(first.processor, second.processor, third.processor),
            routeSelector = { _, packet -> packet.route }
        )

        assertEquals(3.milliseconds, router.nextTaskDuration())

        router.advanceAndExecuteAll(router.nextTaskDuration()!!)

        assertEquals(emptyList<Duration>(), first.advanceCalls)
        assertEquals(listOf(3.milliseconds), second.advanceCalls)
        assertEquals(listOf(3.milliseconds), third.advanceCalls)
    }

    @Test
    fun `nextTaskDuration returns null when all route processors are idle`() {
        val router = ControllablePacketRouter(
            routeProcessors = listOf(
                recordingRoute().processor,
                recordingRoute().processor
            ),
            routeSelector = { _, packet -> packet.route }
        )

        assertNull(router.nextTaskDuration())
    }

    @Test
    fun `syncs idle route before delivering packets to it`() {
        val outboundQueue = LatencyQueueImpl<Packet>(60.milliseconds)
        val inboundQueue = LatencyQueueImpl<Packet>(10.milliseconds)
        outboundQueue.receiver.receivePackets(listOf(Packet(route = 1, payload = "from-left")))
        val router = ControllablePacketRouter(
            routeProcessors = listOf(
                InOutProcessor(outboundQueue.emitter, outboundQueue.receiver),
                InOutProcessor(inboundQueue.emitter, inboundQueue.receiver)
            ),
            routeSelector = { _, packet -> packet.route }
        )

        router.advanceAndExecuteAll(router.nextTaskDuration()!!)

        assertEquals(10.milliseconds, router.nextTaskDuration())
    }

    private data class Packet(
        val route: RouteId,
        val payload: String
    )

    private fun recordingRoute(
        outbound: MutableList<Packet> = mutableListOf(),
        transformInbound: (Packet) -> Packet? = { null },
        nextTask: Duration? = null
    ): RecordingRoute {
        val emitter = RecordingEmitter(outbound, nextTask)
        val receiver = RecordingReceiver(outbound, transformInbound)
        return RecordingRoute(
            processor = InOutProcessor(emitter, receiver),
            emitter = emitter,
            receiver = receiver
        )
    }

    private data class RecordingRoute(
        val processor: InOutProcessor<Packet>,
        val emitter: RecordingEmitter,
        val receiver: RecordingReceiver,
    ) {
        val receivedInbound: List<List<Packet>> get() = receiver.receivedInbound
        val advanceCalls: List<Duration> get() = emitter.advanceCalls
    }

    private class RecordingEmitter(
        private val outbound: MutableList<Packet>,
        private val nextTask: Duration?
    ) : NotifyingPacketEmitter<Packet> {
        val advanceCalls = mutableListOf<Duration>()

        override fun emitPackets(): List<Packet> {
            val emitted = outbound.toList()
            outbound.clear()
            return emitted
        }

        override fun addPacketAddedListener(listener: () -> Unit) {
        }

        override fun advance(advanceDuration: Duration) {
            advanceCalls += advanceDuration
        }

        override fun executePending() {
        }

        override fun nextTaskDuration(): Duration? =
            if (outbound.isNotEmpty()) Duration.ZERO else nextTask
    }

    private class RecordingReceiver(
        private val outbound: MutableList<Packet>,
        private val transformInbound: (Packet) -> Packet?
    ) : PacketReceiver<Packet> {
        val receivedInbound = mutableListOf<List<Packet>>()

        override fun receivePackets(packets: List<Packet>) {
            receivedInbound += packets.toList()
            outbound += packets.mapNotNull(transformInbound)
        }

        override fun advance(advanceDuration: Duration) {
        }

        override fun executePending() {
        }

        override fun nextTaskDuration(): Duration? =
            null
    }
}
