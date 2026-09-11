package io.libp2p.example.dc

import io.libp2p.core.pubsub.Topic
import io.libp2p.quicsim.sim.SimNodeId
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Semantic type of a slot message. [id] is the short name used on the wire (as the gossip topic
 * suffix) and in every report.
 *
 * Declaration order is report order: anything printed per message type — the columns of
 * [DcSlotTrafficProfile], the per-type sections of [DcAttestationReport] and [DcGroupStats] — is
 * ordered by [Enum.ordinal], so the constants are listed in the order they occur within a slot.
 */
enum class DcSlotMessageType(val id: String) {
    BLOCK("block"),
    PAYLOAD("payload"),
    PAYLOAD_CHUNK("payload-c"),
    BLOB_COLUMN("blob"),
    GOLDFISH_ATTESTATION("ac-vote"),
    FFG_ATTESTATION("ffg-vote");

    override fun toString(): String = id

    companion object {
        private val BY_ID = values().associateBy { it.id }

        /** The type with this [id], or null if no constant uses it. */
        fun byId(id: String): DcSlotMessageType? = BY_ID[id]
    }
}

/** A compact repeated-wave definition shared by every configured slot-message type. */
data class DcSlotMessageWaves(
    val count: Int,
    val first: Duration,
    val interval: Duration
) {
    init {
        require(count > 0) { "count must be > 0, got $count" }
        require(!first.isNegative()) { "first must be >= 0, got $first" }
        require(interval.isPositive()) { "interval must be > 0, got $interval" }
    }

    val times: List<Duration> get() = List(count) { first + interval * it }
}

/** Gossip topic topology used to disseminate one message type. */
sealed class DcSlotMessageTopics {
    /** One topic subscribed to by every node. */
    object Global : DcSlotMessageTopics() {
        override fun toString(): String = "global"
    }

    /**
     * One topic per subnet. Nodes join the topic ids independently assigned to this message type
     * in [DcNode.slotMessageSubnetIds] that fall within `0 until subnetCount`.
     */
    data class Subnets(val subnetCount: Int) : DcSlotMessageTopics() {
        init {
            require(subnetCount > 0) { "subnetCount must be > 0, got $subnetCount" }
        }

        override fun toString(): String = "$subnetCount subnets"
    }

    internal fun topic(type: DcSlotMessageType, subnetId: Int?): Topic = when (this) {
        Global -> {
            require(subnetId == null) { "global $type message must not have a subnet, got $subnetId" }
            Topic("$TOPIC_PREFIX${type.id}")
        }
        is Subnets -> {
            require(subnetId != null && subnetId in 0 until subnetCount) {
                "$type subnet must be in 0 until $subnetCount, got $subnetId"
            }
            Topic("$TOPIC_PREFIX${type.id}/$subnetId")
        }
    }

    internal fun subscriptions(type: DcSlotMessageType, nodeSubnetIds: Set<Int>): List<Topic> =
        when (this) {
            Global -> listOf(topic(type, null))
            is Subnets -> nodeSubnetIds.filter { it in 0 until subnetCount }.map { topic(type, it) }
        }

    companion object {
        private const val TOPIC_PREFIX = "/dc/"

        /**
         * Recovers the [DcSlotMessageType] a wire topic string belongs to — the inverse of [topic],
         * for code that only has the topic (e.g. [GossipByteCounter], reading a topic ID off an
         * inbound RPC with no other context on what published it). Null for anything not shaped like
         * one of our own topics, including a prefixed topic whose type segment names no constant.
         */
        fun typeOf(topic: String): DcSlotMessageType? {
            if (!topic.startsWith(TOPIC_PREFIX)) return null
            val rest = topic.removePrefix(TOPIC_PREFIX)
            return DcSlotMessageType.byId(rest.substringBefore('/'))
        }
    }
}

/** How a publisher is drawn from the nodes selected by [DcSlotMessageConfig.publisherGroups]. */
enum class DcPublisherSelection {
    /**
     * One producer per slot, drawn uniformly, whether or not it hosts validators; it alone emits
     * every one of [DcSlotMessageConfig.messagesPerSlot] messages — the shape of a proposer
     * splitting one payload into many chunks.
     */
    RANDOM_NODE,

    /** As [RANDOM_NODE], but the draw is weighted by validator count; nodes with none are excluded. */
    VALIDATOR_WEIGHTED,

    /** Every validator publishes one message in every wave; a node publishes once per validator. */
    ALL_VALIDATORS,

    /**
     * [DcSlotMessageConfig.messagesPerSlot] distinct validator-running nodes are drawn without
     * replacement each slot, uniformly over nodes, each publishing exactly one message on one of
     * its own subscribed subnets — unlike [RANDOM_NODE]/[VALIDATOR_WEIGHTED], the messages of one
     * slot come from that many different nodes rather than one node emitting them all. The shape of
     * a sampled subset of attesters voting independently, each on their own subnet.
     */
    RANDOM_NODES,

    /**
     * As [RANDOM_NODES], but the draw is over individual validators rather than nodes: one entry per
     * validator, so a multi-validator node is proportionally more likely to be drawn and may appear
     * more than once in the same slot. [DcSlotMessageConfig.messagesPerSlot] may therefore exceed
     * the node count, as long as it does not exceed the validator count.
     */
    RANDOM_VALIDATORS
}

/**
 * One kind of message issued in every slot.
 *
 * [type] identifies the message in reports and namespaces its gossip [topics]. A publisher is drawn
 * once per slot and emits [messagesPerSlot] messages, which models both singular objects (a block
 * or payload) and batches (payload chunks or data-availability columns). With
 * [DcPublisherSelection.ALL_VALIDATORS], every validator emits one message and
 * [messagesPerSlot] must remain one.
 */
data class DcSlotMessageConfig(
    val type: DcSlotMessageType,
    val sizeBytes: Int,
    val publishOffset: Duration = Duration.ZERO,
    val publisherGroups: Set<String>? = null,
    val messagesPerSlot: Int = 1,
    val publisherSelection: DcPublisherSelection = DcPublisherSelection.RANDOM_NODE,
    val topics: DcSlotMessageTopics = DcSlotMessageTopics.Global
) {
    init {
        require(sizeBytes >= DcMessagePayload.HEADER_BYTES) {
            "$type sizeBytes must be at least ${DcMessagePayload.HEADER_BYTES}, got $sizeBytes"
        }
        require(!publishOffset.isNegative()) {
            "$type publishOffset must be >= 0, got $publishOffset"
        }
        require(messagesPerSlot > 0) { "$type messagesPerSlot must be > 0, got $messagesPerSlot" }
        require(publisherSelection != DcPublisherSelection.ALL_VALIDATORS || messagesPerSlot == 1) {
            "$type ALL_VALIDATORS already emits one message per validator; messagesPerSlot must be 1"
        }
        publisherGroups?.let {
            require(it.isNotEmpty()) {
                "publisherGroups must name at least one group; leave it null to allow every group"
            }
        }
    }

    companion object {
        /** Largest application payload that fits in one gossipsub frame under [params]. */
        fun maxSizeBytes(params: io.libp2p.pubsub.gossip.GossipParams): Int {
            val margin = maxOf(64, params.maxGossipMessageSize / 100)
            return params.maxGossipMessageSize - margin - 256
        }
    }
}

/** One configured message, as scheduled before the simulation starts. */
data class DcSlotMessage(
    val id: Int,
    val slotIndex: Int,
    val indexInSlot: Int,
    val publisherNodeId: SimNodeId,
    /** Null for a global topic; otherwise the subnet topic carrying this message. */
    val subnetId: Int? = null,
    /** Topic namespace and subscriber assignment used for this message. */
    val type: DcSlotMessageType
)

data class DcSlotMessagePublication(
    val message: DcSlotMessage,
    val publishedAt: Duration
)

data class DcSlotMessageDelivery(
    val messageId: Int,
    val slotIndex: Int,
    val receiverNodeId: SimNodeId,
    val latency: Duration
)

class DcSlotMessageRecorder {
    private val publications = ConcurrentLinkedQueue<DcSlotMessagePublication>()
    private val deliveries = ConcurrentLinkedQueue<DcSlotMessageDelivery>()

    fun recordPublished(message: DcSlotMessage, publishedAt: Duration) {
        publications += DcSlotMessagePublication(message, publishedAt)
    }

    fun recordDelivered(delivery: DcSlotMessageDelivery) {
        deliveries += delivery
    }

    fun published(): List<DcSlotMessagePublication> = publications.sortedBy { it.message.id }

    fun deliveries(): List<DcSlotMessageDelivery> =
        deliveries.sortedWith(compareBy({ it.messageId }, { it.receiverNodeId }))
}

/** The complete deterministic issuance schedule for one [config]. */
class DcSlotMessageSchedule(
    val slotTimes: List<Duration>,
    val config: DcSlotMessageConfig,
    val messages: List<DcSlotMessage>
) {
    private val byPublisher = messages.groupBy { it.publisherNodeId }

    fun messagesOf(simNodeId: SimNodeId): List<DcSlotMessage> = byPublisher[simNodeId].orEmpty()

    fun timeOf(message: DcSlotMessage): Duration = slotTimes[message.slotIndex] + config.publishOffset

    fun topicOf(message: DcSlotMessage): Topic = config.topics.topic(config.type, message.subnetId)

    fun subscriptionsOf(nodeSubnetIds: Set<Int>): List<Topic> =
        config.topics.subscriptions(config.type, nodeSubnetIds)

    fun lastPublishTime(): Duration =
        (slotTimes.maxOrNull() ?: Duration.ZERO) + config.publishOffset

    companion object {
        /** Creates the same configured issuance in every wave without listing wave times. */
        fun <R> create(
            network: DcNetwork<R>,
            waves: DcSlotMessageWaves,
            config: DcSlotMessageConfig,
            randomSeed: Long = 0
        ): DcSlotMessageSchedule = create(network, waves.times, config, randomSeed)

        fun <R> create(
            network: DcNetwork<R>,
            slotTimes: List<Duration>,
            config: DcSlotMessageConfig,
            randomSeed: Long = 0
        ): DcSlotMessageSchedule {
            config.publisherGroups?.let { requested ->
                val unknown = requested - network.groupNames()
                require(unknown.isEmpty()) {
                    "publisherGroups names no such group: $unknown; the network has " +
                        if (network.groupNames().isEmpty()) "no named groups" else "${network.groupNames()}"
                }
            }
            if (config.topics is DcSlotMessageTopics.Subnets) {
                val missing = (0 until config.topics.subnetCount)
                    .filter { network.nodesSubscribedTo(config.type, it).isEmpty() }
                require(missing.isEmpty()) {
                    "${config.type} subnet topics have no subscribers: $missing"
                }
            }

            val selection = config.publisherSelection
            val groupNodes = network.nodesInGroups(config.publisherGroups)
            val where = config.publisherGroups?.let { " in $it" }.orEmpty()

            // RANDOM_NODES additionally requires a subscribed subnet up front, since its draw must
            // be over nodes that can actually name one -- ALL_VALIDATORS/RANDOM_VALIDATORS check
            // this per validator instead, since two validators on the same node can differ in which
            // subnets they cover isn't modelled, but the node-level filter would otherwise still be
            // correct for them too.
            val requiresOwnSubnet = config.topics is DcSlotMessageTopics.Subnets &&
                selection == DcPublisherSelection.RANDOM_NODES
            val candidates = groupNodes.filter { node ->
                val validatorOk = selection == DcPublisherSelection.RANDOM_NODE || node.isValidator
                val subnetOk = !requiresOwnSubnet || node.subnetIdsFor(config.type).isNotEmpty()
                validatorOk && subnetOk
            }
            require(candidates.isNotEmpty()) {
                when (selection) {
                    DcPublisherSelection.RANDOM_NODE -> "No node$where can publish ${config.type}"
                    DcPublisherSelection.RANDOM_NODES -> if (requiresOwnSubnet) {
                        "No node$where runs a validator subscribed to a ${config.type} subnet"
                    } else {
                        "No node$where runs a validator, so nothing can publish ${config.type}"
                    }
                    DcPublisherSelection.VALIDATOR_WEIGHTED,
                    DcPublisherSelection.ALL_VALIDATORS,
                    DcPublisherSelection.RANDOM_VALIDATORS ->
                        "No node$where runs a validator, so nothing can publish ${config.type}"
                }
            }
            if (selection == DcPublisherSelection.RANDOM_NODES) {
                require(candidates.size >= config.messagesPerSlot) {
                    "${config.type} messagesPerSlot=${config.messagesPerSlot} exceeds the " +
                        "${candidates.size} eligible nodes$where"
                }
            }

            // One entry per validator, so a multi-validator node is drawn proportionally more often
            // -- needed for RANDOM_VALIDATORS, which draws individual validator-slots rather than
            // whole nodes. A node needs a subscribed subnet the same way RANDOM_NODES' own
            // candidates do, since each drawn slot must be able to name one.
            val validatorSlots: List<DcNode<R>>? = if (selection == DcPublisherSelection.RANDOM_VALIDATORS) {
                val eligible = candidates.filter {
                    config.topics !is DcSlotMessageTopics.Subnets || it.subnetIdsFor(config.type).isNotEmpty()
                }
                eligible.flatMap { node -> List(node.validatorCount) { node } }.also {
                    require(it.size >= config.messagesPerSlot) {
                        "${config.type} messagesPerSlot=${config.messagesPerSlot} exceeds the " +
                            "${it.size} eligible validators$where"
                    }
                }
            } else {
                null
            }

            val random = Random(randomSeed)
            val cumulative = if (selection == DcPublisherSelection.VALIDATOR_WEIGHTED) {
                LongArray(candidates.size).also { weights ->
                    var running = 0L
                    candidates.forEachIndexed { index, node ->
                        running += node.validatorCount
                        weights[index] = running
                    }
                }
            } else {
                null
            }

            var nextId = 0
            fun ownSubnetOf(publisher: DcNode<R>): Int? = (config.topics as? DcSlotMessageTopics.Subnets)?.let { topics ->
                val subscribed = publisher.subnetIdsFor(config.type).filter { it in 0 until topics.subnetCount }
                require(subscribed.isNotEmpty()) {
                    "${config.type} publisher node-${publisher.simNodeId} has no subscribed subnet"
                }
                subscribed.random(random)
            }

            val messages = slotTimes.indices.flatMap { slotIndex ->
                when (selection) {
                    DcPublisherSelection.ALL_VALIDATORS -> {
                        var indexInSlot = 0
                        candidates.flatMap { publisher ->
                            List(publisher.validatorCount) {
                                DcSlotMessage(
                                    nextId++,
                                    slotIndex,
                                    indexInSlot++,
                                    publisher.simNodeId,
                                    ownSubnetOf(publisher),
                                    config.type
                                )
                            }
                        }
                    }

                    DcPublisherSelection.RANDOM_NODES ->
                        candidates.shuffled(random).take(config.messagesPerSlot).mapIndexed { indexInSlot, publisher ->
                            DcSlotMessage(nextId++, slotIndex, indexInSlot, publisher.simNodeId, ownSubnetOf(publisher), config.type)
                        }

                    DcPublisherSelection.RANDOM_VALIDATORS ->
                        validatorSlots!!.shuffled(random).take(config.messagesPerSlot)
                            .mapIndexed { indexInSlot, publisher ->
                                DcSlotMessage(nextId++, slotIndex, indexInSlot, publisher.simNodeId, ownSubnetOf(publisher), config.type)
                            }

                    DcPublisherSelection.RANDOM_NODE, DcPublisherSelection.VALIDATOR_WEIGHTED -> {
                        // Every message of one slot comes from the same selected producer.
                        val publisher = if (cumulative == null) {
                            candidates[random.nextInt(candidates.size)]
                        } else {
                            val draw = random.nextLong(cumulative.last())
                            candidates[cumulative.indexOfFirst { it > draw }]
                        }
                        List(config.messagesPerSlot) { indexInSlot ->
                            val subnetId = (config.topics as? DcSlotMessageTopics.Subnets)?.let {
                                indexInSlot % it.subnetCount
                            }
                            DcSlotMessage(nextId++, slotIndex, indexInSlot, publisher.simNodeId, subnetId, config.type)
                        }
                    }
                }
            }
            return DcSlotMessageSchedule(slotTimes, config, messages)
        }
    }
}
