package io.libp2p.example.dc

import io.libp2p.quicsim.sim.SimNodeId
import io.libp2p.quicsim.udpnetwork.Bandwidth
import kotlin.random.Random

/**
 * Description of one group of nodes, configured as a block rather than as a positional argument
 * list:
 *
 * ```
 * builder.addGroup(count = 60) {
 *     spreadOverRegions()
 *     bandwidth = Bandwidths.RESIDENTIAL
 *     validators = 1
 *     peers = 20
 *     randomSubnets(count = 2, of = 64)
 * }
 * ```
 *
 * The same type describes the defaults a group inherits (see [DcNetworkBuilder.defaults] and
 * [DcNetworkBuilder.withDefaults]). A group may freely override anything it inherits; what it may
 * not do is set two competing options *of its own* — one placement, one validator allocation and
 * one subnet assignment each, checked in [validated].
 */
class DcNodeGroup<R> internal constructor(
    private val allRegions: List<R>,
    /** Total attestation subnets in the network, from [DcNetworkBuilder.subnetCount]. */
    private val subnetCount: Int
) {
    private var placement: Placement<R> = Placement.AllRegions()
    private var validatorSpec: ValidatorSpec = ValidatorSpec.PerNode(0)
    private var subnetSpec: SubnetSpec = SubnetSpec.None

    /** Kinds of option assigned by the current block, used to reject competing options. */
    private val assignedKinds = mutableSetOf<String>()

    /** Access link rate of every node in the group. Required. */
    var bandwidth: Bandwidth? = null

    /**
     * Upload rate of every node in the group. Leave null for a symmetric link at [bandwidth].
     *
     * Set it below [bandwidth] to model a consumer connection, where the upload direction is both
     * narrower and, for gossip, the busier one.
     */
    var uploadBandwidth: Bandwidth? = null

    /** Gossip peers each node of the group connects to. */
    var peers: Int = DEFAULT_PEER_COUNT

    // --- placement: at most one per block ------------------------------------------------------

    /** Put the whole group in a single region. */
    var region: R?
        get() = (placement as? Placement.Single)?.region
        set(value) {
            assign("region")
            placement = value?.let { Placement.Single(it) } ?: Placement.AllRegions()
        }

    /** Split the group over regions by weight, using the largest remainder method. */
    var regionWeights: Map<R, Double>?
        get() = (placement as? Placement.Weighted)?.weights
        set(value) {
            assign("regionWeights")
            placement = value?.let { Placement.Weighted(it) } ?: Placement.AllRegions()
        }

    /** Spread the group round-robin over [regions], or over every known region if none are given. */
    fun spreadOverRegions(vararg regions: R) {
        assign("spreadOverRegions")
        placement = if (regions.isEmpty()) Placement.AllRegions() else Placement.RoundRobin(regions.toList())
    }

    // --- validators: at most one per block -----------------------------------------------------

    /** Validators run by each node of the group. */
    var validators: Int
        get() = (validatorSpec as? ValidatorSpec.PerNode)?.count ?: 0
        set(value) {
            assign("validators")
            validatorSpec = ValidatorSpec.PerNode(value)
        }

    /** Validators shared out evenly across the group. */
    var validatorsTotal: Int?
        get() = (validatorSpec as? ValidatorSpec.Total)?.count
        set(value) {
            assign("validatorsTotal")
            validatorSpec = value?.let { ValidatorSpec.Total(it) } ?: ValidatorSpec.PerNode(0)
        }

    // --- subnets: at most one per block --------------------------------------------------------

    /** Attestation subnets every node of the group subscribes to. */
    var subnets: Set<Int>
        get() = (subnetSpec as? SubnetSpec.Fixed)?.ids ?: emptySet()
        set(value) {
            assign("subnets")
            subnetSpec = if (value.isEmpty()) SubnetSpec.None else SubnetSpec.Fixed(value.toSet())
        }

    /**
     * Assigns subnets per node, called with the index of the node *within this group*. Use it for
     * deterministic coverage, e.g. `subnetsByIndex { index -> setOf(index % subnetCount) }`.
     */
    fun subnetsByIndex(subnetIdsOf: (Int) -> Set<Int>) {
        assign("subnetsByIndex")
        subnetSpec = SubnetSpec.ByIndex(subnetIdsOf)
    }

    /**
     * Subscribes each node to [count] distinct subnets drawn at random from `0 until of`. [of]
     * defaults to the network's total subnet count ([DcNetworkBuilder.subnetCount]); pass it
     * explicitly to draw from a smaller range instead. The draw is seeded per node from the
     * builder's seed, so it is reproducible across runs and unaffected by the order in which groups
     * are added.
     */
    fun randomSubnets(count: Int, of: Int = subnetCount) {
        assign("randomSubnets")
        subnetSpec = SubnetSpec.RandomOf(count, of)
    }

    /** Subscribes each node of the group to every subnet in the network (`0 until subnetCount`). */
    fun allSubnets() {
        assign("allSubnets")
        subnetSpec = SubnetSpec.Fixed((0 until subnetCount).toSet())
    }

    private fun assign(kind: String) {
        assignedKinds += kind
    }

    /** A copy usable as the starting point of a nested scope or a group, with no assignments yet. */
    internal fun copyAsTemplate(): DcNodeGroup<R> = DcNodeGroup(allRegions, subnetCount).also {
        it.placement = placement
        it.validatorSpec = validatorSpec
        it.subnetSpec = subnetSpec
        it.bandwidth = bandwidth
        it.uploadBandwidth = uploadBandwidth
        it.peers = peers
    }

    internal fun validated(): DcNodeGroup<R> = apply {
        requireSingleAssignment("placement", "region", "regionWeights", "spreadOverRegions")
        requireSingleAssignment("validator allocation", "validators", "validatorsTotal")
        requireSingleAssignment("subnet assignment", "subnets", "subnetsByIndex", "randomSubnets", "allSubnets")
        require(bandwidth != null) { "bandwidth is required" }
        uploadBandwidth?.let {
            require(it.bytesPerSecond > 0) { "uploadBandwidth must be positive" }
        }
        require(peers >= 0) { "peers must be >= 0, got $peers" }
        when (val spec = validatorSpec) {
            is ValidatorSpec.PerNode -> require(spec.count >= 0) {
                "validators must be >= 0, got ${spec.count}"
            }
            is ValidatorSpec.Total -> require(spec.count >= 0) {
                "validatorsTotal must be >= 0, got ${spec.count}"
            }
        }
        when (val spec = subnetSpec) {
            is SubnetSpec.Fixed -> require(spec.ids.all { it >= 0 }) {
                "attestation subnet ids must be >= 0, got ${spec.ids}"
            }
            is SubnetSpec.RandomOf -> {
                require(spec.of > 0) { "randomSubnets 'of' must be > 0, got ${spec.of}" }
                require(spec.count in 0..spec.of) {
                    "randomSubnets count must be in [0, ${spec.of}], got ${spec.count}"
                }
            }
            else -> Unit
        }
        when (val spec = placement) {
            is Placement.Single -> require(spec.region in allRegions) { "Unknown region: ${spec.region}" }
            is Placement.RoundRobin -> {
                require(spec.regions.isNotEmpty()) { "regions must not be empty" }
                spec.regions.forEach { require(it in allRegions) { "Unknown region: $it" } }
            }
            is Placement.Weighted -> validateWeights(spec.weights)
            is Placement.AllRegions -> Unit
        }
    }

    private fun requireSingleAssignment(what: String, vararg kinds: String) {
        val used = kinds.filter { it in assignedKinds }
        require(used.size <= 1) { "Set at most one $what option, got $used" }
    }

    /** Region of each node of the group, in order. */
    internal fun regionsFor(count: Int): List<R> = when (val spec = placement) {
        is Placement.Single -> List(count) { spec.region }
        is Placement.Weighted -> weightedRegions(count, spec.weights)
        is Placement.RoundRobin -> List(count) { index -> spec.regions[index % spec.regions.size] }
        is Placement.AllRegions -> List(count) { index -> allRegions[index % allRegions.size] }
    }

    /** Validator count of each node of the group, in order. */
    internal fun validatorsFor(count: Int): List<Int> = when (val spec = validatorSpec) {
        is ValidatorSpec.PerNode -> List(count) { spec.count }
        is ValidatorSpec.Total -> distributeEvenly(spec.count, count)
    }

    /** Subnets of the node at [index] within the group, which is node [simNodeId] of the network. */
    internal fun subnetsFor(index: Int, simNodeId: SimNodeId, randomSeed: Long): Set<Int> =
        when (val spec = subnetSpec) {
            is SubnetSpec.None -> emptySet()
            is SubnetSpec.Fixed -> spec.ids
            is SubnetSpec.ByIndex -> spec.subnetIdsOf(index).also { ids ->
                require(ids.all { it >= 0 }) { "attestation subnet ids must be >= 0, got $ids" }
            }
            is SubnetSpec.RandomOf -> drawSubnets(spec, simNodeId, randomSeed)
        }

    private fun drawSubnets(spec: SubnetSpec.RandomOf, simNodeId: SimNodeId, randomSeed: Long): Set<Int> {
        val random = Random(randomSeed + simNodeId * SEED_STRIDE)
        return (0 until spec.of).shuffled(random).take(spec.count).toSortedSet()
    }

    private fun validateWeights(weights: Map<R, Double>) {
        require(weights.isNotEmpty()) { "regionWeights must not be empty" }
        weights.keys.forEach { require(it in allRegions) { "Unknown region: $it" } }
        require(weights.values.all { it >= 0.0 }) { "Region weights must be >= 0: $weights" }
        require(weights.values.sum() > 0.0) { "Region weights must not sum to zero: $weights" }
    }

    private fun weightedRegions(count: Int, weights: Map<R, Double>): List<R> {
        validateWeights(weights)
        val weightSum = weights.values.sum()
        val entries = weights.entries.toList()
        val exact = entries.map { (_, weight) -> count * weight / weightSum }
        val counts = exact.map { it.toInt() }.toMutableList()
        var remaining = count - counts.sum()
        exact.mapIndexed { index, value -> index to (value - counts[index]) }
            .sortedByDescending { it.second }
            .forEach { (index, _) ->
                if (remaining > 0) {
                    counts[index]++
                    remaining--
                }
            }
        return entries.flatMapIndexed { index, entry -> List(counts[index]) { entry.key } }
    }

    private sealed class Placement<out R> {
        class AllRegions<R> : Placement<R>()
        data class Single<R>(val region: R) : Placement<R>()
        data class RoundRobin<R>(val regions: List<R>) : Placement<R>()
        data class Weighted<R>(val weights: Map<R, Double>) : Placement<R>()
    }

    private sealed class ValidatorSpec {
        data class PerNode(val count: Int) : ValidatorSpec()
        data class Total(val count: Int) : ValidatorSpec()
    }

    private sealed class SubnetSpec {
        object None : SubnetSpec()
        data class Fixed(val ids: Set<Int>) : SubnetSpec()
        data class ByIndex(val subnetIdsOf: (Int) -> Set<Int>) : SubnetSpec()
        data class RandomOf(val count: Int, val of: Int) : SubnetSpec()
    }

    companion object {
        /** Peers a node connects to when neither the group nor its defaults say otherwise. */
        const val DEFAULT_PEER_COUNT: Int = 20

        /** Keeps per-node random subnet draws well separated for adjacent node ids. */
        private const val SEED_STRIDE: Long = 1_000_003L
    }
}

/** Splits [total] into [parts] near-equal integers, larger parts first. Sums to [total]. */
internal fun distributeEvenly(total: Int, parts: Int): List<Int> {
    require(parts > 0) { "parts must be > 0, got $parts" }
    val base = total / parts
    val remainder = total % parts
    return List(parts) { index -> base + if (index < remainder) 1 else 0 }
}
