package io.libp2p.example.dc

import com.google.protobuf.ByteString
import com.google.protobuf.CodedOutputStream
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import pubsub.pb.Rpc
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Netty handler that counts gossip RPC bytes at the protobuf level (after encoding/before
 * decoding), giving traffic figures that exclude QUIC/yamux framing.
 *
 * Position in the pipeline: after [ProtobufEncoder]/[ProtobufDecoder], so the objects seen are
 * [Rpc.RPC] instances. Counts are split into:
 *  - [publishBytesRead]/[publishBytesWritten]: bytes used by [Rpc.RPC.publishList] fields
 *  - [controlBytesRead]/[controlBytesWritten]: bytes used by subscriptions + control fields
 *
 * The sum + 2 (varint length prefix) equals the total stream bytes per RPC.
 *
 * Publish bytes are additionally attributed to the wave that produced them, by reading the wave
 * index out of the attestation payload. That attribution is exact, unlike the wall-clock bucketing
 * used for UDP traffic in [DcTrafficReport] — when waves overlap (a short [DcAttestationConfig
 * .waveInterval] relative to dissemination time) a wave's bytes keep flowing long after the next
 * wave has started, so time windows credit them to the wrong wave.
 */
@io.netty.channel.ChannelHandler.Sharable
class GossipByteCounter : ChannelDuplexHandler() {

    private val _publishBytesRead = AtomicLong(0)
    private val _publishBytesWritten = AtomicLong(0)
    private val _controlBytesRead = AtomicLong(0)
    private val _controlBytesWritten = AtomicLong(0)
    private val _readByWave = ConcurrentHashMap<Int, WaveCounts>()
    private val _writtenByWave = ConcurrentHashMap<Int, WaveCounts>()
    private val _publishMessagesRead = AtomicLong(0)
    private val _publishMessagesWritten = AtomicLong(0)

    /**
     * Number of published messages seen, as opposed to their size. Divided by the count of
     * deduplicated deliveries this gives the duplication factor exactly, with no need to assume a
     * per-message size.
     */
    val publishMessagesRead: Long get() = _publishMessagesRead.get()
    val publishMessagesWritten: Long get() = _publishMessagesWritten.get()

    val publishBytesRead: Long get() = _publishBytesRead.get()
    val publishBytesWritten: Long get() = _publishBytesWritten.get()
    val controlBytesRead: Long get() = _controlBytesRead.get()
    val controlBytesWritten: Long get() = _controlBytesWritten.get()

    val bytesRead: Long get() = publishBytesRead + controlBytesRead
    val bytesWritten: Long get() = publishBytesWritten + controlBytesWritten

    /** Publish bytes per wave index. Payloads without a recognisable header are left out. */
    val publishBytesReadByWave: Map<Int, Long> get() = _readByWave.mapValues { it.value.bytes.get() }
    val publishBytesWrittenByWave: Map<Int, Long> get() = _writtenByWave.mapValues { it.value.bytes.get() }

    /** Publish message counts per wave index, the numerator of a per-wave duplication factor. */
    val publishMessagesReadByWave: Map<Int, Long> get() = _readByWave.mapValues { it.value.messages.get() }
    val publishMessagesWrittenByWave: Map<Int, Long>
        get() = _writtenByWave.mapValues { it.value.messages.get() }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is Rpc.RPC) {
            count(msg, _publishBytesRead, _controlBytesRead, _readByWave, _publishMessagesRead)
        }
        super.channelRead(ctx, msg)
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (msg is Rpc.RPC) {
            count(msg, _publishBytesWritten, _controlBytesWritten, _writtenByWave, _publishMessagesWritten)
        }
        super.write(ctx, msg, promise)
    }

    /** Bytes and message count for one wave, so both are attributed in a single map lookup. */
    private class WaveCounts {
        val bytes = AtomicLong(0)
        val messages = AtomicLong(0)
    }

    companion object {
        // ProtobufVarint32LengthFieldPrepender adds a 1–5 byte varint before each RPC.
        // For our gossip RPCs the varint is 2 bytes (size 128–16383).
        private const val VARINT_OVERHEAD = 2L

        private fun encodedFieldSize(msg: com.google.protobuf.MessageLite): Long {
            val s = msg.serializedSize
            // 1 byte tag (field numbers 1–15, wire type 2) + varint(size) + payload
            return (1 + CodedOutputStream.computeUInt32SizeNoTag(s) + s).toLong()
        }

        private fun count(
            rpc: Rpc.RPC,
            publishAcc: AtomicLong,
            controlAcc: AtomicLong,
            byWave: ConcurrentHashMap<Int, WaveCounts>,
            messageAcc: AtomicLong
        ) {
            var publishBytes = 0L
            rpc.publishList.forEach { message ->
                val size = encodedFieldSize(message)
                publishBytes += size
                waveIndexOf(message.data)?.let { wave ->
                    val counts = byWave.computeIfAbsent(wave) { WaveCounts() }
                    counts.bytes.addAndGet(size)
                    counts.messages.incrementAndGet()
                }
            }
            messageAcc.addAndGet(rpc.publishCount.toLong())
            // Total RPC bytes on stream = rpc.serializedSize + VARINT_OVERHEAD.
            // Everything that isn't publish is control (subscriptions + control msg + RPC overhead).
            val totalBytes = rpc.serializedSize + VARINT_OVERHEAD
            publishAcc.addAndGet(publishBytes)
            controlAcc.addAndGet(totalBytes - publishBytes)
        }

        /**
         * Wave index from an attestation payload, or null if [data] is not one — the magic guard
         * keeps foreign traffic on the same channels from being attributed to a wave.
         */
        private fun waveIndexOf(data: ByteString): Int? {
            if (data.size() < DcAttestationNodeProgram.HEADER_BYTES) return null
            if (intAt(data, 0) != DcAttestationNodeProgram.MAGIC) return null
            return intAt(data, DcAttestationNodeProgram.WAVE_INDEX_OFFSET)
        }

        /** Big-endian int, matching the [java.nio.ByteBuffer] the payload is written with. */
        private fun intAt(data: ByteString, offset: Int): Int =
            ((data.byteAt(offset).toInt() and 0xFF) shl 24) or
                ((data.byteAt(offset + 1).toInt() and 0xFF) shl 16) or
                ((data.byteAt(offset + 2).toInt() and 0xFF) shl 8) or
                (data.byteAt(offset + 3).toInt() and 0xFF)
    }
}
