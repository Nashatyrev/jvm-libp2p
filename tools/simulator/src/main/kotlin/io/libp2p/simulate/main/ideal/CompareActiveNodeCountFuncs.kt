package io.libp2p.simulate.main.ideal

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.main.ideal.DisseminationFunction.Companion.solveIncreasingFunc
import io.libp2p.simulate.util.max
import io.libp2p.simulate.util.toString
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

fun main() {

    val latencyRounds = 10
    val msgSize = 1024L * 1024
    val partCount = 128
    val nodeCount = 100_000
    val bandwidth = Bandwidth(msgSize)
    val latency = 1.seconds * latencyRounds

    fun createDissemitationFunction(activeNodesFunctionSetup: (DisseminationFunction) -> Unit) =
        DisseminationFunction(
            bandwidth, latency, nodeCount, msgSize, partCount,
        ).also {
            activeNodesFunctionSetup(it)
        }

    val simpleDisseminationFunction =
        createDissemitationFunction(SimpleRoundBasedActiveNodeCountFunc::setupActiveNodesFunction)

    val preciseDisseminationFunction =
        createDissemitationFunction(PreciseActiveNodesFunction::setupActiveNodesFunction)

    val integralDisseminationFunction =
        createDissemitationFunction(IntegralActiveNodesFunction::setupActiveNodesFunction)

    val totalDeliverTarget = (msgSize * nodeCount).toDouble()

    val activeNodesAll1 = solveIncreasingFunc(simpleDisseminationFunction.activeNodesFunc, nodeCount)
    val activeNodesAll2 = solveIncreasingFunc(preciseDisseminationFunction.activeNodesFunc, nodeCount)
    val activeNodesAll3 = solveIncreasingFunc(integralDisseminationFunction.activeNodesFunc, nodeCount)

    val deliveredTime1 = solveIncreasingFunc(simpleDisseminationFunction.totalDeliverFunc, totalDeliverTarget)
    val deliveredTime2 = solveIncreasingFunc(preciseDisseminationFunction.totalDeliverFunc, totalDeliverTarget)
    val deliveredTime3 = solveIncreasingFunc(integralDisseminationFunction.totalDeliverFunc, totalDeliverTarget)

    val pivotTimes =
        listOf(activeNodesAll1, activeNodesAll2, activeNodesAll3, deliveredTime1, deliveredTime2, deliveredTime3)
    val capTime = pivotTimes.max()

    val times =
        (generateSequence(ZERO) { it + 1.seconds }.takeWhile { it <= capTime } +
                pivotTimes)
            .toSortedSet()

    for (t in times) {
        fun Double.deliverPercentString() = (this * 100 / totalDeliverTarget).toString(3)

        val activeNodes1 = simpleDisseminationFunction.cappedActiveNodesFunc(t)
        val activeNodes2 = preciseDisseminationFunction.cappedActiveNodesFunc(t)
        val activeNodes3 = integralDisseminationFunction.cappedActiveNodesFunc(t)
        val delivered1 = simpleDisseminationFunction.totalDeliverFunc(t)
        val delivered2 = preciseDisseminationFunction.totalDeliverFunc(t)
        val delivered3 = integralDisseminationFunction.totalDeliverFunc(t)
        println(
            "$t\t$activeNodes1\t$activeNodes2\t$activeNodes3\t" +
                    "${delivered1.deliverPercentString()}\t${delivered2.deliverPercentString()}\t${delivered3.deliverPercentString()}"
        )
    }
    println("Done")
}
