package io.libp2p.quicsim.network2

import io.libp2p.quicsim.network.SimNode

interface RouteResolver {

    fun findNextHop(fromNode: SimNode, destNode: SimNode): SimNode?
}