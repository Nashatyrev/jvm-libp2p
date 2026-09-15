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
 * Publish bytes are additionally attributed to the slot that produced them, by reading the slot
 * index out of the payload ([DcMessagePayload.slotIndexOf], which recognises every message kind, so
 * blocks are credited to their slot like any other type). That attribution is exact, unlike the
 * wall-clock bucketing used for UDP traffic in [DcTrafficReport] — when slots overlap (a short
 * [DcRunConfig.slotInterval] relative to dissemination time) a slot's bytes keep flowing long
 * after the next slot has started, so time windows credit them to the wrong slot.
 *
 * When constructed with [slotProfile], inbound reads are additionally bucketed by where in the slot
 * cycle they landed — see [DcSlotTrafficProfile] — split into per-([DcSlotMessageType], bucket)
 * publish bytes, further split into [uniqueMessageBytesReadByTypeAndBucket] (this node's first time
 * seeing this exact message) and [duplicateMessageBytesReadByTypeAndBucket] (every copy after that,
 * still genuine wire traffic even though gossip's own deduplication will discard it) — plus
 * per-bucket control bytes ([controlBytesReadByBucket]). That needs to know the current simulated
 * time, which is not available at construction, so [currentTimeSupplier] is set later — see
 * [DcNodeProgram.onAllConnected].
 */
@io.netty.channel.ChannelHandler.Sharable
class GossipByteCounter(private val slotProfile: DcSlotProfileParams? = null) : ChannelDuplexHandler() {

    /** Set once this node's simulated clock is available; reads before that are not bucketed. */
    var currentTimeSupplier: (() -> Duration)? = null

    private val _publishBytesRead = AtomicLong(0)
    private val _publishBytesWritten = AtomicLong(0)
    private val _controlBytesRead = AtomicLong(0)
    private val _controlBytesWritten = AtomicLong(0)
    private val _readBySlot = ConcurrentHashMap<Int, SlotCounts>()
    private val _writtenBySlot = ConcurrentHashMap<Int, SlotCounts>()
    private val _publishMessagesRead = AtomicLong(0)
    private val _publishMessagesWritten = AtomicLong(0)

    private val _uniqueMessageBytesByTypeBucket = ConcurrentHashMap<DcSlotMessageType, Array<AtomicLong>>()
    private val _duplicateMessageBytesByTypeBucket = ConcurrentHashMap<DcSlotMessageType, Array<AtomicLong>>()
    private val _controlBytesReadByBucket: Array<AtomicLong>? = slotProfile?.let { params ->
        Array(params.bucketCount) { AtomicLong(0) }
    }
    private val _controlBreakdownRead = ControlAccumulator()

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

    /** Publish bytes per slot index. Payloads without a recognisable header are left out. */
    val publishBytesReadBySlot: Map<Int, Long> get() = _readBySlot.mapValues { it.value.bytes.get() }
    val publishBytesWrittenBySlot: Map<Int, Long> get() = _writtenBySlot.mapValues { it.value.bytes.get() }

    /** Publish message counts per slot index, the numerator of a per-slot duplication factor. */
    val publishMessagesReadBySlot: Map<Int, Long> get() = _readBySlot.mapValues { it.value.messages.get() }
    val publishMessagesWrittenBySlot: Map<Int, Long>
        get() = _writtenBySlot.mapValues { it.value.messages.get() }

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

    /**
     * Inbound [controlBytesRead] split by what it was spent on, since "control" otherwise lumps
     * together things with very different scaling: IHAVE/IWANT grow with the number of messages
     * published, GRAFT/PRUNE with mesh churn, and subscriptions are paid once per topic at startup.
     * [DcControlBreakdown.framing] is what is left of the RPC once every field is accounted for.
     */
    val controlBreakdownRead: DcControlBreakdown get() = _controlBreakdownRead.snapshot(controlBytesRead)

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is Rpc.RPC) {
            count(msg, _publishBytesRead, _controlBytesRead, _readBySlot, _publishMessagesRead)
            _controlBreakdownRead.add(msg)
            countSlotProfile(msg)
        }
        super.channelRead(ctx, msg)
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (msg is Rpc.RPC) {
            count(msg, _publishBytesWritten, _controlBytesWritten, _writtenBySlot, _publishMessagesWritten)
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

    /** Bytes and message count for one slot, so both are attributed in a single map lookup. */
    private class SlotCounts {
        val bytes = AtomicLong(0)
        val messages = AtomicLong(0)
    }

    /**
     * Running total of each control field's encoded size, and of how many IHAVE/IWANT message ids
     * were announced or asked for — the id count is what actually scales with published messages,
     * and it is not recoverable from the byte total once ids of different lengths are mixed.
     */
    private class ControlAccumulator {
        val subscriptions = AtomicLong(0)
        val ihave = AtomicLong(0)
        val iwant = AtomicLong(0)
        val graft = AtomicLong(0)
        val prune = AtomicLong(0)
        val ihaveIds = AtomicLong(0)
        val iwantIds = AtomicLong(0)

        fun add(rpc: Rpc.RPC) {
            rpc.subscriptionsList.forEach { subscriptions.addAndGet(encodedFieldSize(it)) }
            if (!rpc.hasControl()) return
            val control = rpc.control
            control.ihaveList.forEach {
                ihave.addAndGet(encodedFieldSize(it))
                ihaveIds.addAndGet(it.messageIDsCount.toLong())
            }
            control.iwantList.forEach {
                iwant.addAndGet(encodedFieldSize(it))
                iwantIds.addAndGet(it.messageIDsCount.toLong())
            }
            control.graftList.forEach { graft.addAndGet(encodedFieldSize(it)) }
            control.pruneList.forEach { prune.addAndGet(encodedFieldSize(it)) }
        }

        /**
         * [total] is the authoritative control figure [count] already produced; framing is whatever
         * it holds beyond the fields above, so the parts always add up to it exactly.
         */
        fun snapshot(total: Long) = DcControlBreakdown(
            subscriptionBytes = subscriptions.get(),
            ihaveBytes = ihave.get(),
            iwantBytes = iwant.get(),
            graftBytes = graft.get(),
            pruneBytes = prune.get(),
            ihaveMessageIds = ihaveIds.get(),
            iwantMessageIds = iwantIds.get(),
            totalBytes = total
        )
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
            bySlot: ConcurrentHashMap<Int, SlotCounts>,
            messageAcc: AtomicLong
        ) {
            var publishBytes = 0L
            rpc.publishList.forEach { message ->
                val size = encodedFieldSize(message)
                publishBytes += size
                DcMessagePayload.slotIndexOf(message.data)?.let { slot ->
                    val counts = bySlot.computeIfAbsent(slot) { SlotCounts() }
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
