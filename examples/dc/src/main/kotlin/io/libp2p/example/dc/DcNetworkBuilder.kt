package io.libp2p.example.dc

import io.libp2p.quicsim.scenario.QuicNetworkTopology
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.ContinentRegion
import io.libp2p.quicsim.scenario.RegionalNetworkTopologyBuilder
import io.libp2p.quicsim.sim.SimNodeId
import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkDefaults
import kotlin.time.Duration

/**
 * Generates the node population of a Decoupled Consensus network: nodes placed in geographical
 * regions, each with a given access bandwidth, number of validators, peer count, and set of
 * attestation subnets it subscribes to.
 *
 * Nodes are added in groups, so a realistic population is a handful of calls:
 *
 * ```
 * val network = DcNetworkBuilder.world()
 *     // 4 big staking operators in Europe, 500 validators each, subscribed to every subnet
 *     .addNodes(
 *         count = 4, region = ContinentRegion.EUROPE, bandwidth = Bandwidths.DATACENTER,
 *         validatorsPerNode = 500, peers = 100, attestationSubnetIds = (0 until 64).toSet()
 *     )
 *     // 60 home stakers spread over all continents, 1 validator each, 2 subnets apiece
 *     .addNodesWithSubnets(
 *         count = 60, bandwidth = Bandwidths.RESIDENTIAL, validatorsPerNode = 1, peers = 20,
 *         subnetIdsOf = { index -> setOf(index % 64, (index + 32) % 64) }
 *     )
 *     // 20 VPS nodes sharing 400 validators between them
 *     .addNodesWithTotalValidators(count = 20, bandwidth = Bandwidths.VPS, validatorsTotal = 400)
 *     .build()
 * ```
 *
 * Region assignment is deterministic (round-robin, or largest remainder for weighted splits), so a
 * given builder sequence always produces the same network.
 *
 * [build] hands back both the [DcNode] descriptors and the [QuicNetworkTopology] to feed to the
 * simulator; the two are index-aligned, so `network.nodes[i].simNodeId == i`.
 */
class DcNetworkBuilder<R>(
    val descriptor: RegionalNetworkDescriptor<R>,
    private val maxQueueWaitTime: Duration = UdpSimNetworkDefaults.MAX_QUEUE_WAIT_TIME,
    private val hostId: (SimNodeId) -> String = { "node-$it" }
) {
    private val nodes = mutableListOf<DcNode<R>>()

    /** Nodes added so far. */
    val nodeCount: Int get() = nodes.size

    /** Validators across all nodes added so far. */
    val validatorCount: Int get() = nodes.sumOf { it.validatorCount }

    /** Adds a single node in [region]. */
    fun addNode(
        region: R,
        bandwidth: Bandwidth,
        validators: Int = 0,
        peers: Int = DEFAULT_PEER_COUNT,
        attestationSubnetIds: Set<Int> = emptySet()
    ): DcNetworkBuilder<R> = apply {
        require(region in descriptor.regions) { "Unknown region: $region" }
        require(validators >= 0) { "validators must be >= 0, got $validators" }
        require(peers >= 0) { "peers must be >= 0, got $peers" }
        require(attestationSubnetIds.all { it >= 0 }) {
            "attestation subnet ids must be >= 0, got $attestationSubnetIds"
        }
        val simNodeId = nodes.size
        nodes += DcNode(
            simNodeId = simNodeId,
            id = hostId(simNodeId),
            region = region,
            bandwidthBytesPerSecond = bandwidth.bytesPerSecond,
            validatorCount = validators,
            peerCount = peers,
            attestationSubnetIds = attestationSubnetIds.toSet()
        )
    }

    /** Adds [count] identical nodes, all in [region], each running [validatorsPerNode] validators. */
    fun addNodes(
        count: Int,
        region: R,
        bandwidth: Bandwidth,
        validatorsPerNode: Int = 0,
        peers: Int = DEFAULT_PEER_COUNT,
        attestationSubnetIds: Set<Int> = emptySet()
    ): DcNetworkBuilder<R> = apply {
        requirePositiveCount(count)
        repeat(count) { addNode(region, bandwidth, validatorsPerNode, peers, attestationSubnetIds) }
    }

    /**
     * Adds [count] nodes spread round-robin over [regions] (all of the descriptor's regions by
     * default), each running [validatorsPerNode] validators.
     */
    fun addNodes(
        count: Int,
        bandwidth: Bandwidth,
        validatorsPerNode: Int = 0,
        regions: List<R> = descriptor.regions,
        peers: Int = DEFAULT_PEER_COUNT,
        attestationSubnetIds: Set<Int> = emptySet()
    ): DcNetworkBuilder<R> = apply {
        requirePositiveCount(count)
        require(regions.isNotEmpty()) { "regions must not be empty" }
        repeat(count) { index ->
            addNode(regions[index % regions.size], bandwidth, validatorsPerNode, peers, attestationSubnetIds)
        }
    }

    /**
     * Adds [count] nodes distributed over regions according to [regionWeights], using the largest
     * remainder method so the per-region counts always add up to [count] exactly.
     */
    fun addNodes(
        count: Int,
        regionWeights: Map<R, Double>,
        bandwidth: Bandwidth,
        validatorsPerNode: Int = 0,
        peers: Int = DEFAULT_PEER_COUNT,
        attestationSubnetIds: Set<Int> = emptySet()
    ): DcNetworkBuilder<R> = apply {
        requirePositiveCount(count)
        countsByWeight(count, regionWeights).forEach { (region, regionNodeCount) ->
            addNodes(regionNodeCount, region, bandwidth, validatorsPerNode, peers, attestationSubnetIds)
        }
    }

    /**
     * Adds [count] nodes whose attestation subnets are assigned per node by [subnetIdsOf], which is
     * called with the index of the node *within this group*. Use it to spread subnet subscriptions
     * over a group, e.g. `subnetIdsOf = { index -> setOf(index % subnetCount) }`.
     */
    fun addNodesWithSubnets(
        count: Int,
        bandwidth: Bandwidth,
        subnetIdsOf: (Int) -> Set<Int>,
        validatorsPerNode: Int = 0,
        regions: List<R> = descriptor.regions,
        peers: Int = DEFAULT_PEER_COUNT
    ): DcNetworkBuilder<R> = apply {
        requirePositiveCount(count)
        require(regions.isNotEmpty()) { "regions must not be empty" }
        repeat(count) { index ->
            addNode(regions[index % regions.size], bandwidth, validatorsPerNode, peers, subnetIdsOf(index))
        }
    }

    /**
     * Adds [count] nodes in [region] and spreads [validatorsTotal] validators across them as evenly
     * as possible; the remainder goes to the first nodes of the group.
     */
    fun addNodesWithTotalValidators(
        count: Int,
        region: R,
        bandwidth: Bandwidth,
        validatorsTotal: Int,
        peers: Int = DEFAULT_PEER_COUNT,
        attestationSubnetIds: Set<Int> = emptySet()
    ): DcNetworkBuilder<R> = apply {
        requirePositiveCount(count)
        distribute(validatorsTotal, count).forEach { validators ->
            addNode(region, bandwidth, validators, peers, attestationSubnetIds)
        }
    }

    /**
     * Adds [count] nodes spread round-robin over [regions] and splits [validatorsTotal] evenly
     * across them.
     */
    fun addNodesWithTotalValidators(
        count: Int,
        bandwidth: Bandwidth,
        validatorsTotal: Int,
        regions: List<R> = descriptor.regions,
        peers: Int = DEFAULT_PEER_COUNT,
        attestationSubnetIds: Set<Int> = emptySet()
    ): DcNetworkBuilder<R> = apply {
        requirePositiveCount(count)
        require(regions.isNotEmpty()) { "regions must not be empty" }
        distribute(validatorsTotal, count).forEachIndexed { index, validators ->
            addNode(regions[index % regions.size], bandwidth, validators, peers, attestationSubnetIds)
        }
    }

    /**
     * A node cannot have more peers than there are other nodes, so requested peer counts are
     * clamped to `nodeCount - 1` here — the point at which the final node count is known.
     */
    fun build(): DcNetwork<R> {
        require(nodes.isNotEmpty()) { "Network must contain at least one node" }
        val maxPeers = nodes.size - 1
        val builtNodes = nodes.map { node ->
            if (node.peerCount <= maxPeers) node else node.copy(peerCount = maxPeers)
        }
        val topologyBuilder = RegionalNetworkTopologyBuilder(descriptor, maxQueueWaitTime)
        builtNodes.forEach { node ->
            topologyBuilder.addHost(node.id, node.region, node.bandwidthBytesPerSecond)
        }
        return DcNetwork(builtNodes, topologyBuilder.build())
    }

    private fun requirePositiveCount(count: Int) =
        require(count > 0) { "count must be > 0, got $count" }

    /** Splits [total] into [parts] near-equal integers, larger parts first. Sums to [total]. */
    private fun distribute(total: Int, parts: Int): List<Int> {
        require(total >= 0) { "validatorsTotal must be >= 0, got $total" }
        val base = total / parts
        val remainder = total % parts
        return List(parts) { index -> base + if (index < remainder) 1 else 0 }
    }

    /** Largest remainder apportionment of [count] over weighted regions. */
    private fun countsByWeight(count: Int, regionWeights: Map<R, Double>): List<Pair<R, Int>> {
        require(regionWeights.isNotEmpty()) { "regionWeights must not be empty" }
        regionWeights.keys.forEach { region ->
            require(region in descriptor.regions) { "Unknown region: $region" }
        }
        require(regionWeights.values.all { it >= 0.0 }) { "Region weights must be >= 0: $regionWeights" }
        val weightSum = regionWeights.values.sum()
        require(weightSum > 0.0) { "Region weights must not sum to zero: $regionWeights" }

        val entries = regionWeights.entries.toList()
        val exact = entries.map { (_, weight) -> count * weight / weightSum }
        val floors = exact.map { it.toInt() }.toMutableList()
        var remaining = count - floors.sum()
        exact.mapIndexed { index, value -> index to (value - floors[index]) }
            .sortedByDescending { it.second }
            .forEach { (index, _) ->
                if (remaining > 0) {
                    floors[index]++
                    remaining--
                }
            }
        return entries.mapIndexed { index, entry -> entry.key to floors[index] }
            .filter { it.second > 0 }
    }

    companion object {
        /** Peers a node connects to when the caller does not say otherwise. */
        const val DEFAULT_PEER_COUNT: Int = 20

        /** Builder over the simulator's six-continent world model. */
        fun world(
            maxQueueWaitTime: Duration = UdpSimNetworkDefaults.MAX_QUEUE_WAIT_TIME
        ): DcNetworkBuilder<ContinentRegion> =
            DcNetworkBuilder(RegionalNetworkDescriptor.WORLD_DESCRIPTOR_1, maxQueueWaitTime)
    }
}

/** The generated node population together with the simulator topology it maps onto. */
data class DcNetwork<R>(
    val nodes: List<DcNode<R>>,
    val topology: QuicNetworkTopology
) {
    val nodeCount: Int get() = nodes.size

    val validatorCount: Int get() = nodes.sumOf { it.validatorCount }

    val validatorNodes: List<DcNode<R>> get() = nodes.filter { it.isValidator }

    fun node(simNodeId: SimNodeId): DcNode<R> = nodes[simNodeId]

    fun nodesIn(region: R): List<DcNode<R>> = nodes.filter { it.region == region }

    fun validatorsIn(region: R): Int = nodesIn(region).sumOf { it.validatorCount }

    fun nodesByRegion(): Map<R, List<DcNode<R>>> = nodes.groupBy { it.region }

    /** All attestation subnets at least one node subscribes to. */
    fun attestationSubnetIds(): Set<Int> = nodes.flatMapTo(sortedSetOf()) { it.attestationSubnetIds }

    fun nodesSubscribedTo(subnetId: Int): List<DcNode<R>> = nodes.filter { it.subscribesTo(subnetId) }

    /** Subscriber count per attestation subnet, useful for spotting under-covered subnets. */
    fun subscribersPerSubnet(): Map<Int, Int> =
        attestationSubnetIds().associateWith { subnetId -> nodesSubscribedTo(subnetId).size }

    fun summary(): String = buildString {
        appendLine("nodes=$nodeCount validators=$validatorCount subnets=${attestationSubnetIds().size}")
        nodesByRegion().forEach { (region, regionNodes) ->
            appendLine(
                "  $region: nodes=${regionNodes.size} " +
                    "validators=${regionNodes.sumOf { it.validatorCount }} " +
                    "peers=${regionNodes.map { it.peerCount }.distinct().sorted().joinToString("/")} " +
                    "bandwidths=${regionNodes.map { it.bandwidth.toString() }.distinct().joinToString()}"
            )
        }
    }
}
