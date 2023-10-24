package io.libp2p.pubsub.erasure

import io.libp2p.core.PeerId
import io.libp2p.etc.types.WBytes
import io.libp2p.etc.types.toProtobuf
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.AbstractPubsubMessage
import io.libp2p.pubsub.AbstractRouter
import io.libp2p.pubsub.DefaultRpcPartsQueue
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.PubsubMessage
import io.libp2p.pubsub.PubsubMessageFactory
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.PubsubRouterMessageValidator
import io.libp2p.pubsub.SeenCache
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.TopicSubscriptionFilter
import io.libp2p.pubsub.erasure.message.ErasureHeader
import io.libp2p.pubsub.erasure.message.ErasureMessage
import io.libp2p.pubsub.erasure.message.ErasureSample
import io.libp2p.pubsub.erasure.message.MessageACK
import io.libp2p.pubsub.erasure.message.MutableSampledMessage
import io.libp2p.pubsub.erasure.message.SampledMessage
import io.libp2p.pubsub.erasure.message.SourceMessage
import io.libp2p.pubsub.erasure.message.impl.ErasureHeaderImpl
import io.libp2p.pubsub.erasure.router.MessageRouter
import io.libp2p.pubsub.erasure.router.MessageRouterFactory
import pubsub.pb.Rpc
import pubsub.pb.Rpc.RPC
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

class ErasureRouter(
    executor: ExecutorService,
    val erasureCoder: ErasureCoder,
    val messageRouterFactory: MessageRouterFactory
) : AbstractRouter(
    executor,
    PubsubProtocol.ErasureSub,
    TopicSubscriptionFilter.AllowAllTopicSubscriptionFilter(),
    Int.MAX_VALUE,
    NoopPubsubMessageFactory(),
    NoopSeenCache(),
    NoopPubsubRouterMessageValidator()
) {

    val messageRouters: MutableMap<MessageId, MessageRouter> = mutableMapOf()
    val recentlySeenMessages: MutableSet<MessageId> = mutableSetOf()
    var name = ""

    private var autoFlush = true

    override val pendingRpcParts = PendingRpcPartsMap { ErasureRpcPartsQueue() }

    init {
        messageRouterFactory.sender = this::sendErasureMessageLenient
    }

    override fun publish(msg: PubsubMessage): CompletableFuture<Unit> {
        require(msg.topics.size == 1)
        val topic = msg.topics.first()

        val srcMessage = SourceMessage(topic, msg.messageId, msg.protobufMessage.data.toWBytes())
        val sampledMessage = erasureCoder.extend(srcMessage)

        return publishSampledMessage(sampledMessage)
    }

    fun publishSampledMessage(msg: SampledMessage): CompletableFuture<Unit> {
        require(msg.header.messageId !in messageRouters)
        val messageRouter =
            messageRouterFactory.create(msg as MutableSampledMessage, getTopicPeerIds(msg.header.topic))
        messageRouters[msg.header.messageId] = messageRouter
        autoFlush = false
        try {
            messageRouter.start()
            flushAllPending()
            return CompletableFuture.completedFuture(null)
        } finally {
            autoFlush = true
        }
    }

    private fun onMessageRestored(msg: SourceMessage) {
        val pubsubMessage = ErasurePubsubMessage(msg.messageId, msg.topic, msg.blob)
        msgHandler(pubsubMessage)
    }

    fun processErasureMessage(msg: ErasureMessage, from: PeerHandler) {
        if (msg.messageId in recentlySeenMessages) {
            // just ignore
            return
        }
        val msgRouter = messageRouters[msg.messageId]
            ?: when (msg) {
                is ErasureHeader -> {
                    val sampledMessage = SampledMessage.fromHeader(msg, erasureCoder)
                    sampledMessage.restoredMessage.thenAccept {
                        onMessageRestored(it)
                    }
                    messageRouterFactory.create(sampledMessage, getTopicPeerIds(msg.topic))
                        .also {
                            messageRouters[msg.messageId] = it
                        }
                }
                else ->  throw IllegalStateException("ErasureHeader expected for unknown message: $msg")
            }

        msgRouter.onMessage(msg, from.peerId)

        if (msgRouter.isComplete) {
            recentlySeenMessages += msg.messageId
            messageRouters -= msg.messageId
        }
    }

    fun sendErasureMessageLenient(to: PeerId, msg: List<ErasureMessage>): CompletableFuture<Unit> =
        peerIdToPeerHandlerMap[to]
            ?.let { peerHandler ->
                msg.forEach {
                    enqueueErasureMessage(it, peerHandler)
                }
                val sendPromise = CompletableFuture<Unit>()
                pendingMessagePromises[peerHandler] += sendPromise
                if (autoFlush) {
                    flushPending(peerHandler)
                }
                sendPromise
            }
            ?: CompletableFuture.completedFuture(null)


    fun enqueueErasureMessage(msg: ErasureMessage, to: PeerHandler) {
        val peerParts = pendingRpcParts.getQueue(to)
        when(msg) {
            is ErasureHeader -> peerParts.addPart {
                it.controlBuilder.addErasureHeaderBuilder()
                    .setTopicID(msg.topic)
                    .setMessageID(msg.messageId.toProtobuf())
                    .setTotalSampleCount(msg.totalSampleCount)
                    .setRecoverSampleCount(msg.recoverSampleCount)
            }
            is ErasureSample -> peerParts.addSample(msg)
            is MessageACK -> peerParts.addAck(msg)
        }
    }

    fun processRpcHeader(header: Rpc.ErasureHeader, from: PeerHandler) {
        processErasureMessage(
            ErasureHeaderImpl(
                header.topicID,
                header.messageID.toWBytes(),
                header.totalSampleCount,
                header.recoverSampleCount
            ),
            from
        )
    }

    fun processRpcSample(sample: Rpc.ErasureSample, from: PeerHandler) {
        processErasureMessage(
            ErasureSample(
                sample.messageID.toWBytes(),
                sample.sampleIndex,
                sample.data.toWBytes()
            ),
            from
        )
    }

    fun processRpcAck(ack: Rpc.ErasureAck, from: PeerHandler) {
        processErasureMessage(
            MessageACK(
                ack.messageID.toWBytes(),
                ack.hasSamplesCount,
                ack.peerReceivedSamplesCount
            ),
            from
        )
    }

    override fun processControl(ctrl: Rpc.ControlMessage, receivedFrom: PeerHandler) {
        ctrl.erasureHeaderList.forEach { processRpcHeader(it, receivedFrom) }
        ctrl.erasureAckList.forEach { processRpcAck(it, receivedFrom) }
    }

    override fun onInbound(peer: PeerHandler, msg: Any) {
        autoFlush = false

        try {
            msg as RPC
            super.onInbound(peer, msg)

            msg.erasureSampleList.forEach { processRpcSample(it, peer) }

            flushAllPending()
        } finally {
            autoFlush = true
        }
    }

    private fun getTopicPeerIds(topic: Topic) = getTopicPeers(topic).map { it.peerId }

    override fun broadcastOutbound(msg: PubsubMessage): CompletableFuture<Unit> {
        throw IllegalStateException("Shouldn't get here")
    }

    override fun broadcastInbound(msgs: List<PubsubMessage>, receivedFrom: PeerHandler) {
        require(msgs.isEmpty()) { "No messages are expected" }
    }

    class NoopSeenCache<T> : SeenCache<T> {
        override val size: Int
            get() = throw IllegalStateException("Shouldn't get here")
        override val messages: Collection<PubsubMessage>
            get() = throw IllegalStateException("Shouldn't get here")

        override fun getSeenMessage(msg: PubsubMessage): PubsubMessage {
            throw IllegalStateException("Shouldn't get here")
        }

        override fun getValue(msg: PubsubMessage): T? {
            throw IllegalStateException("Shouldn't get here")
        }

        override fun isSeen(msg: PubsubMessage): Boolean {
            throw IllegalStateException("Shouldn't get here")
        }

        override fun isSeen(messageId: MessageId): Boolean {
            throw IllegalStateException("Shouldn't get here")
        }

        override fun put(msg: PubsubMessage, value: T) {
            throw IllegalStateException("Shouldn't get here")
        }

        override fun remove(msg: PubsubMessage) {
            throw IllegalStateException("Shouldn't get here")
        }
    }

    class NoopPubsubMessageFactory : PubsubMessageFactory {
        override fun invoke(p1: Rpc.Message): PubsubMessage {
            throw IllegalStateException("Shouldn't get here")
        }
    }

    class NoopPubsubRouterMessageValidator : PubsubRouterMessageValidator {
        override fun validate(msg: PubsubMessage) {
            throw IllegalStateException("Shouldn't get here")
        }
    }

    class ErasureRpcPartsQueue : DefaultRpcPartsQueue() {
        val sampleQueue = mutableListOf<SamplePart>()
        var lastAck: AckPart? = null

        class AckPart(val ack: MessageACK) : AbstractPart {
            override fun appendToBuilder(builder: RPC.Builder) {
                builder.controlBuilder.addErasureAckBuilder()
                    .setMessageID(ack.messageId.toProtobuf())
                    .setHasSamplesCount(ack.hasSamplesCount)
                    .setPeerReceivedSamplesCount(ack.peerReceivedSamplesCount)
            }
        }

        class SamplePart(val sample: ErasureSample) : AbstractPart {
            override fun appendToBuilder(builder: RPC.Builder) {
                builder.addErasureSampleBuilder()
                    .setMessageID(sample.messageId.toProtobuf())
                    .setSampleIndex(sample.sampleIndex)
                    .setData(sample.data.toProtobuf())
            }
        }

        fun addSample(sample: ErasureSample) { sampleQueue += SamplePart(sample) }
        fun addAck(ack: MessageACK) {
            lastAck = AckPart(ack)
        }

        fun addPart(appender: (builder: RPC.Builder) -> Unit) =
            addPart(object: AbstractPart {
                override fun appendToBuilder(builder: RPC.Builder) {
                    appender(builder)
                }
            })

        override fun takeMerged(): List<RPC> {
            lastAck?.also {
                addPart(it)
            }
            lastAck = null
            if (sampleQueue.isNotEmpty()) {
                addPart(sampleQueue.removeFirst())
            }
            val ret = super.takeMerged() + sampleQueue.map {
                val bld = RPC.newBuilder()
                it.appendToBuilder(bld)
                bld.build()
            }
            sampleQueue.clear()
            return ret
        }

        override fun isEmpty(): Boolean {
            return super.isEmpty() && sampleQueue.isEmpty() && lastAck == null
        }
    }

    class ErasurePubsubMessage(
        override val messageId: MessageId,
        topic: Topic,
        payload: WBytes
    ) : AbstractPubsubMessage() {

        override val protobufMessage: Rpc.Message =
            Rpc.Message.newBuilder()
                .addTopicIDs(topic)
                .setData(payload.toProtobuf())
                .build()
    }
}