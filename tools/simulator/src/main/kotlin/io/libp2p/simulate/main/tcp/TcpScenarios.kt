package io.libp2p.simulate.main.tcp

import io.libp2p.simulate.Bandwidth
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
        listOf(TcpOption.Default),
    val bandwidthParams: List<Bandwidth> =
        listOf(25.mbitsPerSecond),
    val halfPingParams: List<Long> =
        listOf(1),
    val msgSizeParams: List<Int> =
        listOf(512 * 1024, 128 * 1024, 16 * 1024, 2 * 1204, 1024),
    val clientCountParams: List<Int> =
//        listOf(2),
        listOf(128, 64, 32, 16, 8, 4, 2, 1),
    val directionParams: List<Direction> =
        listOf(Direction.Inbound, Direction.Outbound),
    val staggeringParams: List<Double> =
//        listOf(0.0),
        listOf(0.0, 0.5, 1.0),
//        listOf(0.0, 0.25, 0.5, 0.75, 1.0),

    val params: List<RunParams> = cartesianProduct(
        bandwidthParams,
        halfPingParams,
        msgSizeParams,
        clientCountParams,
        directionParams,
        staggeringParams
    ) {
        RunParams(tcpOptionParams[0], it.first, it.second, it.third, it.fourth, it.fifth, it.sixth)
    },
    val outFile: String = "tcp.res.json"
) {

    val messagesCount = 5

    enum class TcpOption {
        Default,
        SlowStartIdleOff
    }

    enum class Direction {
        Inbound,
        Outbound,
    }

    @kotlinx.serialization.Serializable
    data class RunParams(
        val tcpOption: TcpOption,
        val bandwidth: Bandwidth,
        val halfPing: Long,
        val msgSize: Int,
        val clientCount: Int,
        val direction: Direction,
        val staggering: Double
    ) {
        val staggeringDelay
            get() =
                bandwidth.getTransmitTimeMillis(msgSize.toLong()).milliseconds * staggering
    }

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
        PrintWriter(FileOutputStream(file, true).bufferedWriter()).use { writer ->
            params
                .withIndex()
                .map { (index, param) ->
                    if (param in existingParams) {
                        log("Skipping $param")
                    } else {
                        log("Running ${index + 1} of ${params.size}: $param")
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
    }

    fun run(params: RunParams): List<EventRecordingHandler.Event> {

        val recordingHandler = EventRecordingHandler()

        val test = TcpMultiTest(
            msgSize = params.msgSize,
            clientCount = params.clientCount,
            staggeringDelay = params.staggeringDelay,
            loggersEnabled = false,
            handlers = listOf(recordingHandler),
            messagesCount = messagesCount
        )
        test.setup()

        when (params.direction) {
            Direction.Inbound -> test.runInbound()
            Direction.Outbound -> test.runOutbound()
        }

        test.shutdown()

        return recordingHandler.events
    }
}