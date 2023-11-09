package io.libp2p.simulate.delay

import io.libp2p.simulate.BandwidthDelayer
import io.libp2p.simulate.SimPeerId
import io.libp2p.simulate.util.isOrderedAscending
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

abstract class AbstractBandwidthTrackerTest {

    val timeController = TimeControllerImpl()
    val executor = ControlledExecutorServiceImpl(timeController)

    abstract val tracker: BandwidthDelayer

    private var idCounter = 0

    inner class TestMessage(
        val time: Long,
        val size: Long,
        val remotePeer: SimPeerId = 0,
        val id: Int = idCounter++
    )

    data class MessageDelivery(
        val origMessage: TestMessage,
        val deliverTime: Long
    )

    protected fun calcDelayTimes(messages: List<TestMessage>): List<MessageDelivery> {
        assert(messages.map { it.time }.isOrderedAscending())

        val deliveries = mutableListOf<MessageDelivery>()

        messages.forEach { message ->
            timeController.time = message.time
            tracker.delay(message.remotePeer, message.size).thenAccept {
                deliveries += MessageDelivery(message, timeController.time)
            }
        }

        timeController.addTime(1.days.toJavaDuration())

        assertThat(deliveries).hasSize(messages.size)
        assertPeerMessagesOrdered(deliveries)

        return deliveries
    }

    protected fun assertPeerMessagesOrdered(msgs: List<MessageDelivery>) {
        val byPeerMessages = msgs
            .groupBy { it.origMessage.remotePeer }
            .values

        byPeerMessages
            .forEach {
                assertThat(it.map { it.origMessage.id }.isOrderedAscending()).isTrue()
            }
    }

    @Test
    fun `check small messages sent at same time  are delivered in order`() {
        val messages = listOf(
            TestMessage(1000, 1, 0),
            TestMessage(1000, 1, 0),
            TestMessage(1000, 1, 0),
            TestMessage(1000, 1, 1),
            TestMessage(1000, 1, 1),
            TestMessage(1000, 1, 1),
        )

        calcDelayTimes(messages)
    }
}