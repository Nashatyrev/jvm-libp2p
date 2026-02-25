package io.libp2p.quicsim.network

data class SimPacket(
    val id: Long,
    val bytes: Int,
    val srcNodeId: String,
    val dstNodeId: String,
    val payloadRef: Any? = null
) {
    val flowKey: String
        get() = "$srcNodeId->$dstNodeId"
}
