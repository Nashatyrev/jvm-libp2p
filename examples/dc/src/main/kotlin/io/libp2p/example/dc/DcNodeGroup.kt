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
 *     name = "home stakers"
 *     spreadOverRegions()
 *     bandwidth = Bandwidths.RESIDENTIAL
 *     validators = 1
 *     peers = 20
 *     randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = 2, of = 64)
 * }
 * ```
 *
 * The same type describes the defaults a group inherits (see [DcNetworkBuilder.defaults] and
 * [DcNetworkBuilder.withDefaults]). A group may freely override anything it inherits; what it may
 * not do is set two competing options *of its own* — one placement, one validator allocation, and
 * at most one subnet assignment per message family — checked in [validated].
 */
class DcNodeGroup<R> internal constructor(
    private val allRegions: List<R>,
    /** Network-wide default subnet range, from [DcNetworkBuilder.subnetCount]. */
    private val subnetCount: Int,
    /** Per-type override of [subnetCount], from [DcNetworkBuilder.subnetCounts]. */
    private val subnetCounts: Map<DcSlotMessageType, Int> = emptyMap()
) {
    private var placement: Placement<R> = Placement.AllRegions()
    private var validatorSpec: ValidatorSpec = ValidatorSpec.PerNode(0)
    private val slotMessageSubnetSpecs = mutableMapOf<DcSlotMessageType, SubnetSpec>()

    /** Kinds of option assigned by the current block, used to reject competing options. */
    private val assignedKinds = mutableSetOf<String>()

    /**
     * Name of the group, carried onto every node it produces as [DcNode.groupName]. Optional, and
     * only needed when something later wants to refer back to this part of the population — e.g.
     * [DcSlotMessageConfig.publisherGroups], which picks message publishers out of named groups.
     *
     * Like every other option it can be set in a [DcNetworkBuilder.defaults] block, in which case
     * the groups underneath share the name and are selected together as one pool.
     */
    var name: String? = null

    /** Access link rate of every node in the group. Required. */
    var bandwidth: DcLink? = null

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

    // --- independently namespaced slot-message subnets ---------------------------------------

    /** Assigns fixed [subnetIds] for one slot-message family to every node in the group. */
    fun messageSubnets(type: DcSlotMessageType, subnetIds: Set<Int>) {
        assignMessageSubnets(type, "messageSubnets")
        slotMessageSubnetSpecs[type] =
            if (subnetIds.isEmpty()) SubnetSpec.None else SubnetSpec.Fixed(subnetIds.toSet())
    }

    /** Assigns a slot-message family's subnets from the node index within this group. */
    fun messageSubnetsByIndex(
        type: DcSlotMessageType,
        subnetIdsOf: (Int) -> Set<Int>
    ) {
        assignMessageSubnets(type, "messageSubnetsByIndex")
        slotMessageSubnetSpecs[type] = SubnetSpec.ByIndex(subnetIdsOf)
    }

    /**
     * Draws [count] independent subscriptions for [type] from `0 until of`. [of] defaults to
     * [type]'s entry in [DcNetworkBuilder.subnetCounts], or the network's total subnet count
     * ([DcNetworkBuilder.subnetCount]) if [type] has none; pass it explicitly to draw from a
     * different range instead. The draw is seeded per node from the builder's seed, so it is
     * reproducible across runs and unaffected by the order in which groups are added.
     */
    fun randomMessageSubnets(type: DcSlotMessageType, count: Int, of: Int = subnetCountFor(type)) {
        assignMessageSubnets(type, "randomMessageSubnets")
        slotMessageSubnetSpecs[type] = SubnetSpec.RandomOf(count, of)
    }

    /**
     * Subscribes every node in the group to every subnet for [type] (`0 until of`). [of] defaults
     * the same way as in [randomMessageSubnets].
     */
    fun allMessageSubnets(type: DcSlotMessageType, of: Int = subnetCountFor(type)) {
        assignMessageSubnets(type, "allMessageSubnets")
        slotMessageSubnetSpecs[type] = SubnetSpec.Fixed((0 until of).toSet())
    }

    /** [type]'s own subnet count, or the network-wide default if it was not given one. */
    private fun subnetCountFor(type: DcSlotMessageType): Int = subnetCounts[type] ?: subnetCount

    private fun assignMessageSubnets(type: DcSlotMessageType, option: String) {
        assign("slot-message:${type.id}:$option")
    }

    private fun assign(kind: String) {
        assignedKinds += kind
    }

    /** A copy usable as the starting point of a nested scope or a group, with no assignments yet. */
    internal fun copyAsTemplate(): DcNodeGroup<R> = DcNodeGroup(allRegions, subnetCount, subnetCounts).also {
        it.placement = placement
        it.validatorSpec = validatorSpec
        it.slotMessageSubnetSpecs.putAll(slotMessageSubnetSpecs)
        it.name = name
        it.bandwidth = bandwidth
        it.uploadBandwidth = uploadBandwidth
        it.peers = peers
    }

    internal fun validated(): DcNodeGroup<R> = apply {
        requireSingleAssignment("placement", "region", "regionWeights", "spreadOverRegions")
        requireSingleAssignment("validator allocation", "validators", "validatorsTotal")
        slotMessageSubnetSpecs.keys.forEach { type ->
            val prefix = "slot-message:${type.id}:"
            val used = assignedKinds.filter { it.startsWith(prefix) }
            require(used.size <= 1) {
                "Set at most one ${type.id} subnet assignment, got ${used.map { it.removePrefix(prefix) }}"
            }
        }
        require(bandwidth != null) { "bandwidth is required" }
        name?.let { require(it.isNotBlank()) { "name must not be blank" } }
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
        slotMessageSubnetSpecs.forEach { (type, spec) -> validateSubnetSpec("${type.id} subnet", spec) }
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

    /** Independently assigned subnet ids, keyed by slot-message family. */
    internal fun messageSubnetsFor(
        index: Int,
        simNodeId: SimNodeId,
        randomSeed: Long
    ): Map<DcSlotMessageType, Set<Int>> =
        slotMessageSubnetSpecs.mapValues { (type, spec) ->
            val familySeed = randomSeed + type.id.hashCode().toLong() * FAMILY_SEED_STRIDE
            resolveSubnetSpec("${type.id} subnet", spec, index, simNodeId, familySeed)
        }.filterValues { it.isNotEmpty() }

    private fun resolveSubnetSpec(
        description: String,
        spec: SubnetSpec,
        index: Int,
        simNodeId: SimNodeId,
        randomSeed: Long
    ): Set<Int> = when (spec) {
        is SubnetSpec.None -> emptySet()
        is SubnetSpec.Fixed -> spec.ids
        is SubnetSpec.ByIndex -> spec.subnetIdsOf(index).also { ids ->
            require(ids.all { it >= 0 }) { "$description ids must be >= 0, got $ids" }
        }
        is SubnetSpec.RandomOf -> drawSubnets(spec, simNodeId, randomSeed)
    }

    private fun validateSubnetSpec(description: String, spec: SubnetSpec) {
        when (spec) {
            is SubnetSpec.Fixed -> require(spec.ids.all { it >= 0 }) {
                "$description ids must be >= 0, got ${spec.ids}"
            }
            is SubnetSpec.RandomOf -> {
                require(spec.of > 0) { "$description random 'of' must be > 0, got ${spec.of}" }
                require(spec.count in 0..spec.of) {
                    "$description random count must be in [0, ${spec.of}], got ${spec.count}"
                }
            }
            else -> Unit
        }
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

        /** Separates deterministic random draws for different topic families. */
        private const val FAMILY_SEED_STRIDE: Long = 10_000_019L
    }
}

/** Splits [total] into [parts] near-equal integers, larger parts first. Sums to [total]. */
internal fun distributeEvenly(total: Int, parts: Int): List<Int> {
    require(parts > 0) { "parts must be > 0, got $parts" }
    val base = total / parts
    val remainder = total % parts
    return List(parts) { index -> base + if (index < remainder) 1 else 0 }
}
