package io.libp2p.pubsub

import pubsub.pb.Rpc

interface RpcPartsQueue {

    enum class SubscriptionStatus { Subscribed, Unsubscribed }

    /**
     * Queues a publish together with its pubsub message ID.
     *
     * The ID is retained so protocol extensions can remove a publish before it is flushed.
     */
    fun addPublish(message: Rpc.Message, messageId: MessageId = defaultPubsubMessageId(message))

    fun addSubscribe(topic: Topic) {
        addSubscription(topic, SubscriptionStatus.Subscribed)
    }

    fun addUnsubscribe(topic: Topic) {
        addSubscription(topic, SubscriptionStatus.Unsubscribed)
    }

    fun addSubscription(topic: Topic, status: SubscriptionStatus)

    /** Returns whether there are no RPC parts waiting to be sent. */
    fun isEmpty(): Boolean

    fun popMerged(): Rpc.RPC?

    fun takeMerged(): List<Rpc.RPC>
}

/**
 * Default [RpcPartsQueue] implementation
 *
 * NOT thread safe
 */
open class DefaultRpcPartsQueue : RpcPartsQueue {

    protected interface AbstractPart {
        fun appendToBuilder(builder: Rpc.RPC.Builder)
    }

    protected data class PublishPart(val message: Rpc.Message, val messageId: MessageId) : AbstractPart {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            builder.addPublish(message)
        }
    }

    protected data class SubscriptionPart(val topic: Topic, val status: RpcPartsQueue.SubscriptionStatus) : AbstractPart {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            builder.addSubscriptionsBuilder().apply {
                setTopicid(topic)
                setSubscribe(status == RpcPartsQueue.SubscriptionStatus.Subscribed)
            }
        }
    }

    protected open val parts = mutableListOf<AbstractPart>()

    protected open fun addPart(part: AbstractPart) {
        parts += part
    }

    override fun addPublish(message: Rpc.Message, messageId: MessageId) {
        addPart(PublishPart(message, messageId))
    }

    override fun addSubscription(topic: Topic, status: RpcPartsQueue.SubscriptionStatus) {
        addPart(SubscriptionPart(topic, status))
    }

    override fun isEmpty(): Boolean = parts.isEmpty()

    override fun popMerged(): Rpc.RPC? {
        if (parts.isEmpty()) return null

        val builder = Rpc.RPC.newBuilder()
        parts.forEach {
            it.appendToBuilder(builder)
        }
        parts.clear()
        return builder.build()
    }

    override fun takeMerged(): List<Rpc.RPC> {
        val builder = Rpc.RPC.newBuilder()
        parts.forEach {
            it.appendToBuilder(builder)
        }
        parts.clear()
        return listOf(builder.build())
    }
}
