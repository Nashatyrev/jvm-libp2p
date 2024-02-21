package io.libp2p.simulate.main.ideal

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.main.ideal.func.CachedFunc
import io.libp2p.simulate.main.ideal.func.Func
import io.libp2p.simulate.main.ideal.func.Integrated
import java.lang.Integer.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DisseminationFunction(
    val bandwidth: Bandwidth,
    val latency: Duration,
    val nodeCount: Int,
    val messageSize: Long,
    val partCount: Int,
    val calcDelta: Duration = 1.milliseconds
) {
    val partSize = messageSize / partCount
    val targetTotalDeliver = messageSize * nodeCount

    lateinit var activeNodesFunc: ActiveNodeCountFunc

    val cappedActiveNodesFunc: ActiveNodeCountFunc = CachedFunc { t ->
        min(nodeCount, activeNodesFunc(t))
    }

    val totalBandwidthFunc: Func<Duration, Bandwidth> = CachedFunc { t ->
        bandwidth * cappedActiveNodesFunc(t)
    }

    val totalDeliverFunc: Func<Duration, Double> = Integrated(
        scrFunction = { totalBandwidthFunc(it - latency) },
        minusXFunction = { x1, x2 -> x1 - x2 },
        mulXYFunction = { x, y -> y.getTransmitSize(x) },
        integrationStep = calcDelta,
        minX = ZERO
    )

    val totalSentFunc: Func<Duration, Double> = Integrated(
        scrFunction = { totalBandwidthFunc(it - calcDelta) },
        minusXFunction = { x1, x2 -> x1 - x2 },
        mulXYFunction = { x, y -> y.getTransmitSize(x) },
        integrationStep = calcDelta,
        minX = ZERO
    )

    companion object {

        fun <Y : Comparable<Y>> solveIncreasingFunc(
            func: Func<Duration, Y>,
            target: Y,
            initialStep: Duration = 10.seconds,
            minimalStep: Duration = 1.milliseconds
        ): Duration {
            var t = ZERO
            var step = initialStep
            var f = func(t)
            while (step > minimalStep) {
                while (f < target) {
                    t+= step
                    f = func(t)
                }
                step /= 2
                while (f >= target) {
                    t-= step
                    f = func(t)
                }
                step /= 2
            }
            while (f < target) {
                t+= step
                f = func(t)
            }
            return t
        }

        private operator fun Bandwidth.times(n: Int) = Bandwidth(this.bytesPerSecond * n)
    }
}