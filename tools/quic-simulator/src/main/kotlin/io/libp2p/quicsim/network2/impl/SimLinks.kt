package io.libp2p.quicsim.network2.impl

import io.libp2p.quicsim.network.SimNode
import io.libp2p.quicsim.network2.SimLink2
import io.libp2p.quicsim.network2.SimQueueDiscipline2

class SimLinks {

    val links = mutableListOf<SimLink2>()
    var defaultQDiscCtor: () -> SimQueueDiscipline2 = { throw IllegalStateException("No default QDiscipline set") }

    fun withQDisc(qdiscCtor: () -> SimQueueDiscipline2) = also { defaultQDiscCtor = qdiscCtor }

    fun addUniDir(from: SimNode, to: SimNode) = addUniDir(from, to, defaultQDiscCtor())
    fun addUniDir(from: SimNode, to: SimNode, qdisc: SimQueueDiscipline2) = also {
        links += SimLink2(from, to, qdisc)
    }

    fun addBiDir(node1: SimNode, node2: SimNode) = addBiDir(node1, node2, defaultQDiscCtor)
    fun addBiDir(node1: SimNode, node2: SimNode, qdiscCtor: () -> SimQueueDiscipline2) =
        addUniDir(node1, node2, qdiscCtor())
            .addUniDir(node2, node1, qdiscCtor())

}