package io.libp2p.quicsim.program.gossip.attestation

import io.netty.buffer.ByteBuf
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.nanoseconds

object AttestationAggregateMessageCodec {
    private const val MAGIC = "attestation-aggregate-v1"
    private const val FIELD_COUNT = 7
    private const val ZERO_BYTE = 0.toByte()

    fun encode(message: AttestationAggregateMessage, messageSizeBytes: Int): ByteArray {
        val body = listOf(
            MAGIC,
            message.publisherNodeId,
            message.aggregatorId,
            message.slot,
            message.attestationPercent,
            message.emittedAt.inWholeNanoseconds,
            message.ruleId,
        ).joinToString("\t")
            .toByteArray(StandardCharsets.UTF_8)

        require(messageSizeBytes >= body.size) {
            "messageSizeBytes=$messageSizeBytes is too small, should be at least ${body.size} bytes"
        }

        return ByteArray(messageSizeBytes).also { payload ->
            System.arraycopy(body, 0, payload, 0, body.size)
        }
    }

    fun decode(data: ByteBuf): AttestationAggregateMessage? {
        val bytes = ByteArray(data.readableBytes())
        data.getBytes(data.readerIndex(), bytes)

        val endIndex = bytes.indexOfFirst { it == ZERO_BYTE }
            .let { if (it == -1) bytes.size else it }
        val parts = String(bytes, 0, endIndex, StandardCharsets.UTF_8).split('\t')
        if (parts.size != FIELD_COUNT || parts[0] != MAGIC) return null

        return try {
            AttestationAggregateMessage(
                publisherNodeId = parts[1].toInt(),
                aggregatorId = parts[2],
                slot = parts[3].toLong(),
                attestationPercent = parts[4].toDouble(),
                emittedAt = parts[5].toLong().nanoseconds,
                ruleId = parts[6],
            )
        } catch (e: RuntimeException) {
            null
        }
    }
}
