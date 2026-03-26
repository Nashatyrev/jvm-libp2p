package io.libp2p.quicsim.udpnetwork

interface RouteResolver {

    fun findNextHop(fromNode: UdpSimNode, destNode: UdpSimNode): UdpSimNode?
}
