package io.libp2p.quicsim.udpnetwork

/**
 * Resolves forwarding decisions over a static [UdpSimNetwork] topology.
 *
 * Implementations may be called concurrently by parallel network controllers, so they must be thread-safe.
 * Any mutable caches used to speed up route lookup must be safely published and protected from concurrent
 * mutation.
 */
interface RouteResolver {

    /**
     * Returns the next hop to use when forwarding a packet from [fromNode] toward [destNode].
     *
     * Returns `null` when [fromNode] is already [destNode]. Implementations should fail fast when no route
     * exists between distinct nodes.
     */
    fun findNextHop(fromNode: UdpSimNode, destNode: UdpSimNode): UdpSimNode?
}
