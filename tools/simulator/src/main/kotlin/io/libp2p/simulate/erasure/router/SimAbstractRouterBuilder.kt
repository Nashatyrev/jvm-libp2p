package io.libp2p.simulate.erasure.router

import io.libp2p.pubsub.AbstractRouter
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import java.util.Random
import java.util.concurrent.ScheduledExecutorService

interface SimAbstractRouterBuilder {

    var name: String
    var scheduledAsyncExecutor: ScheduledExecutorService
    var currentTimeSuppluer: CurrentTimeSupplier
    var random: Random

    var protocol: PubsubProtocol

    fun build(): AbstractRouter
}
