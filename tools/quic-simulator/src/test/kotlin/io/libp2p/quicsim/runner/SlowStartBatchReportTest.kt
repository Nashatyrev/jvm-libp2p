package io.libp2p.quicsim.runner

import io.libp2p.quicsim.program.DataChunkMetrics
import io.libp2p.quicsim.runner.shadow.ShadowQuicScenarioRunner
import io.libp2p.quicsim.scenario.QuicScenarioResult
import io.libp2p.quicsim.scenario.QuicScenarios
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.nanoseconds

class SlowStartBatchReportTest {
    @Test
    fun `write simulated slow start packet batch csv`() {
        writeBatchCsv(
            result = SimulatedQuicScenarioRunner(latencyWindowParallelism = 20).run(QuicScenarios.slowStart()),
            outputPath = outputDir().resolve("slow-start-simulated-batches.csv")
        )
    }

    @Test
    fun `write simulated slow start codel packet batch csv`() {
        writeBatchCsv(
            result = SimulatedQuicScenarioRunner(
                latencyWindowParallelism = 20,
                bandwidthQueueDiscipline = BandwidthQueueDiscipline.CODEL
            ).run(QuicScenarios.slowStart()),
            outputPath = outputDir().resolve("slow-start-codel-simulated-batches.csv")
        )
    }

    @Test
    fun `write simulated slow start fifo outbound codel inbound packet batch csv`() {
        writeBatchCsv(
            result = SimulatedQuicScenarioRunner(
                latencyWindowParallelism = 20,
                bandwidthQueueDiscipline = BandwidthQueueDiscipline.FIFO_OUTBOUND_CODEL_INBOUND
            ).run(QuicScenarios.slowStart()),
            outputPath = outputDir().resolve("slow-start-fifo-outbound-codel-inbound-simulated-batches.csv")
        )
    }

    @Test
    fun `write simulated single transfer 8mb codel packet batch csv`() {
        writeBatchCsv(
            result = SimulatedQuicScenarioRunner(
                latencyWindowParallelism = 20,
                bandwidthQueueDiscipline = BandwidthQueueDiscipline.CODEL
            ).run(QuicScenarios.singleTransfer8Mb()),
            outputPath = outputDir().resolve("single-transfer-8mb-codel-simulated-batches.csv")
        )
    }

    @Test
    fun `write simulated single transfer 8mb fifo outbound codel inbound packet batch csv`() {
        writeBatchCsv(
            result = SimulatedQuicScenarioRunner(
                latencyWindowParallelism = 20,
                bandwidthQueueDiscipline = BandwidthQueueDiscipline.FIFO_OUTBOUND_CODEL_INBOUND
            ).run(QuicScenarios.singleTransfer8Mb()),
            outputPath = outputDir().resolve("single-transfer-8mb-fifo-outbound-codel-inbound-simulated-batches.csv")
        )
    }

    @Test
    fun `write simulated single transfer 8mb fifo packet batch csv`() {
        writeBatchCsv(
            result = SimulatedQuicScenarioRunner(
                latencyWindowParallelism = 20
            ).run(QuicScenarios.singleTransfer8Mb()),
            outputPath = outputDir().resolve("single-transfer-8mb-fifo-simulated-batches.csv")
        )
    }

    @Test
    fun `write simulated single transfer 8mb half receiver bandwidth fifo packet batch csv`() {
        writeBatchCsv(
            result = SimulatedQuicScenarioRunner(
                latencyWindowParallelism = 20
            ).run(QuicScenarios.singleTransfer8MbHalfReceiverBandwidth()),
            outputPath = outputDir().resolve("single-transfer-8mb-half-receiver-bw-fifo-simulated-batches.csv")
        )
    }

    @Test
    fun `write simulated single transfer 8mb half receiver bandwidth codel packet batch csv`() {
        writeBatchCsv(
            result = SimulatedQuicScenarioRunner(
                latencyWindowParallelism = 20,
                bandwidthQueueDiscipline = BandwidthQueueDiscipline.CODEL
            ).run(QuicScenarios.singleTransfer8MbHalfReceiverBandwidth()),
            outputPath = outputDir().resolve("single-transfer-8mb-half-receiver-bw-codel-simulated-batches.csv")
        )
    }

    @Test
    fun `write simulated single transfer 8mb half receiver bandwidth fifo outbound codel inbound packet batch csv`() {
        writeBatchCsv(
            result = SimulatedQuicScenarioRunner(
                latencyWindowParallelism = 20,
                bandwidthQueueDiscipline = BandwidthQueueDiscipline.FIFO_OUTBOUND_CODEL_INBOUND
            ).run(QuicScenarios.singleTransfer8MbHalfReceiverBandwidth()),
            outputPath = outputDir().resolve(
                "single-transfer-8mb-half-receiver-bw-fifo-outbound-codel-inbound-simulated-batches.csv"
            )
        )
    }

    @Test
    fun `write simulated single transfer 8mb fifo datagram trace csv`() {
        val traceRecorder = RecordingDatagramPacketTraceRecorder()
        SimulatedQuicScenarioRunner(
            latencyWindowParallelism = 20,
            datagramPacketTraceRecorder = traceRecorder
        ).run(QuicScenarios.singleTransfer8Mb())

        writeDatagramTraceCsv(
            events = traceRecorder.events(),
            outputPath = outputDir().resolve("single-transfer-8mb-fifo-simulated-datagrams.csv")
        )
        writeMissingDatagramTraceCsv(
            events = traceRecorder.events(),
            outputPath = outputDir().resolve("single-transfer-8mb-fifo-simulated-missing-datagrams.csv")
        )
        writeDatagramTraceSummaryCsv(
            events = traceRecorder.events(),
            outputPath = outputDir().resolve("single-transfer-8mb-fifo-simulated-datagram-summary.csv")
        )
    }

    @Test
    fun `write simulated single transfer 8mb half receiver bandwidth codel udp packet report csv`() {
        val traceRecorder = RecordingDatagramPacketTraceRecorder()
        SimulatedQuicScenarioRunner(
            latencyWindowParallelism = 20,
            bandwidthQueueDiscipline = BandwidthQueueDiscipline.CODEL,
            datagramPacketTraceRecorder = traceRecorder
        ).run(QuicScenarios.singleTransfer8MbHalfReceiverBandwidth())

        writeDatagramTraceCsv(
            events = traceRecorder.events(),
            outputPath = outputDir().resolve("single-transfer-8mb-half-receiver-bw-codel-simulated-datagrams.csv")
        )
        writeUdpPacketReportCsv(
            events = traceRecorder.events(),
            outputPath = outputDir().resolve("single-transfer-8mb-half-receiver-bw-codel-simulated-udp-packets.csv")
        )
    }

    @Test
    fun `write shadow single transfer 8mb packet batch csv`() {
        val shadowPath = System.getProperty("shadow.path")
        assumeTrue(!shadowPath.isNullOrBlank(), "Set -Dshadow.path=/path/to/shadow to run Shadow report")

        writeBatchCsv(
            result = ShadowQuicScenarioRunner(
                shadowPath = Path(shadowPath),
                workDir = Files.createTempDirectory("quic-shadow-single-transfer-8mb-batch-report-")
            ).run(QuicScenarios.singleTransfer8Mb()),
            outputPath = outputDir().resolve("single-transfer-8mb-shadow-batches.csv")
        )
    }

    @Test
    fun `write shadow single transfer 8mb half receiver bandwidth packet batch csv`() {
        val shadowPath = System.getProperty("shadow.path")
        assumeTrue(!shadowPath.isNullOrBlank(), "Set -Dshadow.path=/path/to/shadow to run Shadow report")

        writeBatchCsv(
            result = ShadowQuicScenarioRunner(
                shadowPath = Path(shadowPath),
                workDir = Files.createTempDirectory("quic-shadow-single-transfer-8mb-half-receiver-bw-batch-report-")
            ).run(QuicScenarios.singleTransfer8MbHalfReceiverBandwidth()),
            outputPath = outputDir().resolve("single-transfer-8mb-half-receiver-bw-shadow-batches.csv")
        )
    }

    @Test
    fun `write shadow single transfer 8mb datagram trace csv`() {
        val shadowPath = System.getProperty("shadow.path")
        assumeTrue(!shadowPath.isNullOrBlank(), "Set -Dshadow.path=/path/to/shadow to run Shadow report")

        val workDir = Files.createTempDirectory("quic-shadow-single-transfer-8mb-datagram-trace-")
        ShadowQuicScenarioRunner(
            shadowPath = Path(shadowPath),
            workDir = workDir
        ).run(QuicScenarios.singleTransfer8Mb())

        val events = readDatagramTraceCsv(workDir.resolve("datagram-traces"))
        writeDatagramTraceCsv(
            events = events,
            outputPath = outputDir().resolve("single-transfer-8mb-shadow-datagrams.csv")
        )
        writeMissingDatagramTraceCsv(
            events = events,
            outputPath = outputDir().resolve("single-transfer-8mb-shadow-missing-datagrams.csv")
        )
        writeDatagramTraceSummaryCsv(
            events = events,
            outputPath = outputDir().resolve("single-transfer-8mb-shadow-datagram-summary.csv")
        )
    }

    @Test
    fun `write shadow single transfer 8mb half receiver bandwidth udp packet report csv`() {
        val shadowPath = System.getProperty("shadow.path")
        assumeTrue(!shadowPath.isNullOrBlank(), "Set -Dshadow.path=/path/to/shadow to run Shadow report")

        val workDir = Files.createTempDirectory("quic-shadow-single-transfer-8mb-half-receiver-bw-udp-packets-")
        ShadowQuicScenarioRunner(
            shadowPath = Path(shadowPath),
            workDir = workDir
        ).run(QuicScenarios.singleTransfer8MbHalfReceiverBandwidth())

        val events = readDatagramTraceCsv(workDir.resolve("datagram-traces"))
        writeDatagramTraceCsv(
            events = events,
            outputPath = outputDir().resolve("single-transfer-8mb-half-receiver-bw-shadow-datagrams.csv")
        )
        writeUdpPacketReportCsv(
            events = events,
            outputPath = outputDir().resolve("single-transfer-8mb-half-receiver-bw-shadow-udp-packets.csv")
        )
    }

    @Test
    fun `write simulated slow start codel datagram trace csv`() {
        val traceRecorder = RecordingDatagramPacketTraceRecorder()
        SimulatedQuicScenarioRunner(
            latencyWindowParallelism = 20,
            bandwidthQueueDiscipline = BandwidthQueueDiscipline.CODEL,
            datagramPacketTraceRecorder = traceRecorder
        ).run(QuicScenarios.slowStart())

        writeDatagramTraceCsv(
            events = traceRecorder.events(),
            outputPath = outputDir().resolve("slow-start-codel-simulated-datagrams.csv")
        )
        writeMissingDatagramTraceCsv(
            events = traceRecorder.events(),
            outputPath = outputDir().resolve("slow-start-codel-simulated-missing-datagrams.csv")
        )
        writeDatagramTraceSummaryCsv(
            events = traceRecorder.events(),
            outputPath = outputDir().resolve("slow-start-codel-simulated-datagram-summary.csv")
        )
    }

    @Test
    fun `write shadow slow start packet batch csv`() {
        val shadowPath = System.getProperty("shadow.path")
        assumeTrue(!shadowPath.isNullOrBlank(), "Set -Dshadow.path=/path/to/shadow to run Shadow report")

        writeBatchCsv(
            result = ShadowQuicScenarioRunner(
                shadowPath = Path(shadowPath),
                workDir = Files.createTempDirectory("quic-shadow-batch-report-")
            ).run(QuicScenarios.slowStart()),
            outputPath = outputDir().resolve("slow-start-shadow-batches.csv")
        )
    }

    @Test
    fun `write shadow slow start datagram trace csv`() {
        val shadowPath = System.getProperty("shadow.path")
        assumeTrue(!shadowPath.isNullOrBlank(), "Set -Dshadow.path=/path/to/shadow to run Shadow report")

        val workDir = Files.createTempDirectory("quic-shadow-datagram-trace-")
        ShadowQuicScenarioRunner(
            shadowPath = Path(shadowPath),
            workDir = workDir
        ).run(QuicScenarios.slowStart())

        val events = readDatagramTraceCsv(workDir.resolve("datagram-traces"))
        writeDatagramTraceCsv(
            events = events,
            outputPath = outputDir().resolve("slow-start-shadow-datagrams.csv")
        )
        writeMissingDatagramTraceCsv(
            events = events,
            outputPath = outputDir().resolve("slow-start-shadow-missing-datagrams.csv")
        )
        writeDatagramTraceSummaryCsv(
            events = events,
            outputPath = outputDir().resolve("slow-start-shadow-datagram-summary.csv")
        )
    }

    private fun writeBatchCsv(
        result: QuicScenarioResult<*>,
        outputPath: Path
    ) {
        outputPath.parent.createDirectories()
        val rows = buildString {
            appendLine("runner,scenario,chunk,batch,start_time_ns,start_time_s,end_time_ns,end_time_s,packet_count,payload_bytes")
            DataChunkMetrics.packetBatches(result.events).forEach { batch ->
                appendLine(
                    listOf(
                        result.runnerName,
                        result.scenarioName,
                        batch.chunkIndex,
                        batch.batchIndex,
                        batch.startAt.inWholeNanoseconds,
                        "%.9f".format(batch.startAt.inWholeNanoseconds / 1_000_000_000.0),
                        batch.endAt.inWholeNanoseconds,
                        "%.9f".format(batch.endAt.inWholeNanoseconds / 1_000_000_000.0),
                        batch.packetCount,
                        batch.payloadBytes
                    ).joinToString(",")
                )
            }
        }
        outputPath.writeText(rows)
        println("Wrote packet batch CSV: $outputPath")
    }

    private fun writeDatagramTraceCsv(
        events: List<DatagramPacketTraceEvent>,
        outputPath: Path
    ) {
        outputPath.parent.createDirectories()
        val rows = buildString {
            appendLine(
                "direction,node_id,time_ns,time_s,local_host,local_port,remote_host,remote_port,bytes,payload_sha256,match_key"
            )
            events.sortedWith(compareBy({ it.at }, { it.nodeId }, { it.direction.name }, { it.payloadSha256 }))
                .forEach { event ->
                    appendLine(event.toCsvRow())
                }
        }
        outputPath.writeText(rows)
        println("Wrote datagram trace CSV: $outputPath")
    }

    private fun writeMissingDatagramTraceCsv(
        events: List<DatagramPacketTraceEvent>,
        outputPath: Path
    ) {
        outputPath.parent.createDirectories()
        val rows = buildString {
            appendLine(
                "direction,node_id,time_ns,time_s,local_host,local_port,remote_host,remote_port,bytes,payload_sha256,match_key"
            )
            missingOutboundDatagrams(events)
                .sortedWith(compareBy({ it.at }, { it.nodeId }, { it.payloadSha256 }))
                .forEach { event ->
                    appendLine(event.toCsvRow())
                }
        }
        outputPath.writeText(rows)
        println("Wrote missing datagram CSV: $outputPath")
    }

    private fun writeDatagramTraceSummaryCsv(
        events: List<DatagramPacketTraceEvent>,
        outputPath: Path
    ) {
        outputPath.parent.createDirectories()
        val outbound = events.filter { it.direction == DatagramPacketTraceEvent.Direction.OUTBOUND }
        val inbound = events.filter { it.direction == DatagramPacketTraceEvent.Direction.INBOUND }
        val missing = missingOutboundDatagrams(events)
        val rows = buildString {
            appendLine("outbound_datagrams,inbound_datagrams,missing_datagrams,outbound_bytes,inbound_bytes,missing_bytes")
            appendLine(
                listOf(
                    outbound.size,
                    inbound.size,
                    missing.size,
                    outbound.sumOf { it.bytes },
                    inbound.sumOf { it.bytes },
                    missing.sumOf { it.bytes }
                ).joinToString(",")
            )
        }
        outputPath.writeText(rows)
        println("Wrote datagram trace summary CSV: $outputPath")
    }

    private fun writeUdpPacketReportCsv(
        events: List<DatagramPacketTraceEvent>,
        outputPath: Path
    ) {
        outputPath.parent.createDirectories()
        val indexedEvents = events.mapIndexed { index, event -> IndexedDatagramPacketTraceEvent(index, event) }
        val inboundByKey = indexedEvents
            .filter { it.event.direction == DatagramPacketTraceEvent.Direction.INBOUND }
            .sortedWith(compareBy({ it.event.at }, { it.index }))
            .groupBy { it.event.matchKey }
            .mapValues { (_, value) -> ArrayDeque(value) }
        val outbound = indexedEvents
            .filter { it.event.direction == DatagramPacketTraceEvent.Direction.OUTBOUND }
            .sortedWith(compareBy({ it.event.at }, { it.index }))

        val rows = buildString {
            appendLine("packet_number,from,to,sent_time_ns,sent_time_s,receive_time_ns,receive_time_s,size")
            outbound.forEachIndexed { index, sentWithIndex ->
                val sent = sentWithIndex.event
                val received = inboundByKey[sent.matchKey]?.removeFirstOrNull()?.event
                appendLine(
                    listOf(
                        index + 1,
                        sent.nodeId,
                        received?.nodeId ?: sent.remoteHost.toNodeIdOrBlank(),
                        sent.at.inWholeNanoseconds,
                        "%.9f".format(sent.at.inWholeNanoseconds / 1_000_000_000.0),
                        received?.at?.inWholeNanoseconds ?: "",
                        received?.at?.let { "%.9f".format(it.inWholeNanoseconds / 1_000_000_000.0) } ?: "",
                        sent.bytes
                    ).joinToString(",")
                )
            }
        }
        outputPath.writeText(rows)
        println("Wrote UDP packet report CSV: $outputPath")
    }

    private data class IndexedDatagramPacketTraceEvent(
        val index: Int,
        val event: DatagramPacketTraceEvent
    )

    private fun missingOutboundDatagrams(events: List<DatagramPacketTraceEvent>): List<DatagramPacketTraceEvent> {
        val inboundCounts = events
            .filter { it.direction == DatagramPacketTraceEvent.Direction.INBOUND }
            .groupingBy { it.matchKey }
            .eachCount()
            .toMutableMap()
        return events
            .filter { it.direction == DatagramPacketTraceEvent.Direction.OUTBOUND }
            .filter { outbound ->
                val remaining = inboundCounts[outbound.matchKey] ?: 0
                if (remaining > 0) {
                    inboundCounts[outbound.matchKey] = remaining - 1
                    false
                } else {
                    true
                }
            }
    }

    private fun DatagramPacketTraceEvent.toCsvRow(): String =
        listOf(
            direction.name.lowercase(),
            nodeId,
            at.inWholeNanoseconds,
            "%.9f".format(at.inWholeNanoseconds / 1_000_000_000.0),
            localHost,
            localPort,
            remoteHost,
            remotePort,
            bytes,
            payloadSha256,
            matchKey
        ).joinToString(",")

    private fun String.toNodeIdOrBlank(): String =
        when {
            startsWith("10.0.") -> split(".").let { parts ->
                if (parts.size == 4) {
                    (parts[2].toIntOrNull()?.times(256) ?: return "") + (parts[3].toIntOrNull() ?: return "")
                } else {
                    ""
                }
            }.toString()
            startsWith("11.0.0.") -> removePrefix("11.0.0.").toIntOrNull()?.minus(1)?.toString() ?: ""
            else -> ""
        }

    private fun readDatagramTraceCsv(traceDir: Path): List<DatagramPacketTraceEvent> =
        traceDir.listDirectoryEntries("*.datagrams.csv")
            .sortedBy { it.fileName.toString() }
            .flatMap { path ->
                path.readLines()
                    .drop(1)
                    .filter { it.isNotBlank() }
                    .map { line ->
                        val parts = line.split(",")
                        DatagramPacketTraceEvent(
                            direction = DatagramPacketTraceEvent.Direction.valueOf(parts[0].uppercase()),
                            nodeId = parts[1].toInt(),
                            at = parts[2].toLong().nanoseconds,
                            localHost = parts[4],
                            localPort = parts[5].toInt(),
                            remoteHost = parts[6],
                            remotePort = parts[7].toInt(),
                            bytes = parts[8].toInt(),
                            payloadSha256 = parts[9]
                        )
                    }
            }

    private fun outputDir(): Path =
        Path(System.getProperty("slowStartReport.dir", "build/reports/slow-start-batches"))
}
