package io.libp2p.pubsub.gossip

import com.google.protobuf.CodedOutputStream
import io.libp2p.core.PeerId
import io.libp2p.etc.types.toProtobuf
import io.libp2p.pubsub.DefaultRpcPartsQueue
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.RpcPartsQueue
import io.libp2p.pubsub.Topic
import pubsub.pb.Rpc
import java.util.concurrent.atomic.AtomicLong

object GossipRpcFrameStats {
    private val enabled = System.getProperty("quicsim.profile.gossipRpcFrameStats").toBoolean()

    private val rpcFrames = AtomicLong()
    private val totalSerializedBytes = AtomicLong()
    private val maxSerializedBytes = AtomicLong()
    private val lock = Any()
    private var maxFrame: FrameRecord? = null

    fun reset() {
        rpcFrames.set(0)
        totalSerializedBytes.set(0)
        maxSerializedBytes.set(0)
        synchronized(lock) {
            maxFrame = null
        }
    }

    fun record(rpc: Rpc.RPC) {
        if (!enabled) return

        val frame = FrameRecord.from(rpc)
        rpcFrames.incrementAndGet()
        totalSerializedBytes.addAndGet(frame.serializedBytes.toLong())

        while (true) {
            val cur = maxSerializedBytes.get()
            if (frame.serializedBytes <= cur) return
            if (maxSerializedBytes.compareAndSet(cur, frame.serializedBytes.toLong())) {
                synchronized(lock) {
                    if ((maxFrame?.serializedBytes ?: -1) < frame.serializedBytes) {
                        maxFrame = frame
                    }
                }
                return
            }
        }
    }

    fun snapshot(): Snapshot =
        Snapshot(
            rpcFrames = rpcFrames.get(),
            totalSerializedBytes = totalSerializedBytes.get(),
            maxFrame = synchronized(lock) { maxFrame }
        )

    data class Snapshot(
        val rpcFrames: Long,
        val totalSerializedBytes: Long,
        val maxFrame: FrameRecord?
    )

    data class FrameRecord(
        val serializedBytes: Int,
        val subscriptionCount: Int,
        val publishCount: Int,
        val hasControl: Boolean,
        val subscriptionBytes: Int,
        val publishBytes: Int,
        val controlBytes: Int,
        val iHaveCount: Int,
        val iHaveMessageIds: Int,
        val iHaveBytes: Int,
        val iWantCount: Int,
        val iWantMessageIds: Int,
        val iWantBytes: Int,
        val graftCount: Int,
        val graftBytes: Int,
        val pruneCount: Int,
        val prunePeers: Int,
        val pruneBytes: Int,
        val iDontWantCount: Int,
        val iDontWantMessageIds: Int,
        val iDontWantBytes: Int
    ) {
        companion object {
            fun from(rpc: Rpc.RPC): FrameRecord {
                val control = rpc.control
                val subscriptionBytes = rpc.subscriptionsList.sumOf {
                    embeddedMessageFieldSize(Rpc.RPC.SUBSCRIPTIONS_FIELD_NUMBER, it.serializedSize)
                }
                val publishBytes = rpc.publishList.sumOf {
                    embeddedMessageFieldSize(Rpc.RPC.PUBLISH_FIELD_NUMBER, it.serializedSize)
                }
                val iHaveBytes = control.ihaveList.sumOf {
                    embeddedMessageFieldSize(Rpc.ControlMessage.IHAVE_FIELD_NUMBER, it.serializedSize)
                }
                val iWantBytes = control.iwantList.sumOf {
                    embeddedMessageFieldSize(Rpc.ControlMessage.IWANT_FIELD_NUMBER, it.serializedSize)
                }
                val graftBytes = control.graftList.sumOf {
                    embeddedMessageFieldSize(Rpc.ControlMessage.GRAFT_FIELD_NUMBER, it.serializedSize)
                }
                val pruneBytes = control.pruneList.sumOf {
                    embeddedMessageFieldSize(Rpc.ControlMessage.PRUNE_FIELD_NUMBER, it.serializedSize)
                }
                val iDontWantBytes = control.idontwantList.sumOf {
                    embeddedMessageFieldSize(Rpc.ControlMessage.IDONTWANT_FIELD_NUMBER, it.serializedSize)
                }

                return FrameRecord(
                    serializedBytes = rpc.serializedSize,
                    subscriptionCount = rpc.subscriptionsCount,
                    publishCount = rpc.publishCount,
                    hasControl = rpc.hasControl(),
                    subscriptionBytes = subscriptionBytes,
                    publishBytes = publishBytes,
                    controlBytes = if (rpc.hasControl()) {
                        embeddedMessageFieldSize(Rpc.RPC.CONTROL_FIELD_NUMBER, control.serializedSize)
                    } else {
                        0
                    },
                    iHaveCount = control.ihaveCount,
                    iHaveMessageIds = control.ihaveList.sumOf { it.messageIDsCount },
                    iHaveBytes = iHaveBytes,
                    iWantCount = control.iwantCount,
                    iWantMessageIds = control.iwantList.sumOf { it.messageIDsCount },
                    iWantBytes = iWantBytes,
                    graftCount = control.graftCount,
                    graftBytes = graftBytes,
                    pruneCount = control.pruneCount,
                    prunePeers = control.pruneList.sumOf { it.peersCount },
                    pruneBytes = pruneBytes,
                    iDontWantCount = control.idontwantCount,
                    iDontWantMessageIds = control.idontwantList.sumOf { it.messageIDsCount },
                    iDontWantBytes = iDontWantBytes
                )
            }
        }
    }
}

interface GossipRpcPartsQueue : RpcPartsQueue {

    /**
     * Removes publishes which have not yet been flushed to the peer.
     *
     * Returns the number of removed publish parts.
     */
    fun removePublishes(messageIds: Set<MessageId>): Int

    fun addIHave(messageId: MessageId, topic: Topic)
    fun addIHaves(messageIds: Collection<MessageId>, topic: Topic) = messageIds.forEach { addIHave(it, topic) }
    fun addIWant(messageId: MessageId)
    fun addIWants(messageIds: Collection<MessageId>) = messageIds.forEach { addIWant(it) }

    fun addGraft(topic: Topic)

    /**
     * Gossip 1.0 variant
     */
    fun addPrune(topic: Topic)

    /**
     * Gossip 1.1 variant
     */
    fun addPrune(topic: Topic, backoffSeconds: Long, backoffPeers: List<PeerId>)

    // TODO Need to check if we should handle when control extension and extension messages could be separated by split  (https://github.com/libp2p/jvm-libp2p/issues/440)
    fun addControlExtensions(ctrlMessage: Rpc.ControlExtensions)

    fun addPartialMessage(partialMessage: Rpc.PartialMessagesExtension)
}

/**
 * Default [RpcPartsQueue] implementation
 *
 * NOT thread safe
 */
open class DefaultGossipRpcPartsQueue(
    private val params: GossipParams
) : DefaultRpcPartsQueue(), GossipRpcPartsQueue {

    override fun removePublishes(messageIds: Set<MessageId>): Int {
        if (messageIds.isEmpty()) return 0

        val partCount = parts.size
        parts.removeAll { it is PublishPart && it.messageId in messageIds }
        return partCount - parts.size
    }

    protected data class IHavePart(val messageId: MessageId, val topic: Topic) : AbstractPart {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            val ctrlBuilder = builder.controlBuilder
            val iHaveBuilder = ctrlBuilder.ihaveBuilderList
                .find { it.topicID == topic }
                ?: ctrlBuilder.addIhaveBuilder().setTopicID(topic)

            iHaveBuilder.addMessageIDs(messageId.toProtobuf())
        }
    }

    protected data class IWantPart(val messageId: MessageId) : AbstractPart {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            val ctrlBuilder = builder.controlBuilder
            val iWantBuilder = if (ctrlBuilder.iwantBuilderList.isEmpty()) {
                ctrlBuilder.addIwantBuilder()
            } else {
                ctrlBuilder.getIwantBuilder(0)
            }
            iWantBuilder.addMessageIDs(messageId.toProtobuf())
        }
    }

    protected data class GraftPart(val topic: Topic) : AbstractPart {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            builder.controlBuilder.addGraftBuilder().setTopicID(topic)
        }
    }

    protected data class PrunePart(val topic: Topic, val backoffSeconds: Long?, val backoffPeers: List<PeerId>) : AbstractPart {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            val pruneBuilder = builder.controlBuilder.addPruneBuilder()
            pruneBuilder.setTopicID(topic)
            if (backoffSeconds != null) {
                pruneBuilder.setBackoff(backoffSeconds)
                pruneBuilder.addAllPeers(
                    backoffPeers.map {
                        Rpc.PeerInfo.newBuilder().setPeerID(it.bytes.toProtobuf()).build()
                    }
                )
            }
        }
    }

    protected data class ControlExtensionPart(val ctrlExtension: Rpc.ControlExtensions) : AbstractPart {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            builder.controlBuilder.setExtensions(ctrlExtension)
        }
    }

    protected data class PartialMessagePart(val partialMessage: Rpc.PartialMessagesExtension) : AbstractPart {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            builder.setPartial(partialMessage)
        }
    }

    override fun addIHave(messageId: MessageId, topic: Topic) {
        addPart(IHavePart(messageId, topic))
    }

    override fun addIWant(messageId: MessageId) {
        addPart(IWantPart(messageId))
    }

    override fun addGraft(topic: Topic) {
        addPart(GraftPart(topic))
    }

    override fun addPrune(topic: Topic) {
        addPart(PrunePart(topic, null, emptyList()))
    }

    override fun addPrune(topic: Topic, backoffSeconds: Long, backoffPeers: List<PeerId>) {
        addPart(PrunePart(topic, backoffSeconds, backoffPeers))
    }

    override fun addControlExtensions(ctrlMessage: Rpc.ControlExtensions) {
        addPart(ControlExtensionPart(ctrlMessage))
    }

    override fun addPartialMessage(partialMessage: Rpc.PartialMessagesExtension) {
        addPart(PartialMessagePart(partialMessage))
    }

    override fun popMerged(): Rpc.RPC? {
        if (parts.isEmpty()) return null

        val builder = Rpc.RPC.newBuilder()
        var partIdx = 0
        var estimatedSize = 0L
        val estimatedSizeLimit = estimatedSizeLimit()

        var publishCount = params.maxPublishedMessages ?: Int.MAX_VALUE
        var subscriptionCount = params.maxSubscriptions ?: Int.MAX_VALUE
        var iHaveCount = params.maxIHaveLength
        var iWantCount = params.maxIWantMessageIds ?: Int.MAX_VALUE
        var graftCount = params.maxGraftMessages ?: Int.MAX_VALUE
        var pruneCount = params.maxPruneMessages ?: Int.MAX_VALUE

        while (partIdx < parts.size &&
            publishCount > 0 && subscriptionCount > 0 && iHaveCount > 0 &&
            iWantCount > 0 && graftCount > 0 && pruneCount > 0
        ) {
            val part = parts[partIdx]
            if (part is PartialMessagePart && partIdx > 0) {
                break
            }
            val partEstimatedSize = estimatePartSize(part).toLong()
            if (partIdx > 0 && estimatedSize + partEstimatedSize > estimatedSizeLimit) {
                break
            }

            partIdx++
            when (part) {
                is PublishPart -> publishCount--
                is SubscriptionPart -> subscriptionCount--
                is IHavePart -> iHaveCount--
                is IWantPart -> iWantCount--
                is GraftPart -> graftCount--
                is PrunePart -> pruneCount--
            }

            part.appendToBuilder(builder)
            estimatedSize += partEstimatedSize
            if (part is PartialMessagePart) {
                break
            }
        }

        parts.subList(0, partIdx).clear()
        return builder.build().also(GossipRpcFrameStats::record)
    }

    override fun takeMerged(): List<Rpc.RPC> =
        generateSequence { popMerged() }.toList()

    private fun estimatedSizeLimit(): Long {
        val margin = maxOf(MIN_SIZE_ESTIMATE_MARGIN_BYTES, params.maxGossipMessageSize / SIZE_ESTIMATE_MARGIN_FRACTION)
        return (params.maxGossipMessageSize - margin).coerceAtLeast(0).toLong()
    }

    private fun estimatePartSize(part: AbstractPart): Int =
        when (part) {
            is PublishPart ->
                embeddedMessageFieldSize(Rpc.RPC.PUBLISH_FIELD_NUMBER, part.message.serializedSize)

            is SubscriptionPart -> {
                val subscription = Rpc.RPC.SubOpts.newBuilder()
                    .setTopicid(part.topic)
                    .setSubscribe(part.status == RpcPartsQueue.SubscriptionStatus.Subscribed)
                    .setRequestsPartial(part.options.requestsPartial)
                    .setSupportsSendingPartial(part.options.supportsSendingPartial)
                    .build()
                embeddedMessageFieldSize(Rpc.RPC.SUBSCRIPTIONS_FIELD_NUMBER, subscription.serializedSize)
            }

            is PartialMessagePart ->
                embeddedMessageFieldSize(Rpc.RPC.PARTIAL_FIELD_NUMBER, part.partialMessage.serializedSize)

            else -> {
                val singleControlPart = Rpc.RPC.newBuilder()
                part.appendToBuilder(singleControlPart)
                embeddedMessageFieldSize(Rpc.RPC.CONTROL_FIELD_NUMBER, singleControlPart.control.serializedSize)
            }
        }

    private companion object {
        const val MIN_SIZE_ESTIMATE_MARGIN_BYTES = 64
        const val SIZE_ESTIMATE_MARGIN_FRACTION = 100
    }
}

private fun embeddedMessageFieldSize(fieldNumber: Int, messageSize: Int): Int =
    CodedOutputStream.computeTagSize(fieldNumber) +
        CodedOutputStream.computeUInt32SizeNoTag(messageSize) +
        messageSize
