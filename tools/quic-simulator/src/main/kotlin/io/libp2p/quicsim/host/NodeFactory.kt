package io.libp2p.quicsim.host


interface NodeFactory {

    fun createNode(id: SimNodeId): NodeProgram
}