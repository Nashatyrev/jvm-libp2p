package io.libp2p.simulate.main.tcp

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.main.tcp.TcpScenarios.NetworkLimitOption.*
import io.libp2p.simulate.mbitsPerSecond
import io.libp2p.simulate.util.cartesianProduct
import io.libp2p.tools.log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    TcpScenarios().runAll()
}

class TcpScenarios(
    val tcpOptionParams: List<TcpOption> =
        listOf(
//            TcpOption.Default,
            TcpOption.SlowStartIdleOff
        ),
    val bandwidthParams: List<Bandwidth> =
//        listOf(25.mbitsPerSecond, 50.mbitsPerSecond, 100.mbitsPerSecond),
        listOf(
            Bandwidth.UNLIM,
//            1000.mbitsPerSecond,
//            500.mbitsPerSecond,
//            100.mbitsPerSecond,
//            50.mbitsPerSecond,
//            20.mbitsPerSecond,
        ),
    val halfPingParams: List<Long> =
        listOf(100),
//        listOf(10, 20, 30, 40, 50, 75, 100, 125, 150),
//        listOf(100, 150, 200),
//        listOf(1, 10, 50, 100),
    val msgSizeParams: List<Int> =
        listOf(
            128 * 1024,
            1 * 1024 * 1024,
            5 * 1024 * 1024,
//            10 * 1024 * 1024,
//            100 * 1024 * 1024,
        ),
//        listOf(512 * 1024, 1024 * 1024),
//        listOf(512 * 1024, 128 * 1024, 16 * 1024, 2 * 1204, 1024),
    val clientCountParams: List<Int> =
        listOf(
            1,
            2,
            4,
            8,
            16,
            24,
            32
        ),
//        listOf(128, 64, 32, 16, 8, 4, 2, 1),
    val scenarioParams: List<Scenario> =
        listOf(Scenario.SimpleInbound),
//        listOf(Scenario.WarmupOutbound),
//        listOf(Direction.Inbound, Direction.Outbound),
    val staggeringParams: List<Double> =
        listOf(0.0),
//        listOf(0.0, 0.5, 1.0, 1.5),
//        listOf(0.0, 0.25, 0.5, 0.75, 1.0),

    val params: List<RunParams> = cartesianProduct(
        tcpOptionParams,
        bandwidthParams,
        halfPingParams,
        msgSizeParams,
        clientCountParams,
        scenarioParams,
        staggeringParams,
        ::RunParams
    ),
    val outFile: String = "tcp.res.json",
    val tcConfig: TcConfig = TcConfig("lo")
) {

    val messagesCount = 5
    val serverPort = 7777
    val clientPortStart = 8000
    val networkLimitOption = ClientPortSide
    val printOnlyLastWave: Boolean = true

    enum class NetworkLimitOption {
        ServerPortSide,
        ClientPortSide
    }

    enum class TcpOption {
        Default,
        SlowStartIdleOff
    }

    enum class Scenario {
        SimpleInbound,
        SimpleOutbound,
        WarmupOutbound
    }

    @kotlinx.serialization.Serializable
    data class RunParams(
        val tcpOption: TcpOption,
        val bandwidth: Bandwidth,
        val halfPing: Long,
        val msgSize: Int,
        val clientCount: Int,
        val scenario: Scenario,
        val staggering: Double
    ) : Comparable<RunParams> {
        val staggeringDelay
            get() =
                bandwidth.getTransmitTimeMillis(msgSize.toLong()).milliseconds * staggering

        override fun compareTo(other: RunParams): Int = comparator.compare(this, other)

        companion object {
            val comparator =
                compareBy<RunParams> { it.tcpOption }
                    .thenBy { it.bandwidth }
                    .thenBy { it.halfPing }
                    .thenBy { it.msgSize }
                    .thenBy { it.clientCount }
                    .thenBy { it.scenario }
                    .thenBy { it.staggering }
        }

    }

    var prevSystemOptions: RunParams? = null
    fun setSystemOptionsIfRequired(params: RunParams) {
        if (prevSystemOptions == null || prevSystemOptions!!.tcpOption != params.tcpOption) {
            tcConfig.setTcpSlowStartAferIdle(params.tcpOption == TcpOption.Default)
        }
        if (prevSystemOptions == null
            || prevSystemOptions!!.bandwidth != params.bandwidth || prevSystemOptions!!.halfPing != params.halfPing) {

            val inverse = when(networkLimitOption) {
                ServerPortSide -> false
                ClientPortSide -> true
            }
            tcConfig.setLimits(serverPort, params.bandwidth, params.halfPing.milliseconds, inverse)

        }
        prevSystemOptions = params
    }

    private fun File.appendPrintWriter() =
        PrintWriter(FileOutputStream(this, true).bufferedWriter())

    fun runAll() {
        val file = File(outFile)
        val existingParams = if (file.canRead()) {
            log("Loading existing file $file...")
            val params = TcpScenariosStats.load(outFile).keys.toSet()
            log("${params.size} existing results were found")
            params
        } else {
            emptySet()
        }

        log("Running ${params.size} param sets...")
        file.appendPrintWriter().use { writer ->
            params
                .withIndex()
                .map { (index, param) ->
                    if (param in existingParams) {
                        log("Skipping $param")
                    } else {
                        log("Running ${index + 1} of ${params.size}: $param")

                        setSystemOptionsIfRequired(param)

                        val res = run(param)

                        val valid = try {
                            TcpScenariosStats.validateWaves(res, param)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            false
                        }

                        if (!valid) {

                            File("tcp.err.json").printWriter().use { errW ->
                                errW.println("Params:" + Json.encodeToString(param))
                                res.forEach {
                                    errW.println("Event:" + Json.encodeToString(it))
                                }
                                errW.flush()
                            }

                            throw RuntimeException("Invalid waves for $param")
                        }

                        writer.println()
                        writer.println("Params:" + Json.encodeToString(param))
                        res.forEach {
                            writer.println("Event:" + Json.encodeToString(it))
                        }
                        writer.flush()

                    }
                }
        }

        log("Printing results...")
        TcpScenariosStats().printStats(listOf(outFile), printOnlyLastWave)

    }

    var startClientPort = clientPortStart

    private fun TcpMultiTest.runWarmupOutbound(eventRecordingHandler: EventRecordingHandler) {
        repeat(clientCount) { connectionNum ->
            log("Warming up #$connectionNum")
            repeat(messagesCount) {
                runOutboundSingle(connectionNum)
            }
        }
//        eventRecordingHandler.events.clear()
        log("Running")
        runOutbound()
    }

    fun run(params: RunParams): List<EventRecordingHandler.Event> {

        val recordingHandler = EventRecordingHandler()

        val test = TcpMultiTest(
            msgSize = params.msgSize,
            clientCount = params.clientCount,
            staggeringDelay = params.staggeringDelay,
            loggersEnabled = false,
            handlers = listOf(recordingHandler),
            messagesCount = messagesCount,
            clientAddresses = TcpMultiTest.loopbackAddresses(startClientPort, params.clientCount),
            serverPort = serverPort,
        )
        startClientPort += params.clientCount

        test.setup()

        when (params.scenario) {
            Scenario.SimpleInbound -> test.runInbound()
            Scenario.SimpleOutbound -> test.runOutbound()
            Scenario.WarmupOutbound -> test.runWarmupOutbound(recordingHandler)
        }

        test.shutdown()

        return recordingHandler.events
    }
}