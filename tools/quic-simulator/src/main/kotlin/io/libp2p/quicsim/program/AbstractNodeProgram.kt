package io.libp2p.quicsim.program

import io.libp2p.core.Connection
import io.libp2p.quicsim.sim.NetworkContext
import io.libp2p.quicsim.sim.SimContext
import io.libp2p.quicsim.sim.SimNodeId
import java.util.concurrent.CompletableFuture

abstract class AbstractNodeProgram(
    override val simNodeId: SimNodeId,
    val connectToNodeIds: List<SimNodeId>,
) : NodeProgram {

    override fun start(simContext: SimContext, networkContext: NetworkContext): CompletableFuture<Unit> {
        val connectAll = connectAll(networkContext)
        return CompletableFuture.allOf(*connectAll.toTypedArray())
            .thenApply { onAllConnected(simContext, networkContext) }
    }


    protected fun connectAll(context: NetworkContext): List<CompletableFuture<Connection>> =
        connectToNodeIds.map { nodeId ->
            val nodeAddr = context.allNodes[nodeId] ?: throw IllegalStateException("Node $nodeId not found")
            context.myHost.network.connect(nodeAddr)
        }

    protected abstract fun onAllConnected(simContext: SimContext, networkContext: NetworkContext)
}
