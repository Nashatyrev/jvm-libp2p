package io.libp2p.simulate.main.ideal

import io.libp2p.simulate.Bandwidth
import java.lang.Integer.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun main() {
    val latencyRounds = 3

    val roundCount = 5000
    var curNodeCount = 1
    var prevNodeCount = 1
    val deliverySchedules = MutableList(roundCount + latencyRounds + 1) { 0 }

    val msgSize = 1024L * 1024
    val disseminationFunction = DisseminationFunction(
        bandwidth = Bandwidth(msgSize),
        latency = 1.seconds * latencyRounds,
        nodeCount = 1_000_000,
        messageSize = msgSize,
        partsCount = 1,
    )

    var activeNodes = 0
    generateSequence(0.milliseconds) { it + 1.milliseconds }
        .forEach { t ->
            val nodes = disseminationFunction.activeNodes(t)
            if (nodes != activeNodes) {
                activeNodes = nodes
                println("$t, $activeNodes")
            }
//            println("  $t: " + disseminationFunction.deliver(t))
        }

    for (round in 0 until roundCount) {
        prevNodeCount = curNodeCount
        curNodeCount += deliverySchedules[round]

        if (curNodeCount < 0) break

        deliverySchedules[round + 1 + latencyRounds] = curNodeCount
        val expBase = curNodeCount.toDouble() / prevNodeCount
        val t = 1.seconds * round
        val funcCount = disseminationFunction.activeNodes(t)
        println("$round\t$curNodeCount\t$funcCount\t$expBase")
    }
}

class DisseminationFunction(
    private val bandwidth: Bandwidth,
    private val latency: Duration,
    private val nodeCount: Int,
    private val messageSize: Long,
    private val partsCount: Int,
    private val calcDelta: Duration = 1.milliseconds
) {
    private val partSize = messageSize / partsCount

    val activeNodes: Func<Duration, Int> = Cached { t ->
        when {
            t < ZERO -> 0
            else -> 1 + (deliver(t) / partSize).toInt()
        }
    }

    val totalBandwidth: Func<Duration, Bandwidth> = Cached {
        val nodes = activeNodes(it)
        bandwidth * min(nodes, nodeCount)
    }

    val deliver: Func<Duration, Double> = Integrated(
        scrFunction = { totalBandwidth(it - latency) },
        minusXFunction = { x1, x2 -> x1 - x2 },
        mulXYFunction = { x, y -> y.getTransmitSize(x)},
        integrationStep = calcDelta,
        minX = ZERO
    )

    interface Func<X, Y> {
        operator fun invoke(x: X): Y
    }

    private class Cached<K, V>(
        private val f: (K) -> V
    ) : Func<K, V> {
        val cache = mutableMapOf<K, V>()

        override operator fun invoke(key: K): V {
            val maybeCached = cache[key]
            return when(maybeCached) {
                null -> {
                    val v = f(key)
                    cache[key] = v
                    v
                }
                else -> maybeCached
            }
        }
    }

    private operator fun Bandwidth.times(n: Int) = Bandwidth(this.bytesPerSecond * n)
}