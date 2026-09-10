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
 * publish bytes, further split into [uniqueMessageBytesReadByTypeAndBucket] (this node's first time
 * seeing this exact message) and [duplicateMessageBytesReadByTypeAndBucket] (every copy after that,
 * still genuine wire traffic even though gossip's own deduplication will discard it) — plus
 * per-bucket control bytes ([controlBytesReadByBucket]). That needs to know the current simulated
 * time, which is not available at construction, so [currentTimeSupplier] is set later — see
 * [DcAttestationNodeProgram.onAllConnected].
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

    private val _uniqueMessageBytesByTypeBucket = ConcurrentHashMap<DcSlotMessageType, Array<AtomicLong>>()
    private val _duplicateMessageBytesByTypeBucket = ConcurrentHashMap<DcSlotMessageType, Array<AtomicLong>>()
    private val _controlBytesReadByBucket: Array<AtomicLong>? = slotProfile?.let { params ->
        Array(params.bucketCount) { AtomicLong(0) }
    }

    /** (type, message id) pairs this node has already seen, so a later copy is marked a duplicate. */
    private val _seenMessages = ConcurrentHashMap.newKeySet<Pair<DcSlotMessageType, Int>>()

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
     * Inbound publish bytes read off the wire on this node's first sighting of each message, by
     * message type and [DcSlotTrafficProfile] bucket. Empty unless this counter was built with a
     * [slotProfile] and [currentTimeSupplier] has been set.
     */
    val uniqueMessageBytesReadByTypeAndBucket: Map<DcSlotMessageType, List<Long>>
        get() = _uniqueMessageBytesByTypeBucket.mapValues { (_, buckets) -> buckets.map { it.get() } }

    /**
     * Inbound publish bytes read off the wire for a message this node has already seen once before
     * — a mesh peer forwarding a copy it has not yet deduplicated, still genuine wire traffic even
     * though the application layer will discard it. By message type and bucket, as above.
     */
    val duplicateMessageBytesReadByTypeAndBucket: Map<DcSlotMessageType, List<Long>>
        get() = _duplicateMessageBytesByTypeBucket.mapValues { (_, buckets) -> buckets.map { it.get() } }

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
     * RPCs too), then splits its bytes the same way [count] already does — by topic, further split
     * into [_uniqueMessageBytesByTypeBucket] or [_duplicateMessageBytesByTypeBucket] depending on
     * whether [_seenMessages] already held this exact (type, id) — with the remainder into
     * [_controlBytesReadByBucket].
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
            val id = DcMessagePayload.idOf(message.data)
            if (type != null && id != null) {
                val isFirstSighting = _seenMessages.add(type to id)
                val byTypeBucket = if (isFirstSighting) _uniqueMessageBytesByTypeBucket else _duplicateMessageBytesByTypeBucket
                val buckets = byTypeBucket.computeIfAbsent(type) { Array(params.bucketCount) { AtomicLong(0) } }
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
