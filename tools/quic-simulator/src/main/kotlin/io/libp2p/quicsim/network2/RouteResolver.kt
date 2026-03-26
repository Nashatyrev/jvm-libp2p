package io.libp2p.quicsim.network2

interface RouteResolver {

    fun findNextHop(fromNode: SimNode, destNode: SimNode): SimNode?
}
