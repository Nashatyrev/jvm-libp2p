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

    override val tracker: QDiscBandwidthTracker = QDiscBandwidthTracker(Bandwidth(2000), executor, qDisc)

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

    fun List<MessageDelivery>.getById(id: Int) =
        this.find { it.origMessage.id == id } ?: throw NoSuchElementException("No delivery with id $id")
}