package io.libp2p.simulate.pubsub

import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.pubsub.createPubsubApi
import io.libp2p.etc.types.lazyVar
import io.libp2p.pubsub.AbstractRouter
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.simulate.stats.collect.gossip.PubsubMessageIdGenerator
import io.libp2p.simulate.stream.StreamSimPeer
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import java.util.Random
import java.util.concurrent.CompletableFuture

open class SimPubsubPeer(
    override val simPeerId: Int,
    override val random: Random,
    protocol: PubsubProtocol,
    val routerBuilder: SimPubsubRouterBuilder
) : StreamSimPeer<Unit>(true, protocol.announceStr) {

    protected var abstractRouter: AbstractRouter by lazyVar {
        routerBuilder.also {
            it.name = name
            it.scheduledAsyncExecutor = simExecutor
            it.currentTimeSuppluer = { currentTime() }
            it.random = random
        }.build()
    }

    open val router: AbstractRouter get() = abstractRouter

    val api by lazy { createPubsubApi(router) }
    val apiPublisher by lazy { api.createPublisher(keyPair.first, 0L) }
    var pubsubLogs: (PeerId) -> Boolean = { false }

    override fun handleStream(stream: Stream): CompletableFuture<Unit> {
        stream.getProtocol()
        val logConnection = pubsubLogs(stream.remotePeerId())
        router.addPeerWithDebugHandler(
            stream,
            if (logConnection)
                LoggingHandler(name, LogLevel.ERROR) else null
        )
        return dummy
    }

    override fun toString(): String {
        return name
    }

    companion object {
        private val dummy = CompletableFuture.completedFuture(Unit)
    }
}

fun SimPubsubPeer.getMessageIdGenerator(): PubsubMessageIdGenerator = {
    this.router.messageFactory(it).messageId
}

