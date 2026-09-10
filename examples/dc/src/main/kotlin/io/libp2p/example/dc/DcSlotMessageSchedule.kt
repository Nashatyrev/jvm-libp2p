package io.libp2p.example.dc

import io.libp2p.core.pubsub.Topic
import io.libp2p.quicsim.sim.SimNodeId
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Semantic type of a slot message. The built-ins cover the messages currently studied, while the
 * public constructor keeps the model open to new protocol messages without changing an enum, e.g.
 * `DcSlotMessageType("custody-proof")`.
 */
data class DcSlotMessageType(val id: String) {
    init {
        require(ID.matches(id)) {
            "message type id must contain only letters, digits, '.', '_' or '-', got '$id'"
        }
    }

    override fun toString(): String = id

    companion object {
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

        val BLOCK = DcSlotMessageType("block")
        val BLOB_COLUMN = DcSlotMessageType("blob-column")
        val PAYLOAD = DcSlotMessageType("payload")
        val PAYLOAD_CHUNK = DcSlotMessageType("payload-chunk")
        val GOLDFISH_ATTESTATION = DcSlotMessageType("goldfish-attestation")
        val FINALITY_ATTESTATION = DcSlotMessageType("finality-attestation")
    }
}

/** How a publisher is drawn from the nodes selected by [DcSlotMessageConfig.publisherGroups]. */
enum class DcPublisherSelection {
    /** Every node has the same probability, whether or not it hosts validators. */
    RANDOM_NODE,

    /** Probability is proportional to validator count; nodes without validators are excluded. */
    VALIDATOR_WEIGHTED
}

/**
 * One kind of message issued in every slot.
 *
 * [type] identifies the message in reports and gives it a separate `/dc/<type>` gossip topic.
 * Every node subscribes to that topic. A publisher is drawn once per slot and emits
 * [messagesPerSlot] messages, which models both singular objects (a block or payload) and batches
 * (payload chunks or data-availability columns).
 */
data class DcSlotMessageConfig(
    val type: DcSlotMessageType,
    val sizeBytes: Int,
    val publishOffset: Duration = Duration.ZERO,
    val publisherGroups: Set<String>? = null,
    val messagesPerSlot: Int = 1,
    val publisherSelection: DcPublisherSelection = DcPublisherSelection.RANDOM_NODE
) {
    init {
        require(sizeBytes >= DcMessagePayload.HEADER_BYTES) {
            "$type sizeBytes must be at least ${DcMessagePayload.HEADER_BYTES}, got $sizeBytes"
        }
        require(!publishOffset.isNegative()) {
            "$type publishOffset must be >= 0, got $publishOffset"
        }
        require(messagesPerSlot > 0) { "$type messagesPerSlot must be > 0, got $messagesPerSlot" }
        publisherGroups?.let {
            require(it.isNotEmpty()) {
                "publisherGroups must name at least one group; leave it null to allow every group"
            }
        }
    }

    val topic: Topic get() = Topic("/dc/${type.id}")

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
    val publisherNodeId: SimNodeId
) {
    /** Compatibility vocabulary for code that treats slots as attestation waves. */
    val waveIndex: Int get() = slotIndex
}

data class DcSlotMessagePublication(
    val message: DcSlotMessage,
    val publishedAt: Duration
)

data class DcSlotMessageDelivery(
    val messageId: Int,
    val slotIndex: Int,
    val receiverNodeId: SimNodeId,
    val latency: Duration
) {
    val waveIndex: Int get() = slotIndex
}

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

    fun lastPublishTime(): Duration =
        (slotTimes.maxOrNull() ?: Duration.ZERO) + config.publishOffset

    companion object {
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
            val candidates = network.nodesInGroups(config.publisherGroups).let { nodes ->
                when (config.publisherSelection) {
                    DcPublisherSelection.RANDOM_NODE -> nodes
                    DcPublisherSelection.VALIDATOR_WEIGHTED -> nodes.filter { it.isValidator }
                }
            }
            require(candidates.isNotEmpty()) {
                val where = config.publisherGroups?.let { " in $it" }.orEmpty()
                when (config.publisherSelection) {
                    DcPublisherSelection.RANDOM_NODE -> "No node$where can publish ${config.type}"
                    DcPublisherSelection.VALIDATOR_WEIGHTED ->
                        "No node$where runs a validator, so nothing can publish ${config.type}"
                }
            }

            val random = Random(randomSeed)
            val cumulative = if (config.publisherSelection == DcPublisherSelection.VALIDATOR_WEIGHTED) {
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
            val messages = slotTimes.indices.flatMap { slotIndex ->
                // Chunks belonging to one slot come from the same selected producer.
                val publisher = if (cumulative == null) {
                    candidates[random.nextInt(candidates.size)]
                } else {
                    val draw = random.nextLong(cumulative.last())
                    candidates[cumulative.indexOfFirst { it > draw }]
                }
                List(config.messagesPerSlot) { indexInSlot ->
                    DcSlotMessage(nextId++, slotIndex, indexInSlot, publisher.simNodeId)
                }
            }
            return DcSlotMessageSchedule(slotTimes, config, messages)
        }
    }
}
