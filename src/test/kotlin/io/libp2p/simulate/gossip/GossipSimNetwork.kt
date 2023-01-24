package io.libp2p.simulate.gossip

import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.simulate.Network
import io.libp2p.simulate.util.TimeDelayer
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import java.util.Random
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

class GossipSimNetwork(
    val cfg: GossipSimConfig,
    val routerFactory: (Int) -> GossipRouterBuilder,
    val simPeerModifier: (Int, GossipSimPeer) -> Unit = { a, b -> }
) {
    val peers = sortedMapOf<Int, GossipSimPeer>()
    lateinit var network: Network

    open val timeController = TimeControllerImpl()
    open val commonRnd = Random(cfg.startRandomSeed)
    protected open val peerExecutors =
        if (cfg.iterationThreadsCount > 1)
            (0 until cfg.iterationThreadsCount).map { Executors.newSingleThreadScheduledExecutor() }
        else
            listOf(Executor { it.run() })

    var simPeerFactory: (Int, GossipRouterBuilder) -> GossipSimPeer = { number, router ->
        GossipSimPeer(cfg.topic, number.toString(), commonRnd).apply {
            routerBuilder = router

            val delegateExecutor = peerExecutors[number % peerExecutors.size]
            simExecutor = ControlledExecutorServiceImpl(delegateExecutor, timeController)
            currentTime = { timeController.time }
            msgSizeEstimator =
                GossipSimPeer.rawPubSubMsgSizeEstimator(cfg.avrgMessageSize, cfg.measureTCPFramesOverhead)
            val latencyRandomValue = cfg.latency.newValue(commonRnd)
            msgDelayer = TimeDelayer(simExecutor) { latencyRandomValue.next().toLong().milliseconds }
            validationDelay = cfg.gossipValidationDelay

            start()
        }
    }

    protected open fun createSimPeer(number: Int): GossipSimPeer {
        val router = routerFactory(number).also {
            it.currentTimeSuppluer = { timeController.time }
            it.serialize = false
        }

        val simPeer = simPeerFactory(number, router)
        simPeerModifier(number, simPeer)
        return simPeer
    }

    fun createAllPeers() {
        peers += (0 until cfg.totalPeers).map {
            it to createSimPeer(it)
        }
    }

    fun connectAllPeers() {
        cfg.topology.random = commonRnd
        network = cfg.topology.connect(peers.values.toList())
    }

    fun getConnectedPeers(peerIndex: Int): Collection<GossipSimPeer> {
        val peer = peers[peerIndex] ?: throw IllegalArgumentException("Invalid peer index $peerIndex")
        return peer.getConnectedPeers().map { it as GossipSimPeer }
    }
}