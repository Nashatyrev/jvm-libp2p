package io.libp2p.quicsim.network2

/**
 * Simulator packet envelope used by the network engine and queue disciplines.
 */
data class SimPacket(
    /** Packet identifier for tracing/debugging. */
    val id: Long,
    /** Packet size in bytes. */
    val bytes: Int,
    /** Source node id. */
    val srcNodeId: String,
    /** Destination node id. */
    val dstNodeId: String,
    val srcPort: Int = -1,
    val dstPort: Int = -1,
    /** Optional external payload reference. */
    val payloadRef: Any? = null
) {
    /**
     * Default flow key for queue disciplines:
     * traffic grouped by source/destination node pair.
     */
    val flowKey: String
        get() = "$srcNodeId->$dstNodeId"
}
