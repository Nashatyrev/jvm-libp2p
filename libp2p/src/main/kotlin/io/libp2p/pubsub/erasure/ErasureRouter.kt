package io.libp2p.pubsub.erasure

import io.libp2p.core.PeerId
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.AbstractRouter
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
import io.libp2p.pubsub.erasure.message.MutableSampledMessage
import io.libp2p.pubsub.erasure.message.SampledMessage
import io.libp2p.pubsub.erasure.message.SourceMessage
import io.libp2p.pubsub.erasure.router.MessageRouter
import io.libp2p.pubsub.erasure.router.MessageRouterFactory
import pubsub.pb.Rpc
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
        messageRouter.start()
        return CompletableFuture.completedFuture(null)
    }

    fun processErasureMessage(msg: ErasureMessage, from: PeerHandler) {
        val msgRouter = messageRouters[msg.messageId]
            ?: when (msg) {
                is ErasureHeader -> {
                    val sampledMessage = SampledMessage.fromHeader(msg)
                    messageRouterFactory.create(sampledMessage, getTopicPeerIds(msg.topic))
                        .also {
                            messageRouters[msg.messageId] = it
                        }
                }
                else -> if (msg.messageId in recentlySeenMessages) {
                    // just ignore
                    return
                } else {
                    throw IllegalStateException("ErasureHeader expected for unknown message: $msg")
                }
            }

        msgRouter.onMessage(msg, from.peerId)

        if (msgRouter.isComplete) {
            recentlySeenMessages += msg.messageId
            messageRouters -= msg.messageId
        }
    }

    fun sendErasureMessageLenient(to: PeerId, msg: ErasureMessage): CompletableFuture<Unit> =
        peerIdToPeerHandlerMap[to]
            ?.let {
                sendErasureMessage(msg, it)
            }
            ?: CompletableFuture.completedFuture(null)


    fun sendErasureMessage(msg: ErasureMessage, to: PeerHandler): CompletableFuture<Unit> {
        TODO()
    }

    override fun processControl(ctrl: Rpc.ControlMessage, receivedFrom: PeerHandler) {
        TODO("Deserialize ErasureMessage and call processErasureMessage")
    }

    private fun getTopicPeerIds(topic: Topic) = getTopicPeers(topic).map { it.peerId }

    override fun broadcastOutbound(msg: PubsubMessage): CompletableFuture<Unit> {
        throw IllegalStateException("Shouldn't get here")
    }

    override fun broadcastInbound(msgs: List<PubsubMessage>, receivedFrom: PeerHandler) {
        throw IllegalStateException("Shouldn't get here")
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
}