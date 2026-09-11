package io.libp2p.example.dc

import io.libp2p.core.crypto.sha256
import io.libp2p.core.pubsub.MessageApi
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.AbstractPubsubMessage
import io.libp2p.pubsub.DEFAULT_PUBSUB_MESSAGE_ID_LENGTH
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipScoreParams
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.quicsim.program.GossipNodeProgram
import io.libp2p.quicsim.sim.NetworkContext
import io.libp2p.quicsim.sim.SimContext
import io.libp2p.quicsim.sim.SimNodeId
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandler
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import kotlin.random.Random
import kotlin.time.Duration

/**
 * A node that subscribes to every configured global or subnet message topic and publishes on them
 * when the schedules say so. FFG attestations use this same path as every other message type.
 *
 * Delivery latency is measured by putting the publisher's timestamp in the payload. Every node's
 * scheduler starts at zero and they are advanced in lockstep, so `timer.elapsedTime()` is a clock
 * shared by all nodes and the subtraction is exact — no clock skew to correct for.
 */
class DcNodeProgram(
    simNodeId: SimNodeId,
    connectToNodeIds: List<SimNodeId>,
    private val slotMessageSubnetIds: Map<DcSlotMessageType, Set<Int>>,
    private val completeAt: Duration,
    private val messageSchedules: List<DcSlotMessageSchedule> = emptyList(),
    private val messageRecorders: Map<DcSlotMessageType, DcSlotMessageRecorder> = emptyMap(),
    /** Shared by every node so their [GossipByteCounter]s all bucket wire bytes the same way. */
    private val slotProfile: DcSlotProfileParams? = null,
    params: GossipParams = GossipParams(),
    scoreParams: GossipScoreParams = GossipScoreParams(),
    randomSeed: Long = 0
) : GossipNodeProgram(simNodeId, connectToNodeIds, params, scoreParams, randomSeed) {

    private val random = Random(randomSeed)
    val gossipByteCounter = GossipByteCounter(slotProfile)

    /**
     * Mesh peer counts per topic, sampled at [completeAt] on this node's own event thread — the
     * thread that owns the router's mesh — so the report can read them once the run is over.
     */
    @Volatile
    var finalMeshSizes: List<Int> = emptyList()
        private set

    override fun createDebugGossipHandler(): ChannelHandler = gossipByteCounter

    // Ethereum StrictNoSign: message ID = SHA256(data)[0:20], no from/seqno/signature on wire.
    override fun configureGossipRouterBuilder(builder: GossipRouterBuilder) {
        super.configureGossipRouterBuilder(builder)
        builder.messageFactory = { msg: Rpc.Message ->
            object : AbstractPubsubMessage() {
                override val protobufMessage: Rpc.Message = Rpc.Message.newBuilder()
                    .setData(msg.data)
                    .addAllTopicIDs(msg.topicIDsList)
                    .build()
                override val messageId by lazy {
                    sha256(msg.data.toByteArray()).copyOf(DEFAULT_PUBSUB_MESSAGE_ID_LENGTH).toWBytes()
                }
            }
        }
    }

    init {
        require(messageSchedules.all { it.config.type in messageRecorders }) {
            "Every message schedule needs a recorder to report into"
        }
    }

    override fun start(simContext: SimContext, networkContext: NetworkContext): CompletableFuture<Unit> =
        super.start(simContext, networkContext)

    override fun onAllConnected(simContext: SimContext, networkContext: NetworkContext) {
        // Set before subscribing, so no publish this node's mesh peers send it can arrive un-bucketed.
        gossipByteCounter.currentTimeSupplier = { simContext.timer.elapsedTime() }
        subscribe(simContext)
        schedulePublications(simContext)
        // Every node stops at the same simulated moment, whether or not it published, so the run ends
        // on the settle deadline instead of when the last publisher happens to finish.
        val delay = (completeAt - simContext.timer.elapsedTime()).coerceAtLeast(Duration.ZERO)
        simContext.scheduler.executeAfterDelay(delay) {
            finalMeshSizes = meshSizes()
            completeFuture.complete(Unit)
        }
    }

    /**
     * Every message family owns its topics; global topics are joined by every node.
     *
     * One subscription per message *type*, not per schedule: a type issued as several waves within a
     * slot has one schedule per wave, all of them on the same topics, so subscribing per schedule
     * would register that many consumers on each topic and record every arriving message once per
     * wave — reporting deliveries as a multiple of what actually arrived.
     */
    private fun subscribe(simContext: SimContext) {
        messageSchedules.groupBy { it.config.type }.forEach { (type, schedules) ->
            val subnetIds = slotMessageSubnetIds[type].orEmpty()
            val topics = schedules.flatMap { it.subscriptionsOf(subnetIds) }.distinct()
            if (topics.isNotEmpty()) {
                messageApi.subscribe(
                    Consumer { msg -> onSlotMessage(type, msg, simContext) },
                    *topics.toTypedArray()
                )
            }
        }
    }

    private fun onSlotMessage(type: DcSlotMessageType, msg: MessageApi, simContext: SimContext) {
        val header = headerOf(msg) ?: return
        if (header.kind != DcMessageKind.SLOT_MESSAGE && header.kind != DcMessageKind.BLOCK) return
        messageRecorders.getValue(type).recordDelivered(
            DcSlotMessageDelivery(
                messageId = header.id,
                slotIndex = header.slotIndex,
                receiverNodeId = simNodeId,
                latency = simContext.timer.elapsedTime() - header.publishedAt
            )
        )
    }

    /**
     * Reads only the header out of the message, rather than copying the whole payload. A block is
     * three or four orders of magnitude larger than its header and arrives at every node, so
     * copying it per delivery would dominate the run's own memory traffic.
     */
    private fun headerOf(msg: MessageApi): DcMessageHeader? {
        if (msg.data.readableBytes() < DcMessagePayload.HEADER_BYTES) return null
        val headerBytes = ByteArray(DcMessagePayload.HEADER_BYTES)
        msg.data.getBytes(0, headerBytes)
        return DcMessagePayload.decode(headerBytes)
    }

    private fun schedulePublications(simContext: SimContext) {
        val myMessages = messageSchedules.flatMap { messageSchedule ->
            messageSchedule.messagesOf(simNodeId).map { messageSchedule to it }
        }
        if (myMessages.isEmpty()) return
        val publisher = messageApi.createPublisher(privKey = null, seqIdGenerator = { null })
        myMessages.forEach { (messageSchedule, message) ->
            publishAt(simContext, messageSchedule.timeOf(message)) { publishedAt ->
                messageRecorders.getValue(messageSchedule.config.type)
                    .recordPublished(message, publishedAt)
                publisher.publish(
                    payload(
                        kind = DcMessageKind.SLOT_MESSAGE,
                        id = message.id,
                        slotIndex = message.slotIndex,
                        subnetId = message.subnetId ?: DcMessagePayload.NO_SUBNET,
                        publishedAt = publishedAt,
                        sizeBytes = messageSchedule.config.sizeBytes
                    ),
                    messageSchedule.topicOf(message)
                )
            }
        }
    }

    /** Runs [publish] at simulated time [at], handing it the moment it actually ran. */
    private fun publishAt(simContext: SimContext, at: Duration, publish: (Duration) -> Unit) {
        val delay = (at - simContext.timer.elapsedTime()).coerceAtLeast(Duration.ZERO)
        simContext.scheduler.executeAfterDelay(delay) { publish(simContext.timer.elapsedTime()) }
    }

    private fun payload(
        kind: DcMessageKind,
        id: Int,
        slotIndex: Int,
        subnetId: Int,
        publishedAt: Duration,
        sizeBytes: Int
    ) = Unpooled.wrappedBuffer(
        DcMessagePayload.encode(
            kind = kind,
            id = id,
            slotIndex = slotIndex,
            subnetId = subnetId,
            publishedAt = publishedAt,
            sizeBytes = sizeBytes,
            random = random
        )
    )
}
