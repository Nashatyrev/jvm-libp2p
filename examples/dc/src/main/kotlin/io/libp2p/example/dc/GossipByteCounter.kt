package io.libp2p.example.dc

import com.google.protobuf.CodedOutputStream
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import pubsub.pb.Rpc
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

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
 * index out of the payload ([DcMessagePayload.waveIndexOf], which recognises every message kind, so
 * blocks are credited to their wave alongside attestations). That attribution is exact, unlike the
 * wall-clock bucketing used for UDP traffic in [DcTrafficReport] — when waves overlap (a short
 * [DcAttestationConfig.waveInterval] relative to dissemination time) a wave's bytes keep flowing long
 * after the next wave has started, so time windows credit them to the wrong wave.
 *
 * When constructed with [slotProfile], inbound reads are additionally bucketed by where in the slot
 * cycle they landed — see [DcSlotTrafficProfile] — split into per-([DcSlotMessageType], bucket)
 * publish bytes ([messageBytesReadByTypeAndBucket], read straight off the wire so duplicates are
 * included) and per-bucket control bytes ([controlBytesReadByBucket]). That needs to know the
 * current simulated time, which is not available at construction, so [currentTimeSupplier] is set
 * later — see [DcAttestationNodeProgram.onAllConnected].
 */
@io.netty.channel.ChannelHandler.Sharable
class GossipByteCounter(private val slotProfile: DcSlotProfileParams? = null) : ChannelDuplexHandler() {

    /** Set once this node's simulated clock is available; reads before that are not bucketed. */
    var currentTimeSupplier: (() -> Duration)? = null

    private val _publishBytesRead = AtomicLong(0)
    private val _publishBytesWritten = AtomicLong(0)
    private val _controlBytesRead = AtomicLong(0)
    private val _controlBytesWritten = AtomicLong(0)
    private val _readByWave = ConcurrentHashMap<Int, WaveCounts>()
    private val _writtenByWave = ConcurrentHashMap<Int, WaveCounts>()
    private val _publishMessagesRead = AtomicLong(0)
    private val _publishMessagesWritten = AtomicLong(0)

    private val _messageBytesReadByTypeBucket = ConcurrentHashMap<DcSlotMessageType, Array<AtomicLong>>()
    private val _controlBytesReadByBucket: Array<AtomicLong>? = slotProfile?.let { params ->
        Array(params.bucketCount) { AtomicLong(0) }
    }

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

    /**
     * Inbound publish bytes read off the wire, by message type and [DcSlotTrafficProfile] bucket —
     * every duplicate copy included, since a mesh peer forwarding a message it hasn't deduplicated
     * yet is genuine wire traffic. Empty unless this counter was built with a [slotProfile] and
     * [currentTimeSupplier] has been set.
     */
    val messageBytesReadByTypeAndBucket: Map<DcSlotMessageType, List<Long>>
        get() = _messageBytesReadByTypeBucket.mapValues { (_, buckets) -> buckets.map { it.get() } }

    /** Inbound control bytes (subscriptions, GRAFT/PRUNE/IHAVE/IWANT, RPC framing) by slot bucket. */
    val controlBytesReadByBucket: List<Long>
        get() = _controlBytesReadByBucket?.map { it.get() } ?: emptyList()

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is Rpc.RPC) {
            count(msg, _publishBytesRead, _controlBytesRead, _readByWave, _publishMessagesRead)
            countSlotProfile(msg)
        }
        super.channelRead(ctx, msg)
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (msg is Rpc.RPC) {
            count(msg, _publishBytesWritten, _controlBytesWritten, _writtenByWave, _publishMessagesWritten)
        }
        super.write(ctx, msg, promise)
    }

    /**
     * The [DcSlotTrafficProfile] side of the accounting: which bucket [rpc] arrived in (from the
     * current simulated time, not from any message's own publish time, so it covers control-only
     * RPCs too), then splits its bytes the same way [count] already does — by topic into
     * [_messageBytesReadByTypeBucket], the remainder into [_controlBytesReadByBucket].
     */
    private fun countSlotProfile(rpc: Rpc.RPC) {
        val params = slotProfile ?: return
        val now = currentTimeSupplier?.invoke() ?: return
        val bucket = params.bucketOf(now) ?: return

        var publishBytes = 0L
        rpc.publishList.forEach { message ->
            val size = encodedFieldSize(message)
            publishBytes += size
            val type = message.topicIDsList.firstOrNull()?.let { DcSlotMessageTopics.typeOf(it) }
            if (type != null) {
                val buckets = _messageBytesReadByTypeBucket.computeIfAbsent(type) {
                    Array(params.bucketCount) { AtomicLong(0) }
                }
                buckets[bucket].addAndGet(size)
            }
        }
        val totalBytes = rpc.serializedSize + VARINT_OVERHEAD
        _controlBytesReadByBucket?.get(bucket)?.addAndGet(totalBytes - publishBytes)
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
                DcMessagePayload.waveIndexOf(message.data)?.let { wave ->
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
    }
}
