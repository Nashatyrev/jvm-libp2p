package io.libp2p.simulate.gossip

import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.PubsubSubscription
import io.libp2p.core.pubsub.RESULT_VALID
import io.libp2p.core.pubsub.Topic
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.core.pubsub.Validator
import io.libp2p.core.pubsub.createPubsubApi
import io.libp2p.etc.types.lazyVar
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.simulate.stream.StreamSimPeer
import io.libp2p.simulate.util.MsgSizeEstimator
import io.libp2p.tools.millis
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import pubsub.pb.Rpc
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GossipSimPeer(
    val topic: Topic,
    override val name: String,
    override val random: Random,
    protocol: PubsubProtocol = PubsubProtocol.Gossip_V_1_1
) : StreamSimPeer<Unit>(true, protocol.announceStr) {

    var routerBuilder= GossipRouterBuilder()
    var router by lazyVar {
        routerBuilder.also {
            it.name = name
            it.scheduledAsyncExecutor = simExecutor
            it.currentTimeSuppluer = { currentTime() }
            it.random = random
        }.build()
    }

    val api by lazy { createPubsubApi(router) }
    val apiPublisher by lazy { api.createPublisher(keyPair.first, 0L) }
    var pubsubLogs: (PeerId) -> Boolean = { false }

    var validationDelay = 0.millis
    var validationResult = RESULT_VALID
    var subscription: PubsubSubscription? = null

    val allMessages = mutableListOf<Pair<MessageApi, Long>>()

    val lastMsg: MessageApi?
        get() = allMessages.lastOrNull()?.first
    val lastMsgTime
        get() = allMessages.last().second

    fun onNewMsg(msg: MessageApi) {
        allMessages += msg to router.currentTimeSupplier()
    }

    override fun start(): CompletableFuture<Unit> {
        subscription = api.subscribe(Validator {
            onNewMsg(it)
            if (validationDelay.toMillis() == 0L) {
                validationResult
            } else {
                val ret = CompletableFuture<ValidationResult>()
                simExecutor.schedule({ ret.complete(validationResult.get()) }, validationDelay.toMillis(), TimeUnit.MILLISECONDS)
                ret
            }
        }, topic)

        return super.start()
    }

    override fun toString(): String {
        return name
    }

    override fun handleStream(stream: Stream): CompletableFuture<Unit> {
        stream.getProtocol()
        val logConnection = pubsubLogs(stream.remotePeerId())
        router.addPeerWithDebugHandler(stream, if (logConnection)
            LoggingHandler(name, LogLevel.ERROR) else null)
        return dummy
    }

    companion object {
        private val dummy = CompletableFuture.completedFuture(Unit)

        fun averagePubSubMsgSizeEstimator(avrgMsgLen: Int, measureTcpOverhead: Boolean = true): MsgSizeEstimator = { msg: Any ->
            val payloadSize = (msg as Rpc.RPC).run {
                subscriptionsList.sumBy { it.topicid.length + 2 } +
                        control.graftList.sumBy { it.topicID.length + 1 } +
                        control.pruneList.sumBy { it.topicID.length + 1 } +
                        control.ihaveList.flatMap { it.messageIDsList }.sumBy { it.size() + 1 } +
                        control.iwantList.flatMap { it.messageIDsList }.sumBy { it.size() + 1 } +
                        publishList.sumBy { avrgMsgLen + it.topicIDsList.sumBy { it.length } + 224 } +
                        6
            }
            (payloadSize + if (measureTcpOverhead) ((payloadSize / 1460) + 1) * 40 else 0).toLong()
        }

        fun strictPubSubMsgSizeEstimator(measureTcpOverhead: Boolean = true): MsgSizeEstimator = { msg: Any ->
            val payloadSize = (msg as Rpc.RPC).run {
                subscriptionsList.sumBy { it.topicid.length + 2 } +
                        control.graftList.sumBy { it.topicID.length + 1 } +
                        control.pruneList.sumBy { it.topicID.length + 1 } +
                        control.ihaveList.flatMap { it.messageIDsList }.sumBy { it.size() + 1 } +
                        control.iwantList.flatMap { it.messageIDsList }.sumBy { it.size() + 1 } +
                        publishList.sumBy { it.data.size() + it.topicIDsList.sumBy { it.length } + 224 } +
                        6
            }
            (payloadSize + if (measureTcpOverhead) ((payloadSize / 1460) + 1) * 40 else 0).toLong()
        }
    }
}
