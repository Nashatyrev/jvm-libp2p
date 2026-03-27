package io.libp2p.quicsim.program

import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.pubsub.PubsubApi
import io.libp2p.core.pubsub.createPubsubApi
import io.libp2p.pubsub.gossip.Gossip
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.pubsub.gossip.GossipScoreParams
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.quicsim.core.schedule.impl.toCurrentTimeSupplier
import io.libp2p.quicsim.core.schedule.impl.toScheduledExecutorService
import io.libp2p.quicsim.sim.SimContext
import io.libp2p.quicsim.sim.SimNodeId
import java.util.Random

abstract class GossipNodeProgram(
    simNodeId: SimNodeId,
    connectToNodeIds: List<SimNodeId>,
    val params: GossipParams,
    val scoreParams: GossipScoreParams = GossipScoreParams(),
    val randomSeed: Long = 0,
) : AbstractNodeProgram(simNodeId, connectToNodeIds) {

    lateinit var gossipRouter: GossipRouter
    lateinit var gossipProtocol: Gossip
    lateinit var messageApi: PubsubApi

    override fun createProtocols(context: SimContext): List<ProtocolBinding<*>> {
        gossipRouter = GossipRouterBuilder().also {
            it.params = params
            it.scoreParams = scoreParams
            it.currentTimeSuppluer = context.timer.toCurrentTimeSupplier()
            it.scheduledAsyncExecutor = context.scheduler.toScheduledExecutorService()
            it.random = Random(randomSeed)
            it.name = "$simNodeId"
        }.build()

        messageApi = createPubsubApi(gossipRouter)
        gossipProtocol = Gossip(router = gossipRouter, api = messageApi)
        return listOf(gossipProtocol)
    }

}
