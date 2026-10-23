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

    fun addSubscribe(topic: Topic, options: SubscriptionOptions = SubscriptionOptions()) {
        addSubscription(topic, SubscriptionStatus.Subscribed, options)
    }

    fun addUnsubscribe(topic: Topic) {
        addSubscription(topic, SubscriptionStatus.Unsubscribed)
    }

    fun addSubscription(topic: Topic, status: SubscriptionStatus, options: SubscriptionOptions = SubscriptionOptions())

    /** Returns whether there are no RPC parts waiting to be sent. */
    fun isEmpty(): Boolean

    fun popMerged(): Rpc.RPC?

    fun takeMerged(): List<Rpc.RPC>

    /**
     * Number of lanes the parts are split into. Each lane is flushed on its own, to the peer's stream
     * of that lane (see [io.libp2p.etc.util.P2PService.StreamHandler.lane]), with [popMerged] of the lane
     */
    val laneCount: Int get() = 1

    fun isEmpty(lane: Int): Boolean = isEmpty()

    fun popMerged(lane: Int): Rpc.RPC? = popMerged()
}

/** Optional protocol extensions attached to a topic subscription. */
data class SubscriptionOptions(
    val requestsPartial: Boolean = false,
    val supportsSendingPartial: Boolean = false
)

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

    protected data class SubscriptionPart(
        val topic: Topic,
        val status: RpcPartsQueue.SubscriptionStatus,
        val options: SubscriptionOptions
    ) : AbstractPart {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            builder.addSubscriptionsBuilder().apply {
                setTopicid(topic)
                setSubscribe(status == RpcPartsQueue.SubscriptionStatus.Subscribed)
                if (status == RpcPartsQueue.SubscriptionStatus.Subscribed) {
                    setRequestsPartial(options.requestsPartial)
                    setSupportsSendingPartial(options.supportsSendingPartial)
                }
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

    override fun addSubscription(topic: Topic, status: RpcPartsQueue.SubscriptionStatus, options: SubscriptionOptions) {
        addPart(SubscriptionPart(topic, status, options))
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
