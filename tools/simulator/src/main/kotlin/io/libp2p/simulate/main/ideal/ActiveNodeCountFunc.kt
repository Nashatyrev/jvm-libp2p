package io.libp2p.simulate.main.ideal

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.main.ideal.func.Func
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

typealias ActiveNodeCountFunc = Func<Duration, Int>

/**
 * Simple implementation based on 'rounds' when 1 round is the period equal to
 * message transmission time (`messageSize / bandwidth`) and the latency equals to [latencyRounds] rounds
 */
class SimpleRoundBasedActiveNodeCountFunc(
    val roundTime: Duration,
    val latencyRounds: Int,
    val maxNodes: Int
) : ActiveNodeCountFunc {

    private val nodeCountByRound: List<Int>

    init {
        var curNodeCount = 1
        val maxRoundCount = maxNodes
        val deliverySchedules = MutableList(maxRoundCount + latencyRounds + 1) { 0 }

        val nodeCount = mutableListOf<Int>()
        for (round in 0 until maxRoundCount) {
            curNodeCount += deliverySchedules[round]

            if (curNodeCount > maxNodes) break

            nodeCount += curNodeCount
            deliverySchedules[round + 1 + latencyRounds] = curNodeCount
        }

        nodeCountByRound = nodeCount
    }

    override fun invoke(t: Duration): Int {
        val round = (t / roundTime).toInt()
        return when {
            t < ZERO -> 0
            round >= nodeCountByRound.size -> maxNodes
            else -> nodeCountByRound[round]
        }
    }

    companion object {
        fun setupActiveNodesFunction(disseminationFunction: DisseminationFunction) {
            val roundTime = disseminationFunction.bandwidth.getTransmitTime(disseminationFunction.partSize)
            val latencyRounds = (disseminationFunction.latency / roundTime).roundToInt()
            disseminationFunction.activeNodesFunc = SimpleRoundBasedActiveNodeCountFunc(
                roundTime,
                latencyRounds,
                disseminationFunction.nodeCount
            )
        }
    }
}

/**
 * Quite simple but not very precise function.
 *
 * Calculates the number of active nodes based on the total size of delivered data across all nodes
 * The problem here is when e.g. 2 nodes receive 1/2 of a message this formula would yield +1 active
 * node while in fact there no new active nodes at this moment
 */
class IntegralActiveNodesFunction(
    val totalDeliverSizeFunc: Func<Duration, Long>,
    val messagePartSize: Long
) : ActiveNodeCountFunc {

    override fun invoke(t: Duration): Int =
        when {
            t < ZERO -> 0
            else -> 1 + (totalDeliverSizeFunc(t) / messagePartSize).toInt()
        }

    companion object {
        fun setupActiveNodesFunction(disseminationFunction: DisseminationFunction) {
            disseminationFunction.activeNodesFunc = IntegralActiveNodesFunction(
                { disseminationFunction.totalDeliverFunc(it).toLong() },
                disseminationFunction.partSize
            )
        }
    }
}

/**
 * Precise function with arbitrary latency
 */
class PreciseActiveNodesFunction(
    maxNodes: Int,
    latency: Duration,
    bandwidth: Bandwidth,
    partSize: Long
) : ActiveNodeCountFunc {
    private val bandwidthDuration = bandwidth.getTransmitTime(partSize)

    val activationTime = Array(maxNodes + 1) { ZERO }

    private enum class Type { TRANSMITTED, RECEIVED }
    private data class Event(
        val t: Duration,
        val type: Type
    ) : Comparable<Event> {
        private val id = idGen++
        override fun equals(other: Any?) = this === other
        override fun hashCode() = id
        override fun compareTo(other: Event) = comparator.compare(this, other)

        companion object {
            private var idGen = 0
            private val comparator = compareBy<Event> { it.t }.thenBy { it.id }
        }
    }

    init {
        val events = sortedSetOf<Event>()
        events += Event(ZERO, Type.RECEIVED) //
        var curActiveCount = 0
        var transmittedCount = 0

        while (true) {
            val event = events.pollFirst()!!
            when(event.type) {
                Type.TRANSMITTED -> {
                    if (transmittedCount < maxNodes) {
                        transmittedCount++
                        events += Event(event.t + bandwidthDuration, Type.TRANSMITTED)
                    }
                    events += Event(event.t + latency, Type.RECEIVED)
                }
                Type.RECEIVED -> {
                    curActiveCount++
                    if (curActiveCount == maxNodes)
                        break
                    activationTime[curActiveCount] = event.t
                    if (transmittedCount < maxNodes) {
                        transmittedCount++
                        events += Event(event.t + bandwidthDuration, Type.TRANSMITTED)
                    }
                }
            }
        }
    }

    override operator fun invoke(t: Duration): Int {
        if (t < ZERO) return 0
        val sIdx = activationTime.binarySearch(t)
        var idx = if (sIdx < 0) - sIdx - 1 - 1 else sIdx
        while(activationTime.getOrElse(idx + 1) { Duration.INFINITE } <= t) idx++
        return idx
    }

    companion object {
        fun setupActiveNodesFunction(disseminationFunction: DisseminationFunction) {
            disseminationFunction.activeNodesFunc = PreciseActiveNodesFunction(
                disseminationFunction.nodeCount,
                disseminationFunction.latency,
                disseminationFunction.bandwidth,
                disseminationFunction.partSize
            )
        }
    }
}

