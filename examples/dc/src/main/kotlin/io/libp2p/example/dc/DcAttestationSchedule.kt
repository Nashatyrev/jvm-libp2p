package io.libp2p.example.dc

import io.libp2p.core.pubsub.Topic
import io.libp2p.quicsim.sim.SimNodeId
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Who attests, on which subnet, and when.
 *
 * Built up front rather than decided by each node at run time, so that the set of attesters is
 * exactly the requested size and the whole run is reproducible from a seed.
 */
class DcAttestationSchedule(
    val waveTimes: List<Duration>,
    val attestations: List<DcAttestation>
) {
    private val byAttester: Map<SimNodeId, List<DcAttestation>> = attestations.groupBy { it.attesterNodeId }

    val waveCount: Int get() = waveTimes.size

    fun attestationsOf(simNodeId: SimNodeId): List<DcAttestation> = byAttester[simNodeId].orEmpty()

    fun timeOf(attestation: DcAttestation): Duration = waveTimes[attestation.waveIndex]

    /** Last moment anything is published. */
    fun lastWaveTime(): Duration = waveTimes.maxOrNull() ?: Duration.ZERO

    companion object {
        /**
         * At every time in [waveTimes], picks [attestersPerWave] nodes at random among those running
         * at least one validator and subscribed to at least one subnet, and has each attest on one
         * of its own subnets, also chosen at random.
         *
         * Attesters are drawn per wave without replacement, so a wave of size N really is N distinct
         * nodes; across waves a node may be picked again, as in the real protocol.
         */
        fun <R> random(
            network: DcNetwork<R>,
            waveTimes: List<Duration>,
            attestersPerWave: Int,
            randomSeed: Long = 0
        ): DcAttestationSchedule {
            require(attestersPerWave > 0) { "attestersPerWave must be > 0, got $attestersPerWave" }
            val eligible = network.nodes.filter { it.isValidator && it.attestationSubnetIds.isNotEmpty() }
            require(eligible.size >= attestersPerWave) {
                "attestersPerWave=$attestersPerWave exceeds the ${eligible.size} nodes that run a " +
                    "validator and subscribe to a subnet"
            }

            val random = Random(randomSeed)
            var nextId = 0
            val attestations = waveTimes.indices.flatMap { waveIndex ->
                eligible.shuffled(random).take(attestersPerWave).map { node ->
                    DcAttestation(
                        id = nextId++,
                        waveIndex = waveIndex,
                        attesterNodeId = node.simNodeId,
                        subnetId = node.attestationSubnetIds.random(random)
                    )
                }
            }
            return DcAttestationSchedule(waveTimes, attestations)
        }

        /**
         * Every validator in the network attests in every wave. A node running N validators
         * publishes N attestations, each on one of that node's own subnets, chosen independently —
         * so a multi-validator node spreads its attestations across the subnets it subscribes to.
         *
         * This is the "everybody votes at once" shape, and it is much heavier than [random]: the
         * number of published messages is the validator count, not the node count.
         */
        fun <R> allValidators(
            network: DcNetwork<R>,
            waveTimes: List<Duration>,
            randomSeed: Long = 0
        ): DcAttestationSchedule {
            val attesting = network.nodes.filter { it.isValidator && it.attestationSubnetIds.isNotEmpty() }
            require(attesting.isNotEmpty()) {
                "No node runs a validator and subscribes to a subnet"
            }

            val random = Random(randomSeed)
            var nextId = 0
            val attestations = waveTimes.indices.flatMap { waveIndex ->
                attesting.flatMap { node ->
                    List(node.validatorCount) {
                        DcAttestation(
                            id = nextId++,
                            waveIndex = waveIndex,
                            attesterNodeId = node.simNodeId,
                            subnetId = node.attestationSubnetIds.random(random)
                        )
                    }
                }
            }
            return DcAttestationSchedule(waveTimes, attestations)
        }

        /** Evenly spaced wave times, the usual case: one attestation round per slot. */
        fun waveTimes(count: Int, first: Duration, interval: Duration): List<Duration> {
            require(count > 0) { "count must be > 0, got $count" }
            return List(count) { first + interval * it }
        }
    }
}

/** Gossipsub topic per attestation subnet. */
object DcAttestationTopics {
    const val PREFIX = "/dc/attestation/"

    fun of(subnetId: Int): Topic = Topic("$PREFIX$subnetId")

    fun subnetIdOf(topic: Topic): Int? =
        topic.topic.removePrefix(PREFIX).toIntOrNull().takeIf { topic.topic.startsWith(PREFIX) }
}
