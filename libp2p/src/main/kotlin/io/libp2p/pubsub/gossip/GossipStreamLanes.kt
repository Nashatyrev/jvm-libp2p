package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.etc.util.MAIN_LANE
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.RpcPartsQueue
import io.libp2p.pubsub.SubscriptionOptions
import io.libp2p.pubsub.Topic
import pubsub.pb.Rpc

/**
 * Transport priority of a stream, as RFC 9218 defines it for QUIC: data of a stream with lower
 * [urgency] is sent before any data of higher urgency. Streams of equal urgency share the connection
 * round-robin when [incremental], or go one after another when not.
 * Transports that don't prioritize streams ignore it
 */
data class StreamPriority(val urgency: Int, val incremental: Boolean = true) {
    init {
        require(urgency in 0..127) { "urgency must be in 0..127, got $urgency" }
    }
}

/**
 * Splits the outbound gossip to every peer across several streams ("lanes"), so that a transport
 * which prioritizes streams (QUIC) sends the traffic of urgent topics ahead of the rest.
 *
 * Lane 0 is the regular gossip stream. The other lanes are opened right after it, each as a stream
 * of its own (see [Gossip.LANE_PROTOCOL_PREFIX]). A lane's traffic goes over the lane 0 stream until
 * the lane's own stream is open, and for good if the remote refuses it.
 *
 * Every lane has its own queue, so the RPCs sent over a lane carry only that lane's parts:
 * - publishes, partial messages and IHAVEs go to the lane of their topic, see [laneOfTopic]
 * - GRAFT, PRUNE, IWANT and IDONTWANT go to [controlLane]
 * - subscriptions and the extensions handshake go to lane 0, which opens the conversation as before
 *
 * @param priorities the priority of each lane's stream, by lane
 */
class GossipStreamLanes(
    val priorities: List<StreamPriority>,
    val controlLane: Int = MAIN_LANE,
    private val laneOfTopic: (Topic) -> Int
) {
    init {
        require(priorities.isNotEmpty()) { "At least lane $MAIN_LANE is needed" }
        require(controlLane in priorities.indices) { "Control lane $controlLane is not one of ${priorities.indices}" }
    }

    val count: Int get() = priorities.size

    fun laneOf(topic: Topic): Int =
        laneOfTopic(topic).also {
            require(it in priorities.indices) { "Lane $it of topic $topic is not one of ${priorities.indices}" }
        }
}

/**
 * [GossipRpcPartsQueue] keeping a separate [DefaultGossipRpcPartsQueue] for every lane of [lanes]
 *
 * NOT thread safe
 */
class LanedGossipRpcPartsQueue(
    params: GossipParams,
    private val lanes: GossipStreamLanes
) : GossipRpcPartsQueue {

    private val queues = List(lanes.count) { DefaultGossipRpcPartsQueue(params) }
    private val mainQueue get() = queues[MAIN_LANE]
    private val controlQueue get() = queues[lanes.controlLane]
    private fun topicQueue(topic: Topic) = queues[lanes.laneOf(topic)]

    override val laneCount: Int get() = queues.size
    override fun isEmpty(lane: Int): Boolean = queues[lane].isEmpty()
    override fun popMerged(lane: Int): Rpc.RPC? = queues[lane].popMerged()

    override fun isEmpty(): Boolean = queues.all { it.isEmpty() }
    override fun popMerged(): Rpc.RPC? = queues.firstOrNull { !it.isEmpty() }?.popMerged()
    override fun takeMerged(): List<Rpc.RPC> = generateSequence { popMerged() }.toList()

    override fun addPublish(message: Rpc.Message, messageId: MessageId) =
        (message.topicIDsList.firstOrNull()?.let(::topicQueue) ?: mainQueue).addPublish(message, messageId)

    override fun addSubscription(topic: Topic, status: RpcPartsQueue.SubscriptionStatus, options: SubscriptionOptions) =
        mainQueue.addSubscription(topic, status, options)

    override fun removePublishes(messageIds: Set<MessageId>): Int =
        queues.sumOf { it.removePublishes(messageIds) }

    override fun addIHave(messageId: MessageId, topic: Topic) = topicQueue(topic).addIHave(messageId, topic)
    override fun addIWant(messageId: MessageId) = controlQueue.addIWant(messageId)
    override fun addGraft(topic: Topic) = controlQueue.addGraft(topic)
    override fun addPrune(topic: Topic) = controlQueue.addPrune(topic)
    override fun addPrune(topic: Topic, backoffSeconds: Long, backoffPeers: List<PeerId>) =
        controlQueue.addPrune(topic, backoffSeconds, backoffPeers)

    override fun addControlExtensions(ctrlMessage: Rpc.ControlExtensions) = mainQueue.addControlExtensions(ctrlMessage)

    override fun addPartialMessage(partialMessage: Rpc.PartialMessagesExtension) =
        topicQueue(partialMessage.topicID).addPartialMessage(partialMessage)
}
