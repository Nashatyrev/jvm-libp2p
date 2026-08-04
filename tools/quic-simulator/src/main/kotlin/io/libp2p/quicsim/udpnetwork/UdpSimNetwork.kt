package io.libp2p.quicsim.udpnetwork

/**
 * Static network topology for the simulator engine.
 */
interface UdpSimNetwork {
    /** All nodes participating in this topology. */
    val nodes: List<UdpSimNode>
    /** Directed links between nodes. */
    val links: List<UdpSimLink>


    companion object {
        val UdpSimNetwork.nodesAndRouters get() = links.flatMap { listOf(it.from, it.to) }.distinct()
        val UdpSimNetwork.routers get() = nodesAndRouters - this.findEndpoints()
        fun UdpSimNetwork.findEndpoints(): Set<UdpSimNode> =
            links
                .map { it.from }
                .groupingBy { it }
                .eachCount()
                .filter { it.value == 1 }
                .keys
    }
}
