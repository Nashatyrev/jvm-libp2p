package io.libp2p.quicsim.core

import io.libp2p.quicsim.core.schedule.impl.LatencyQueueImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class InOutProcessorTest {

    @Test
    fun `forwards packet added notifications from emitter`() {
        val inboundQueue = LatencyQueueImpl<String>(10.milliseconds)
        val outboundQueue = LatencyQueueImpl<String>(10.milliseconds)
        val processor = InOutProcessor(
            emitter = inboundQueue.emitter,
            receiver = outboundQueue.receiver
        )
        var notificationCount = 0

        processor.addPacketAddedListener {
            notificationCount++
        }

        inboundQueue.receiver.receivePackets(listOf("inbound"))
        assertEquals(1, notificationCount)

        processor.receivePackets(listOf("outbound"))
        assertEquals(1, notificationCount)
    }
}
