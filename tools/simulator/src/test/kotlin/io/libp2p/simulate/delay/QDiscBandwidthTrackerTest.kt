package io.libp2p.simulate.delay

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.BandwidthDelayer
import io.libp2p.simulate.delay.bandwidth.FairQDisk
import io.libp2p.simulate.delay.bandwidth.QDiscBandwidthTracker
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QDiscBandwidthTrackerTest : AbstractBandwidthTrackerTest() {

    val qDisc = FairQDisk { timeController.time }

    val bandwidth = Bandwidth(2000)
    override val tracker: QDiscBandwidthTracker = QDiscBandwidthTracker(bandwidth, executor, qDisc)

    @Test
    fun `check the qdisc is fair 1`() {
        val messages: List<TestMessage> = listOf(
            TestMessage(1000, 1000, 0, 0),
            TestMessage(1000, 1000, 0, 1),
            TestMessage(1000, 1, 1, 2),
            TestMessage(1000, 1, 1, 3),
        )

        val deliveries = calcDelayTimes(messages)

        assertThat(deliveries.getById(0).deliverTime).isEqualTo(1500)
        assertThat(deliveries.getById(1).deliverTime).isEqualTo(2000)
        assertThat(deliveries.getById(2).deliverTime).isEqualTo(1500)
        assertThat(deliveries.getById(3).deliverTime).isEqualTo(1500)
    }

    @Test
    fun `check the qdisc is fair 2`() {
        val messages: List<TestMessage> = listOf(
            TestMessage(1000, 1000, 0, 0),
            TestMessage(1400, 1000, 0, 1),
            TestMessage(1450, 1100, 1, 2),
        )

        val deliveries = calcDelayTimes(messages)

        assertThat(deliveries.getById(0).deliverTime).isEqualTo(1500)
        assertThat(deliveries.getById(2).deliverTime).isEqualTo(2050)
        assertThat(deliveries.getById(1).deliverTime).isEqualTo(2550)
    }

    @Test
    fun `check multiple peers mutiple messages`() {
        val peerCount = 10
        val messageSize = 9L
        val messageCount = 50
        val messages = (0 until peerCount).flatMap { peer ->
            (0 until  messageCount).map {
                TestMessage(0, messageSize, peer)
            }
        }

        val deliveries = calcDelayTimes(messages)
        val lastDeliver = deliveries.maxOf { it.deliverTime }

        val totalThroughput = peerCount * messageCount * messageSize
        val expectedTime = bandwidth.getTransmitTime(totalThroughput)

        println("Last deliver: $lastDeliver, expected: $expectedTime")

        assertThat(lastDeliver).isGreaterThanOrEqualTo(expectedTime.inWholeMilliseconds)
        assertThat(lastDeliver).isLessThan(expectedTime.inWholeMilliseconds + 100)
    }

    fun List<MessageDelivery>.getById(id: Int) =
        this.find { it.origMessage.id == id } ?: throw NoSuchElementException("No delivery with id $id")
}