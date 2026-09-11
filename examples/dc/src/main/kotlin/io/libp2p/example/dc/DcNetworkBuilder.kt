package io.libp2p.example.dc

import io.libp2p.quicsim.scenario.QuicNetworkTopology
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.ContinentRegion
import io.libp2p.quicsim.scenario.RegionalNetworkTopologyBuilder
import io.libp2p.quicsim.sim.SimNodeId
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkDefaults
import kotlin.time.Duration

/**
 * Generates the node population of a Decoupled Consensus network: nodes placed in geographical
 * regions, each with a given access bandwidth, number of validators, peer count, and set of
 * attestation subnets it subscribes to.
 *
 * Nodes are added a group at a time, each group configured as a block (see [DcNodeGroup]) so that
 * attributes are named and adding a new one never grows an argument list. Whatever the groups have
 * in common goes in a surrounding [defaults] or [withDefaults] block:
 *
 * ```
 * val network = DcNetworkBuilder.world(subnetCount = 64)
 *     .defaults {
 *         bandwidth = Bandwidths.RESIDENTIAL
 *         peers = 20
 *         spreadOverRegions()
 *         randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = 2)
 *     }
 *     // 4 big staking operators in Europe, subscribed to every subnet
 *     .addGroup(count = 4) {
 *         region = ContinentRegion.EUROPE
 *         bandwidth = Bandwidths.DATACENTER
 *         validators = 500
 *         peers = 100
 *         allMessageSubnets(DcSlotMessageType.FFG_ATTESTATION)
 *     }
 *     // 60 home stakers on the defaults above
 *     .addGroup(count = 60) { validators = 1 }
 *     // 20 VPS nodes sharing 400 validators between them
 *     .addGroup(count = 20) {
 *         regionWeights = mapOf(ContinentRegion.EUROPE to 0.6, ContinentRegion.ASIA to 0.4)
 *         bandwidth = Bandwidths.VPS
 *         validatorsTotal = 400
 *     }
 *     .build()
 * ```
 *
 * Placement is deterministic (round-robin, or largest remainder for weighted splits) and random
 * subnet draws are seeded per node from [randomSeed], so a given builder sequence always produces
 * the same network. [subnetCount] is the network-wide default subnet range; it is the default `of`
 * for [DcNodeGroup.randomMessageSubnets] and [DcNodeGroup.allMessageSubnets] for any message type not
 * named in [subnetCounts] — e.g. `DcNetworkBuilder.world(subnetCounts = mapOf(BLOB_COLUMN to 128))`
 * means every `allMessageSubnets(BLOB_COLUMN)`/`randomMessageSubnets(BLOB_COLUMN, count = n)` call
 * defaults its range to 128 without repeating that number at each call site. Individual groups can
 * still name a smaller range or a fixed set of subnet ids per message family by passing `of`
 * explicitly.
 *
 * [build] hands back both the [DcNode] descriptors and the [QuicNetworkTopology] to feed to the
 * simulator; the two are index-aligned, so `network.nodes[i].simNodeId == i`.
 */
class DcNetworkBuilder<R>(
    val descriptor: RegionalNetworkDescriptor<R>,
    private val maxQueueWaitTime: Duration = UdpSimNetworkDefaults.MAX_QUEUE_WAIT_TIME,
    private val randomSeed: Long = 0,
    val subnetCount: Int = DEFAULT_SUBNET_COUNT,
    /** Per-[DcSlotMessageType] override of [subnetCount]; see the class doc. */
    val subnetCounts: Map<DcSlotMessageType, Int> = emptyMap(),
    private val hostId: (SimNodeId) -> String = { "node-$it" }
) {
    init {
        require(subnetCount > 0) { "subnetCount must be > 0, got $subnetCount" }
        subnetCounts.forEach { (type, count) ->
            require(count > 0) { "subnetCounts[$type] must be > 0, got $count" }
        }
    }

    private val nodes = mutableListOf<DcNode<R>>()

    /** Innermost defaults; groups start from a copy of this. */
    private var currentDefaults = DcNodeGroup<R>(descriptor.regions, subnetCount, subnetCounts)

    /** Nodes added so far. */
    val nodeCount: Int get() = nodes.size

    /** Validators across all nodes added so far. */
    val validatorCount: Int get() = nodes.sumOf { it.validatorCount }

    /**
     * Sets the defaults every following group inherits. Call it more than once to refine them; each
     * call layers on top of what is already in effect.
     */
    fun defaults(configure: DcNodeGroup<R>.() -> Unit): DcNetworkBuilder<R> = apply {
        currentDefaults = currentDefaults.copyAsTemplate().apply(configure)
    }

    /**
     * Applies [configure] as defaults for the groups added inside [block] only, then restores the
     * previous defaults. Nests freely.
     */
    fun withDefaults(
        configure: DcNodeGroup<R>.() -> Unit,
        block: DcNetworkBuilder<R>.() -> Unit
    ): DcNetworkBuilder<R> = apply {
        val outer = currentDefaults
        currentDefaults = outer.copyAsTemplate().apply(configure)
        try {
            block()
        } finally {
            currentDefaults = outer
        }
    }

    /** Adds [count] nodes described by [configure], on top of the defaults in effect. */
    fun addGroup(count: Int, configure: DcNodeGroup<R>.() -> Unit = {}): DcNetworkBuilder<R> = apply {
        require(count > 0) { "count must be > 0, got $count" }
        val group = currentDefaults.copyAsTemplate().apply(configure).validated()
        val groupRegions = group.regionsFor(count)
        val groupValidators = group.validatorsFor(count)
        val bandwidthBytesPerSecond = requireNotNull(group.bandwidth).bytesPerSecond
        val uploadBandwidthBytesPerSecond =
            group.uploadBandwidth?.bytesPerSecond ?: bandwidthBytesPerSecond

        repeat(count) { index ->
            val simNodeId = nodes.size
            nodes += DcNode(
                simNodeId = simNodeId,
                id = hostId(simNodeId),
                region = groupRegions[index],
                bandwidthBytesPerSecond = bandwidthBytesPerSecond,
                uploadBandwidthBytesPerSecond = uploadBandwidthBytesPerSecond,
                validatorCount = groupValidators[index],
                peerCount = group.peers,
                slotMessageSubnetIds = group.messageSubnetsFor(index, simNodeId, randomSeed),
                groupName = group.name
            )
        }
    }

    /** Adds a single node described by [configure], on top of the defaults in effect. */
    fun addNode(configure: DcNodeGroup<R>.() -> Unit = {}): DcNetworkBuilder<R> = addGroup(1, configure)

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
            topologyBuilder.addHost(
                node.id,
                node.region,
                node.bandwidthBytesPerSecond,
                node.uploadBandwidthBytesPerSecond
            )
        }
        return DcNetwork(builtNodes, topologyBuilder.build())
    }

    companion object {
        /** Total attestation subnets used when a builder does not say otherwise. */
        const val DEFAULT_SUBNET_COUNT: Int = 64

        /** Builder over the simulator's six-continent world model. */
        fun world(
            maxQueueWaitTime: Duration = UdpSimNetworkDefaults.MAX_QUEUE_WAIT_TIME,
            randomSeed: Long = 0,
            subnetCount: Int = DEFAULT_SUBNET_COUNT,
            subnetCounts: Map<DcSlotMessageType, Int> = emptyMap()
        ): DcNetworkBuilder<ContinentRegion> =
            DcNetworkBuilder(
                RegionalNetworkDescriptor.WORLD_DESCRIPTOR_1,
                maxQueueWaitTime,
                randomSeed,
                subnetCount,
                subnetCounts
            )
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

    /** All subnet ids used by one independently routed slot-message family. */
    fun messageSubnetIds(type: DcSlotMessageType): Set<Int> =
        nodes.flatMapTo(sortedSetOf()) { it.subnetIdsFor(type) }

    fun nodesSubscribedTo(type: DcSlotMessageType, subnetId: Int): List<DcNode<R>> =
        nodes.filter { it.subscribesTo(type, subnetId) }

    /** All logical subnet subscriptions; equal numeric ids in different families stay distinct. */
    fun subnetSubscriptions(): Set<DcSubnetSubscription> =
        nodes.flatMapTo(linkedSetOf()) { it.subnetSubscriptions() }

    /** Names of the groups that were given one, in the order they were added. */
    fun groupNames(): Set<String> = nodes.mapNotNullTo(LinkedHashSet()) { it.groupName }

    /** Nodes of the named group, or of all the groups sharing that name. */
    fun nodesInGroup(groupName: String): List<DcNode<R>> = nodes.filter { it.groupName == groupName }

    /** Nodes of any of [groupNames]; every node when [groupNames] is null. */
    fun nodesInGroups(groupNames: Set<String>?): List<DcNode<R>> =
        if (groupNames == null) nodes else nodes.filter { it.groupName in groupNames }

    /** Subscriber count per subnet of [type], useful for spotting under-covered subnets. */
    fun subscribersPerSubnet(type: DcSlotMessageType): Map<Int, Int> =
        messageSubnetIds(type).associateWith { subnetId -> nodesSubscribedTo(type, subnetId).size }

    fun summary(): String = buildString {
        val subnetsByType = subnetSubscriptions().groupBy { it.messageType }
        appendLine(
            "nodes=$nodeCount validators=$validatorCount " +
                "subnets=" + subnetsByType.entries.sortedBy { it.key }
                    .joinToString { (type, ids) -> "${type.id}=${ids.size}" }
        )
        groupNames().forEach { groupName ->
            val groupNodes = nodesInGroup(groupName)
            appendLine(
                "  group '$groupName': nodes=${groupNodes.size} " +
                    "validators=${groupNodes.sumOf { it.validatorCount }}"
            )
        }
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
