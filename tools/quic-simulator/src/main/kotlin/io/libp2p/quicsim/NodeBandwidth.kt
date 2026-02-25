package io.libp2p.quicsim

data class NodeBandwidth(
    val inboundBytesPerSecond: Long = Long.MAX_VALUE,
    val outboundBytesPerSecond: Long = Long.MAX_VALUE
) {
    init {
        require(inboundBytesPerSecond > 0) { "inboundBytesPerSecond must be > 0" }
        require(outboundBytesPerSecond > 0) { "outboundBytesPerSecond must be > 0" }
    }

    companion object {
        val UNLIMITED = NodeBandwidth()
    }
}