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
    /** Access link rate, applied in both directions. */
    val bandwidthBytesPerSecond: Long,
    /** Number of validators hosted by this node. Zero means a non-validating (full) node. */
    val validatorCount: Int
) {
    val bandwidth: Bandwidth get() = Bandwidth(bandwidthBytesPerSecond)

    val isValidator: Boolean get() = validatorCount > 0

    override fun toString(): String =
        "$id[$region, $bandwidth, validators=$validatorCount]"
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
