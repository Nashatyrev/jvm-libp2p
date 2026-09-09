package io.libp2p.example.dc

import io.libp2p.core.pubsub.Topic
import io.libp2p.quicsim.sim.SimNodeId
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import kotlin.time.Duration

/** One block, as scheduled before the run: one per wave, i.e. one per slot. */
data class DcBlock(
    val id: Int,
    val waveIndex: Int,
    val proposerNodeId: SimNodeId
)

/** A block actually going out, with the moment its proposer published it. */
data class DcBlockPublication(
    val block: DcBlock,
    val publishedAt: Duration
)

/** A block arriving at a node. [latency] is measured from the proposer's send time. */
data class DcBlockDelivery(
    val blockId: Int,
    val waveIndex: Int,
    val receiverNodeId: SimNodeId,
    val latency: Duration
)

/**
 * Collects block publications and deliveries during a run. Node programs run on several simulator
 * threads, so both queues are concurrent.
 *
 * Unlike [DcAttestationRecorder] this keeps the absolute publish time as well, since the publish
 * offset within the slot is a configured quantity worth being able to check.
 */
class DcBlockRecorder {
    private val publications = ConcurrentLinkedQueue<DcBlockPublication>()
    private val deliveries = ConcurrentLinkedQueue<DcBlockDelivery>()

    fun recordPublished(block: DcBlock, publishedAt: Duration) {
        publications += DcBlockPublication(block, publishedAt)
    }

    fun recordDelivered(delivery: DcBlockDelivery) {
        deliveries += delivery
    }

    fun published(): List<DcBlockPublication> = publications.sortedBy { it.block.id }

    fun deliveries(): List<DcBlockDelivery> =
        deliveries.sortedWith(compareBy({ it.blockId }, { it.receiverNodeId }))
}

/**
 * The single global topic blocks travel on, subscribed to by every node regardless of which
 * attestation subnets it takes part in — as on mainnet, where `beacon_block` is one global topic.
 */
object DcBlockTopic {
    val TOPIC: Topic = Topic("/dc/block")
}

/**
 * Who proposes in which wave, and when the block goes out.
 *
 * Built up front rather than decided by each node at run time, so the whole run is reproducible
 * from a seed. Publish time is [publishOffset] into the wave, which is what makes a block land part
 * way through a slot rather than exactly on its boundary.
 */
class DcBlockSchedule(
    val waveTimes: List<Duration>,
    val publishOffset: Duration,
    val blocks: List<DcBlock>
) {
    private val byProposer: Map<SimNodeId, List<DcBlock>> = blocks.groupBy { it.proposerNodeId }

    val waveCount: Int get() = waveTimes.size

    fun blocksOf(simNodeId: SimNodeId): List<DcBlock> = byProposer[simNodeId].orEmpty()

    fun timeOf(block: DcBlock): Duration = waveTimes[block.waveIndex] + publishOffset

    /** Last moment a block is published, i.e. the last wave's own publish time. */
    fun lastPublishTime(): Duration =
        (waveTimes.maxOrNull() ?: Duration.ZERO) + publishOffset

    companion object {
        /**
         * One proposer per wave, drawn at random from the validator-running nodes in proportion to
         * how many validators each runs — the mainnet rule, where proposer probability follows
         * stake, so a big staking operator proposes far more often than a home staker.
         *
         * The same node may propose in more than one wave; nothing prevents that on mainnet either.
         */
        fun <R> validatorWeighted(
            network: DcNetwork<R>,
            waveTimes: List<Duration>,
            publishOffset: Duration = Duration.ZERO,
            randomSeed: Long = 0
        ): DcBlockSchedule {
            val proposers = network.nodes.filter { it.isValidator }
            require(proposers.isNotEmpty()) { "No node runs a validator, so nothing can propose" }

            // Cumulative validator counts, so one uniform draw picks a node with probability
            // proportional to its validators without materialising one entry per validator — the
            // studies run millions of validators.
            val cumulative = LongArray(proposers.size)
            var running = 0L
            proposers.forEachIndexed { index, node ->
                running += node.validatorCount
                cumulative[index] = running
            }
            val totalValidators = running

            val random = Random(randomSeed)
            val blocks = waveTimes.indices.map { waveIndex ->
                val draw = random.nextLong(totalValidators)
                val index = cumulative.indexOfFirst { it > draw }
                DcBlock(
                    id = waveIndex,
                    waveIndex = waveIndex,
                    proposerNodeId = proposers[index].simNodeId
                )
            }
            return DcBlockSchedule(waveTimes, publishOffset, blocks)
        }
    }
}
