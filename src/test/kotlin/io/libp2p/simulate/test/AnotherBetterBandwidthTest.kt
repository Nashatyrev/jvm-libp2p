package io.libp2p.simulate.test

import io.libp2p.simulate.AnotherBetterBandwidthTracker
import io.libp2p.simulate.AnotherBetterBandwidthTracker.Message
import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.BetterBandwidthTracker
import org.assertj.core.api.Assertions
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

class AnotherBetterBandwidthTest {


    @Test
    fun testCalcDeliverTimes1() {
        val bandwidth = Bandwidth(1000)
        val msgs = listOf(
            Message(1000, 200_000),
            Message(1000, 200_000)
        )
        val t = AnotherBetterBandwidthTracker.calcDeliverTimes(bandwidth, msgs)

        Assertions.assertThat(t[0]).isCloseTo(202_000, Offset.offset(2))
        Assertions.assertThat(t[1]).isCloseTo(202_000, Offset.offset(2))
    }

    @Test
    fun testCalcDeliverTimes2() {
        val bandwidth = Bandwidth(1000)
        val msgs = listOf(
            Message(1000, 200_000),
            Message(1000, 200_500)
        )
        val t = AnotherBetterBandwidthTracker.calcDeliverTimes(bandwidth, msgs)

        Assertions.assertThat(t[0]).isCloseTo(201_500, Offset.offset(2))
        Assertions.assertThat(t[1]).isCloseTo(202_000, Offset.offset(2))
    }

    @Test
    fun testCalcDeliverTimes3() {
        val bandwidth = Bandwidth(1000)
        val msgs = listOf(
            Message(1000, 200_000),
            Message(50, 200_500)
        )
        val t = AnotherBetterBandwidthTracker.calcDeliverTimes(bandwidth, msgs)

        Assertions.assertThat(t[0]).isCloseTo(201_050, Offset.offset(2))
        Assertions.assertThat(t[1]).isCloseTo(200_600, Offset.offset(2))
    }

    @Test
    fun testCalcDeliverTimes4() {
        val bandwidth = Bandwidth(1000)
        val msgs = listOf(
            Message(1000, 200_000),
            Message(1, 200_500)
        )
        val t = AnotherBetterBandwidthTracker.calcDeliverTimes(bandwidth, msgs)

        Assertions.assertThat(t[0]).isCloseTo(201_002, Offset.offset(2))
        Assertions.assertThat(t[1]).isCloseTo(200_502, Offset.offset(2))
    }

    @Test
    fun testCalcDeliverTimes5() {
        val bandwidth = Bandwidth(1_000_000)
        val msgs = listOf(
            Message(1, 200_000),
            Message(1, 200_000)
        )
        val t = AnotherBetterBandwidthTracker.calcDeliverTimes(bandwidth, msgs)

        Assertions.assertThat(t[0]).isCloseTo(200_000, Offset.offset(2))
        Assertions.assertThat(t[1]).isCloseTo(200_000, Offset.offset(2))
    }

    @Test
    fun testCalcDeliverTimes6() {
        val bandwidth = Bandwidth(1_000_000)
        val msgs = listOf(
            Message(1, 200_000),
            Message(1, 200_000),
            Message(1, 200_000),
            Message(1, 200_000),
            Message(1, 200_000)
        )
        val t = AnotherBetterBandwidthTracker.calcDeliverTimes(bandwidth, msgs)

        Assertions.assertThat(t[0]).isCloseTo(200_000, Offset.offset(2))
        Assertions.assertThat(t[1]).isCloseTo(200_000, Offset.offset(2))
        Assertions.assertThat(t[2]).isCloseTo(200_000, Offset.offset(2))
        Assertions.assertThat(t[3]).isCloseTo(200_000, Offset.offset(2))
        Assertions.assertThat(t[4]).isCloseTo(200_000, Offset.offset(2))
    }

    @Test
    fun testCalcDeliverTimes7() {
        val bandwidth = Bandwidth(1_000)
        val msgs = listOf(
            Message(1000, 200_000),
            Message(1000, 200_000),
            Message(1000, 201_900),
            Message(10, 202_900),
        )
        val t = AnotherBetterBandwidthTracker.calcDeliverTimes(bandwidth, msgs)

        Assertions.assertThat(t[0]).isCloseTo(202_050, Offset.offset(2))
        Assertions.assertThat(t[1]).isCloseTo(202_050, Offset.offset(2))
        Assertions.assertThat(t[2]).isCloseTo(203_010, Offset.offset(2))
        Assertions.assertThat(t[3]).isCloseTo(202_920, Offset.offset(2))
    }

    @Test
    fun testCalcDeliverTimes8() {
        val bandwidth = Bandwidth(1_000_000)
        val msgs = listOf(
            Message(924956, 70_000),
            Message(924956, 70_000),
            Message(924956, 70_000),
            Message(1130435, 72_774),
        )
        val t = AnotherBetterBandwidthTracker.calcDeliverTimes(bandwidth, msgs)

        Assertions.assertThat(t[0]).isCloseTo(72_775, Offset.offset(2))
        Assertions.assertThat(t[1]).isCloseTo(72_775, Offset.offset(2))
        Assertions.assertThat(t[2]).isCloseTo(72_775, Offset.offset(2))
        Assertions.assertThat(t[3]).isCloseTo(73_905, Offset.offset(2))
    }

    @Test
    fun testCalcDeliverTimes9() {
        val bandwidth = Bandwidth(1_000_000)
        val msgs = listOf(
            Message(900_000, 10_000),
            Message(900_000, 10_000),
            Message(1_100_000, 11_800),
        )
        val t = AnotherBetterBandwidthTracker.calcDeliverTimes(bandwidth, msgs)

        Assertions.assertThat(t[0]).isCloseTo(11_800, Offset.offset(2))
        Assertions.assertThat(t[1]).isCloseTo(11_800, Offset.offset(2))
        Assertions.assertThat(t[2]).isCloseTo(12_900, Offset.offset(2))
    }
}