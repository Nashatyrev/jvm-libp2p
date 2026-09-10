package io.libp2p.example.dc

import io.libp2p.quicsim.sim.SimNodeId
import io.libp2p.quicsim.udpnetwork.Bandwidth

/**
 * A single node of a Decoupled Consensus network: where it sits geographically, how fat its access
 * link is, and how many validators it runs.
 *
 * Bandwidth is held as a plain `Long` rather than as [Bandwidth] so that value equality of this
 * data class behaves as expected ([Bandwidth] compares by identity).
 */
data class DcNode<R>(
    /** Index of the node in the network, and its [SimNodeId] in the simulator. */
    val simNodeId: SimNodeId,
    /** Host id used in the [io.libp2p.quicsim.scenario.QuicNetworkTopology]. */
    val id: String,
    /** Geographical region the node is attached to. */
    val region: R,
    /** Download rate (network -> host), and the default for the upload direction too. */
    val bandwidthBytesPerSecond: Long,
    /**
     * Upload rate (host -> network). Defaults to [bandwidthBytesPerSecond], i.e. a symmetric link.
     *
     * Worth setting below the download rate for consumer connections, which are typically
     * asymmetric — and in gossip the upload direction is the scarce one, since a node forwards each
     * message to every mesh peer but receives it once per peer that has it.
     */
    val uploadBandwidthBytesPerSecond: Long = bandwidthBytesPerSecond,
    /** Number of validators hosted by this node. Zero means a non-validating (full) node. */
    val validatorCount: Int,
    /** Number of gossip peers this node maintains connections to. */
    val peerCount: Int,
    /**
     * Subnet subscriptions, independently routed per slot-message family — including FFG
     * attestation, which is a [DcSlotMessageType] like any other.
     *
     * The numeric ids are local to each [DcSlotMessageType]: payload subnet 7, blob-column subnet
     * 7, and FFG-attestation subnet 7 are three different gossip meshes.
     */
    val slotMessageSubnetIds: Map<DcSlotMessageType, Set<Int>> = emptyMap(),
    /**
     * Name of the [DcNodeGroup] this node came from, or null if the group was not named.
     *
     * Names exist so that a scenario can refer back to part of the population it built — picking
     * block or payload publishers out of the staking pools only, say — without having to know which
     * node ids the builder happened to assign.
     */
    val groupName: String? = null
) {
    val bandwidth: Bandwidth get() = Bandwidth(bandwidthBytesPerSecond)

    val uploadBandwidth: Bandwidth get() = Bandwidth(uploadBandwidthBytesPerSecond)

    val hasAsymmetricLink: Boolean get() = uploadBandwidthBytesPerSecond != bandwidthBytesPerSecond

    val isValidator: Boolean get() = validatorCount > 0

    fun subscribesTo(type: DcSlotMessageType, subnetId: Int): Boolean =
        subnetId in subnetIdsFor(type)

    fun subnetIdsFor(type: DcSlotMessageType): Set<Int> =
        slotMessageSubnetIds[type].orEmpty()

    internal fun subnetSubscriptions(): Set<DcSubnetSubscription> = buildSet {
        slotMessageSubnetIds.forEach { (type, subnetIds) ->
            subnetIds.forEach { add(DcSubnetSubscription(type, it)) }
        }
    }

    override fun toString(): String {
        val link = if (hasAsymmetricLink) "$bandwidth down/$uploadBandwidth up" else "$bandwidth"
        val group = groupName?.let { "$it, " } ?: ""
        val messageSubnets = slotMessageSubnetIds.entries
            .sortedBy { it.key.id }
            .joinToString { (type, ids) -> "${type.id}=${ids.sorted()}" }
        val extra = if (messageSubnets.isEmpty()) "" else ", subnets={$messageSubnets}"
        return "$id[$group$region, $link, validators=$validatorCount, peers=$peerCount$extra]"
    }
}

/** One logical subnet. Numeric ids are namespaced by the message-type family. */
data class DcSubnetSubscription(
    val messageType: DcSlotMessageType,
    val subnetId: Int
) {
    init {
        require(subnetId >= 0) { "subnetId must be >= 0, got $subnetId" }
    }

    override fun toString(): String = "${messageType.id}/$subnetId"
}

/** Convenience constructors for [Bandwidth] in the units people actually quote links in. */
object Bandwidths {
    private const val BITS_PER_BYTE = 8L

    fun mbitPerSecond(mbit: Long): Bandwidth = Bandwidth(mbit * 1_000_000L / BITS_PER_BYTE)

    fun gbitPerSecond(gbit: Long): Bandwidth = mbitPerSecond(gbit * 1_000L)

    fun bytesPerSecond(bytesPerSecond: Long): Bandwidth = Bandwidth(bytesPerSecond)

    /** Typical home connection. */
    val RESIDENTIAL: Bandwidth = mbitPerSecond(50)

    /** Typical small VPS / cloud instance. */
    val VPS: Bandwidth = mbitPerSecond(500)

    /** Well provisioned data centre node, e.g. a large staking operator. */
    val DATACENTER: Bandwidth = gbitPerSecond(1)
}
