package io.libp2p.quicsim.program

import io.libp2p.core.dsl.BuilderJ
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.quicsim.sim.NetworkContext
import io.libp2p.quicsim.sim.SimContext
import io.libp2p.quicsim.sim.SimNodeId
import java.util.concurrent.CompletableFuture

interface NodeProgram {

    val simNodeId: SimNodeId

    fun createProtocols(context: SimContext): List<ProtocolBinding<*>>

    fun modifyBuilder(context: SimContext, builder: BuilderJ) {}

    fun start(simContext: SimContext, networkContext: NetworkContext): CompletableFuture<Unit>

    fun isComplete(): Boolean
}