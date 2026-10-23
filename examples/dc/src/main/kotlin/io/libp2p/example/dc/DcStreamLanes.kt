package io.libp2p.example.dc

import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.GossipStreamLanes
import io.libp2p.pubsub.gossip.StreamPriority
import java.util.concurrent.ConcurrentHashMap

/**
 * Message-type priorities, run as [GossipStreamLanes]: every lane is a gossip stream of its own
 * whose QUIC urgency is its index, so lane 0 is sent first and the last lane only with what is left.
 * Types listed in no lane go on lane 0.
 *
 * @param lanes message types by lane, lane 0 first
 * @param controlLane lane of GRAFT/PRUNE/IWANT/IDONTWANT, see [GossipStreamLanes.controlLane]
 * @param incremental see [StreamPriority.incremental]; with every lane at its own urgency it only
 * matters among the streams of one lane, of which there is one per connection
 */
data class DcStreamLanes(
    val lanes: List<Set<DcSlotMessageType>>,
    val controlLane: Int = 0,
    val incremental: Boolean = true
) {
    init {
        require(lanes.isNotEmpty()) { "At least one lane is needed" }
        require(controlLane in lanes.indices) { "controlLane $controlLane is not one of ${lanes.indices}" }
        val listed = lanes.flatten()
        require(listed.size == listed.toSet().size) { "A message type may be on one lane only: $this" }
    }

    private val laneByType: Map<DcSlotMessageType, Int> =
        lanes.flatMapIndexed { lane, types -> types.map { it to lane } }.toMap()

    /** Shared by every node: the topic parse is cached here, and the cache is safe across event threads */
    val gossipStreamLanes: GossipStreamLanes by lazy {
        val laneByTopic = ConcurrentHashMap<Topic, Int>()
        GossipStreamLanes(
            priorities = lanes.indices.map { StreamPriority(it, incremental) },
            controlLane = controlLane,
            laneOfTopic = { topic ->
                laneByTopic.computeIfAbsent(topic) { DcSlotMessageTopics.typeOf(it)?.let(laneByType::get) ?: 0 }
            }
        )
    }

    override fun toString(): String =
        lanes.joinToString(" | ") { it.joinToString(",") } + " (control on lane $controlLane)"

    companion object {
        /**
         * Lanes written as [DcSlotMessageType.id]s, lanes separated by `|` and types by `,`, e.g.
         * `block,ffg-vote,aggregates|payload-c|blob`
         */
        fun parse(spec: String, controlLane: Int = 0, incremental: Boolean = true): DcStreamLanes =
            DcStreamLanes(
                lanes = spec.split('|').map { lane ->
                    lane.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        .map { requireNotNull(DcSlotMessageType.byId(it)) { "No message type '$it' in '$spec'" } }
                        .toSet()
                },
                controlLane = controlLane,
                incremental = incremental
            )
    }
}
