package io.libp2p.simulate.main.ideal

import com.google.common.collect.Comparators.max
import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.main.SimulationRunner
import io.libp2p.simulate.mbitsPerSecond
import io.libp2p.simulate.stats.ResultPrinter
import io.libp2p.simulate.util.cartesianProduct
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

fun main() {
    val t = 10.mbitsPerSecond.getTransmitTime(1024 * 1024)
    println("Time: $t")
    DisseminationFunctionSimulation().runAllAndPrintResults()
}

class DisseminationFunctionSimulation(
    val bandwidthParams: List<Bandwidth> = listOf(
        10.mbitsPerSecond,
        25.mbitsPerSecond,
        50.mbitsPerSecond,
        100.mbitsPerSecond,
        500.mbitsPerSecond,
    ),
    val latencyParams: List<Duration> = listOf(
        0.milliseconds,
        1.milliseconds,
        5.milliseconds,
        10.milliseconds,
        25.milliseconds,
        50.milliseconds,
        100.milliseconds,
        200.milliseconds,
        500.milliseconds,
        1000.milliseconds,
        2000.milliseconds,
    ),

    val msgPartCountParams: List<Int> = listOf(
        1,
        2,
        4,
        8,
        16,
        32,
        64,
        128,
        256,
        512,
        1024,
        2048,
        4 * 1024,
        8 * 1024,
        16 * 1024,
        256 * 1024,
        1024 * 1024,
    ),
    val messageSizeParams: List<Long> = listOf(
        1024 * 1024
    ),
    val nodeCountParams: List<Int> = listOf(
        10000
    ),


    val paramsSet: List<SimParams> =
        cartesianProduct(
            nodeCountParams,
            messageSizeParams,
            msgPartCountParams,
            bandwidthParams,
            latencyParams,
            DisseminationFunctionSimulation::SimParams
        ),
) {

    data class SimParams(
        val nodeCount: Int,
        val messageSize: Long,
        val msgPartCount: Int,
        val bandwidth: Bandwidth,
        val latency: Duration,
    )

    data class Result(
        val dissemT: Long,
        val allActiveT: Long,
    )

    fun runAll(): List<Result> =
        SimulationRunner<SimParams, Result>(
            threadCount = 1,
            printLocalLogging = false,
            runner = { params, _ ->
                run(params)
            })
            .runAll(paramsSet)

    fun run(params: SimParams): Result {
        fun Duration.roundMilliseconds() = this.toDouble(DurationUnit.MILLISECONDS).roundToLong()

        val disseminationFunc = DisseminationFunction(
            params.bandwidth,
            params.latency,
            params.nodeCount,
            params.messageSize,
            params.msgPartCount
        ).also {
            PreciseActiveNodesFunction.setupActiveNodesFunction(it)
        }

        val allActiveT = DisseminationFunction.solveIncreasingFunc(
            disseminationFunc.cappedActiveNodesFunc,
            disseminationFunc.nodeCount,
            initialStep = 1.seconds
        )
        val dissemT = DisseminationFunction.solveIncreasingFunc(
            disseminationFunc.totalDeliverFunc,
            disseminationFunc.targetTotalDeliver.toDouble(),
            initialStep = 1.seconds
        )
        return Result(max(dissemT, allActiveT).roundMilliseconds(), allActiveT.roundMilliseconds())
    }

    private fun printResults(res: Map<SimParams, Result>) {
        val printer = ResultPrinter(res).apply {
            addPropertiesAsMetrics { it }
        }

        println("\nResult:\n")
        println(printer.printPretty())

        println()
        println(printer.printTabSeparated())
    }

    fun runAllAndPrintResults() {
        val results = runAll()
        printResults(paramsSet.zip(results).toMap())
    }
}
