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
 * attributes are named and adding a new one never grows an argument list:
 *
 * ```
 * val network = DcNetworkBuilder.world()
 *     // 4 big staking operators in Europe, subscribed to every subnet
 *     .addGroup(count = 4) {
 *         region = ContinentRegion.EUROPE
 *         bandwidth = Bandwidths.DATACENTER
 *         validators = 500
 *         peers = 100
 *         subnets = (0 until 64).toSet()
 *     }
 *     // 60 home stakers spread over all continents, 2 subnets apiece
 *     .addGroup(count = 60) {
 *         spreadOverRegions()
 *         bandwidth = Bandwidths.RESIDENTIAL
 *         validators = 1
 *         subnetsByIndex { index -> setOf(index % 64, (index + 32) % 64) }
 *     }
 *     // 20 VPS nodes sharing 400 validators between them
 *     .addGroup(count = 20) {
 *         regionWeights = mapOf(ContinentRegion.EUROPE to 0.6, ContinentRegion.ASIA to 0.4)
 *         bandwidth = Bandwidths.VPS
 *         validatorsTotal = 400
 *     }
 *     .build()
 * ```
 *
 * Placement is deterministic (round-robin, or largest remainder for weighted splits), so a given
 * builder sequence always produces the same network.
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

    /** Adds [count] nodes described by [configure]. */
    fun addGroup(count: Int, configure: DcNodeGroup<R>.() -> Unit): DcNetworkBuilder<R> = apply {
        require(count > 0) { "count must be > 0, got $count" }
        val group = DcNodeGroup<R>(descriptor.regions).apply(configure).validated()
        val groupRegions = group.regionsFor(count)
        val groupValidators = group.validatorsFor(count)
        val bandwidthBytesPerSecond = requireNotNull(group.bandwidth).bytesPerSecond

        repeat(count) { index ->
            val simNodeId = nodes.size
            nodes += DcNode(
                simNodeId = simNodeId,
                id = hostId(simNodeId),
                region = groupRegions[index],
                bandwidthBytesPerSecond = bandwidthBytesPerSecond,
                validatorCount = groupValidators[index],
                peerCount = group.peers,
                attestationSubnetIds = group.subnetsFor(index)
            )
        }
    }

    /** Adds a single node described by [configure]. */
    fun addNode(configure: DcNodeGroup<R>.() -> Unit): DcNetworkBuilder<R> = addGroup(1, configure)

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

    companion object {
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
