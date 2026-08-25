package io.libp2p.quicsim.scenario

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.time.Duration.Companion.nanoseconds

class FileQuicScenarioEventSink(
    private val path: Path
) : QuicScenarioEventSink {
    private val lock = Any()

    init {
        path.parent?.createDirectories()
        if (!path.exists()) {
            Files.createFile(path)
        }
    }

    override fun record(event: QuicScenarioEvent) {
        synchronized(lock) {
            Files.writeString(
                path,
                QuicScenarioEventFileCodec.encode(event) + System.lineSeparator(),
                StandardOpenOption.APPEND
            )
        }
    }
}

object QuicScenarioEventFileCodec {
    fun readEvents(path: Path): List<QuicScenarioEvent> =
        if (path.exists()) {
            path.readLines()
                .filter { it.isNotBlank() }
                .map(::decode)
        } else {
            emptyList()
        }

    fun encode(event: QuicScenarioEvent): String =
        when (event) {
            is QuicScenarioEvent.NodeConnected -> listOf(
                "node_connected",
                event.nodeId,
                event.at.inWholeNanoseconds,
                event.remoteNodeId
            ).joinToString("\t")

            is QuicScenarioEvent.DataChunkSent -> listOf(
                "data_chunk_sent",
                event.nodeId,
                event.at.inWholeNanoseconds,
                event.chunkIndex,
                event.from,
                event.to
            ).joinToString("\t")

            is QuicScenarioEvent.DataChunkPacketReceived -> listOf(
                "data_chunk_packet_received",
                event.nodeId,
                event.at.inWholeNanoseconds,
                event.chunkIndex,
                event.from,
                event.to,
                event.sequence,
                event.totalPackets,
                event.payloadBytes
            ).joinToString("\t")

            is QuicScenarioEvent.GossipMessageReceived -> listOf(
                "gossip_message_received",
                event.nodeId,
                event.at.inWholeNanoseconds,
                event.publisherNodeId,
                event.messageIndex
            ).joinToString("\t")

            is QuicScenarioEvent.GossipMessagePublished -> listOf(
                "gossip_message_published",
                event.nodeId,
                event.at.inWholeNanoseconds,
                event.messageIndex
            ).joinToString("\t")

            is QuicScenarioEvent.AttestationAggregatePublished -> listOf(
                "attestation_aggregate_published",
                event.nodeId,
                event.at.inWholeNanoseconds,
                event.aggregatorId,
                event.slot,
                event.attestationPercent,
                event.ruleId
            ).joinToString("\t")

            is QuicScenarioEvent.AttestationAggregateReceived -> listOf(
                "attestation_aggregate_received",
                event.nodeId,
                event.at.inWholeNanoseconds,
                event.publisherNodeId,
                event.aggregatorId,
                event.slot,
                event.attestationPercent,
                event.ruleId
            ).joinToString("\t")
        }

    fun decode(line: String): QuicScenarioEvent {
        val parts = line.split('\t')
        return when (parts[0]) {
            "node_connected" -> QuicScenarioEvent.NodeConnected(
                nodeId = parts[1].toInt(),
                at = parts[2].toLong().nanoseconds,
                remoteNodeId = parts[3].toInt()
            )

            "data_chunk_sent" -> QuicScenarioEvent.DataChunkSent(
                nodeId = parts[1].toInt(),
                at = parts[2].toLong().nanoseconds,
                chunkIndex = parts[3].toInt(),
                from = parts[4].toInt(),
                to = parts[5].toInt()
            )

            "data_chunk_packet_received" -> QuicScenarioEvent.DataChunkPacketReceived(
                nodeId = parts[1].toInt(),
                at = parts[2].toLong().nanoseconds,
                chunkIndex = parts[3].toInt(),
                sequence = parts[6].toInt(),
                totalPackets = parts[7].toInt(),
                payloadBytes = parts[8].toInt(),
                from = parts[4].toInt(),
                to = parts[5].toInt()
            )

            "gossip_message_received" -> QuicScenarioEvent.GossipMessageReceived(
                nodeId = parts[1].toInt(),
                at = parts[2].toLong().nanoseconds,
                publisherNodeId = parts[3].toInt(),
                messageIndex = parts.getOrNull(4)?.toInt() ?: 0
            )

            "gossip_message_published" -> QuicScenarioEvent.GossipMessagePublished(
                nodeId = parts[1].toInt(),
                at = parts[2].toLong().nanoseconds,
                messageIndex = parts.getOrNull(3)?.toInt() ?: 0
            )

            "attestation_aggregate_published" -> QuicScenarioEvent.AttestationAggregatePublished(
                nodeId = parts[1].toInt(),
                at = parts[2].toLong().nanoseconds,
                aggregatorId = parts[3],
                slot = parts[4].toLong(),
                attestationPercent = parts[5].toDouble(),
                ruleId = parts[6]
            )

            "attestation_aggregate_received" -> QuicScenarioEvent.AttestationAggregateReceived(
                nodeId = parts[1].toInt(),
                at = parts[2].toLong().nanoseconds,
                publisherNodeId = parts[3].toInt(),
                aggregatorId = parts[4],
                slot = parts[5].toLong(),
                attestationPercent = parts[6].toDouble(),
                ruleId = parts[7]
            )

            else -> throw IllegalArgumentException("Unknown scenario event line: $line")
        }
    }
}
