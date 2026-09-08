package io.libp2p.quicsim.program

import io.libp2p.core.PeerId
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.pubsub.PubsubApi
import io.libp2p.core.pubsub.createPubsubApi
import io.libp2p.pubsub.gossip.Gossip
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.pubsub.gossip.GossipScore
import io.libp2p.pubsub.gossip.GossipScoreParams
import io.libp2p.pubsub.gossip.GossipTopicScoreParams
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.quicsim.core.schedule.impl.toCurrentTimeSupplier
import io.libp2p.quicsim.core.schedule.impl.toScheduledExecutorService
import io.libp2p.quicsim.sim.SimContext
import io.libp2p.quicsim.sim.SimNodeId
import io.netty.channel.ChannelHandler
import java.util.Random

abstract class GossipNodeProgram(
    simNodeId: SimNodeId,
    connectToNodeIds: List<SimNodeId>,
    val params: GossipParams,
    val scoreParams: GossipScoreParams = GossipScoreParams(),
    val randomSeed: Long = 0,
    private val useZeroGossipScore: Boolean = false,
    private val debugGossipHandler: ChannelHandler? = null,
) : AbstractNodeProgram(simNodeId, connectToNodeIds) {

    lateinit var gossipRouter: GossipRouter
    lateinit var gossipProtocol: Gossip
    lateinit var messageApi: PubsubApi

    /** Allows specialised simulations to opt into router features before it is built. */
    protected open fun configureGossipRouterBuilder(builder: GossipRouterBuilder) {
        if (useZeroGossipScore) {
            builder.scoreFactory = { _, _, _, _ -> ZeroGossipScore }
        }
    }

    /** Override to inject a per-stream debug handler into every gossip peer channel. */
    protected open fun createDebugGossipHandler(): ChannelHandler? = debugGossipHandler

    /**
     * Current number of mesh peers, one entry per subscribed topic. Empty before the router is
     * built. `mesh` is owned by the gossip event thread, so call this from that thread.
     */
    fun meshSizes(): List<Int> =
        if (this::gossipRouter.isInitialized) gossipRouter.mesh.values.map { it.size } else emptyList()

    override fun createProtocols(context: SimContext): List<ProtocolBinding<*>> {
        gossipRouter = GossipRouterBuilder().also {
            it.params = params
            it.scoreParams = scoreParams
            it.currentTimeSupplier = context.timer.toCurrentTimeSupplier()
            it.scheduledAsyncExecutor = context.scheduler.toScheduledExecutorService()
            it.random = Random(randomSeed)
            it.name = "$simNodeId"
            configureGossipRouterBuilder(it)
        }.build()

        messageApi = createPubsubApi(gossipRouter)
        gossipProtocol = Gossip(router = gossipRouter, api = messageApi, debugGossipHandler = createDebugGossipHandler())
        return listOf(gossipProtocol)
    }

}

/**
 * Avoids allocating per-peer/per-topic score state in simulations whose score parameters are
 * deliberately all zero. It is equivalent to the default score for routing decisions in that
 * configuration.
 */
private object ZeroGossipScore : GossipScore {
    override fun updateTopicParams(topicScoreParams: Map<String, GossipTopicScoreParams>) = Unit
    override fun score(peerId: PeerId): Double = 0.0
    override fun getCachedScore(peerId: PeerId): Double = 0.0
}
