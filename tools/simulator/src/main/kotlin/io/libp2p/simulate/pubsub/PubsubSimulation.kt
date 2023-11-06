package io.libp2p.simulate.pubsub

import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.PubsubSubscription
import io.libp2p.core.pubsub.Topic
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.core.pubsub.Validator
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.simulate.SimPeerId
import io.libp2p.simulate.stats.collect.ConnectionsMessageCollector
import io.libp2p.simulate.stats.collect.gossip.SimMessageId
import io.libp2p.tools.schedule
import io.netty.buffer.Unpooled
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

data class SimMessage(
    val simMessageId: SimMessageId,
    val sendingPeer: Int,
    val sentTime: Long,
    val pubResult: CompletableFuture<Unit>
)

data class SimMessageDelivery(
    val message: SimMessage,
    val receivingPeer: Int,
    val receiveTime: Long,
)

abstract class PubsubSimulation(
    open val cfg: SimPubsubConfig,
    open val network: SimPubsubNetwork
) {

    private val idCounter = AtomicLong(0)

    private val subscriptions = mutableMapOf<SimPubsubPeer, MutableMap<Topic, PubsubSubscription>>()

    private val publishedMessagesMut = LinkedHashMap<SimMessageId, SimMessage>()
    val publishedMessages: List<SimMessage> get() = publishedMessagesMut.values.toList()
    private val pendingValidationCount = AtomicInteger()
    private val deliveredMessagesCount = mutableMapOf<SimMessageId, AtomicInteger>()

    val apiMessageDeliveries = mutableListOf<SimMessageDelivery>()

    val currentTimeSupplier: CurrentTimeSupplier = { network.timeController.time }

    abstract val messageCollector: ConnectionsMessageCollector<*>

    fun start() {
        subscribeAll()
        forwardTime(cfg.warmUpDelay)
    }

    private fun subscribeAll() {
        network.peers.values.forEach { peer ->
            cfg.peerConfigs[peer.simPeerId].topics.forEach { topic ->
                subscribe(peer, topic)
            }
        }
    }

    private fun onNewApiMessage(receivingPeer: SimPubsubPeer, msg: MessageApi) {
        val simMessageId = cfg.pubsubMessageSizes.messageBodyGenerator.messageIdRetriever(msg.data.array())
        deliveredMessagesCount.computeIfAbsent(simMessageId) { AtomicInteger() }.incrementAndGet()
        apiMessageDeliveries += SimMessageDelivery(
            publishedMessagesMut[simMessageId]!!,
            receivingPeer.simPeerId,
            currentTimeSupplier()
        )
    }

    fun subscribe(peer: SimPubsubPeer, topic: Topic) {
        check(!(subscriptions[peer]?.contains(topic) ?: false))
        val subscription = peer.api.subscribe(
            Validator { message ->
                onNewApiMessage(peer, message)
                val (validationDelay, validationResult) =
                    cfg.peerConfigs[peer.simPeerId].messageValidationGenerator(message)
                if (validationDelay == Duration.ZERO) {
                    CompletableFuture.completedFuture(validationResult)
                } else {
                    val ret = CompletableFuture<ValidationResult>()
                    pendingValidationCount.incrementAndGet()
                    peer.simExecutor.schedule(validationDelay) {
                        ret.complete(validationResult)
                        pendingValidationCount.decrementAndGet()
                    }
                    ret
                }
            },
            topic
        )
        subscriptions.computeIfAbsent(peer) { mutableMapOf() }[topic] = subscription
    }

    fun unsubscribe(peer: SimPubsubPeer, topic: Topic) {
        val peerSubscriptions = subscriptions[peer]
            ?: throw IllegalArgumentException("No subscriptions found for peer $peer")
        val subscription = peerSubscriptions.remove(topic)
            ?: throw IllegalArgumentException("Peer $peer is not subscribed to topic '$topic'")
        subscription.unsubscribe()
    }

    fun forwardTime(duration: Duration): Long {
        network.timeController.addTime(duration.toJavaDuration())
        return network.timeController.time
    }

    fun forwardTimeUntilAllPubDelivered(step: Duration = 1.seconds, maxDuration: Duration = 1.minutes) {
        var totalDuration = 0.seconds
        while (totalDuration <= maxDuration && !isAllMessagesDelivered()) {
            network.timeController.addTime(step.toJavaDuration())
            totalDuration += step
        }
    }

    fun forwardTimeUntilNoPendingMessages(
        step: Duration = 1.seconds,
        maxDuration: Duration = 1.minutes,
        maxPendingMessagesAllowed: Int = 10
    ) {
        var totalDuration = 0.seconds
        while (totalDuration <= maxDuration && messageCollector.pendingMessages.size > maxPendingMessagesAllowed) {
            network.timeController.addTime(step.toJavaDuration())
            totalDuration += step
        }
    }

    fun isAllMessagesDelivered(): Boolean =
        deliveredMessagesCount.values.sumOf { it.get() } == publishedMessagesMut.size * (network.peers.size - 1)

    fun publishMessage(srcPeer: SimPeerId): SimMessage {
        val peerTopics = cfg.peerConfigs[srcPeer].topics
        require(peerTopics.size == 1)
        return publishMessage(srcPeer, 0, peerTopics[0])
    }

    fun publishMessage(srcPeer: SimPeerId, size: Int, topic: Topic): SimMessage {
        val peer = network.peers[srcPeer] ?: throw IllegalArgumentException("Invalid peer index $srcPeer")
        val msgId = idCounter.incrementAndGet()

        val msg = Unpooled.wrappedBuffer(cfg.pubsubMessageSizes.messageBodyGenerator.msgGenerator(msgId, size))
        val future = peer.apiPublisher.publish(msg, topic)
        val ret = SimMessage(msgId, srcPeer, network.timeController.time, future)
        publishedMessagesMut[msgId] = ret
        return ret
    }

    fun clearAllMessages() {
        messageCollector.clear()
    }
}
