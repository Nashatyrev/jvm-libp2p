package io.libp2p.quicsim.program

import io.libp2p.quicsim.sim.SimNodeId


interface NodeProgramFactory {

    fun createNode(id: SimNodeId): NodeProgram
}