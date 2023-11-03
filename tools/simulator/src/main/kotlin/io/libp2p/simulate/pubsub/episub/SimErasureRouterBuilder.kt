package io.libp2p.simulate.pubsub.episub

import io.libp2p.core.crypto.sha256
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.AbstractPubsubMessage
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.erasure.ErasureRouter
import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.simulate.pubsub.SimAbstractRouterBuilder
import io.libp2p.simulate.pubsub.gossip.router.SimGossipRouter
import pubsub.pb.Rpc
import java.util.Random
import java.util.concurrent.ScheduledExecutorService
import kotlin.time.Duration

class SimErasureRouterBuilder : SimAbstractRouterBuilder {

    override var name: String = ""
    override lateinit var scheduledAsyncExecutor: ScheduledExecutorService
    override lateinit var currentTimeSuppluer: CurrentTimeSupplier
    override lateinit var random: Random
    override var protocol: PubsubProtocol = PubsubProtocol.ErasureSub

    override fun build(): ErasureRouter {
        TODO()
//        return ErasureRouter(
//            scheduledAsyncExecutor,
//
//        )
    }
}
