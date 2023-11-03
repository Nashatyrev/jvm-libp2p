package io.libp2p.simulate.pubsub.episub

import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.erasure.ErasureRouter
import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.simulate.pubsub.SimPubsubRouterBuilder
import java.util.Random
import java.util.concurrent.ScheduledExecutorService

class SimErasureRouterBuilder : SimPubsubRouterBuilder {

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
