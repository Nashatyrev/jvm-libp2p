package io.libp2p.pubsub.gossip

import io.libp2p.core.Host
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.dsl.HostBuilder
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.pubsub.Subscriber
import io.libp2p.core.pubsub.Topic
import io.libp2p.core.transport.Transport
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.util.P2PServiceSemiDuplex
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.transport.quic.QuicTransport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.BiFunction

/**
 * Two hosts over QUIC with [GossipStreamLanes]: [URGENT] on lane 0 at the top priority, [BULK] on a
 * lane of its own below it. A backlog of bulk messages is queued first and one urgent message last.
 */
class GossipStreamLanesQuicTest {

    private val lanes = GossipStreamLanes(
        priorities = listOf(StreamPriority(0), StreamPriority(2)),
        laneOfTopic = { if (it == BULK.topic) 1 else 0 }
    )
    private val params = GossipParams(maxGossipMessageSize = 2 * BULK_SIZE)
    private val hosts = mutableListOf<Host>()

    @AfterEach
    fun stopHosts() {
        hosts.forEach { it.stop().get(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `lane streams are opened in both directions`() {
        val (router1, router2) = connectedRouters(lanes)

        listOf(router1, router2).forEach { router ->
            waitFor {
                router.submitOnEventThread {
                    val peer = router.peers.singleOrNull() as P2PServiceSemiDuplex.SDPeerHandler?
                    peer != null && peer.outboundLanes.keys == setOf(1) && peer.inboundLanes.keys == setOf(1)
                }.get()
            }
        }
    }

    @Test
    fun `urgent message overtakes the bulk backlog on its own lane`() {
        val arrivals = publishBacklogThenUrgent(lanes)

        assertThat(arrivals).hasSize(BULK_COUNT + 1)
        assertThat(arrivals.indexOf(URGENT.topic))
            .describedAs("urgent arrival position among $arrivals")
            .isLessThan(BULK_COUNT / 2)
    }

    @Test
    fun `without lanes the urgent message waits behind the backlog`() {
        val arrivals = publishBacklogThenUrgent(null)

        assertThat(arrivals).hasSize(BULK_COUNT + 1)
        assertThat(arrivals.last()).isEqualTo(URGENT.topic)
    }

    private fun publishBacklogThenUrgent(lanes: GossipStreamLanes?): List<String> {
        val (gossip1, gossip2) = connectedGossips(lanes)
        val router1 = gossip1.second
        val arrivals = Collections.synchronizedList(mutableListOf<String>())
        gossip2.first.subscribe(Subscriber { arrivals += it.topics.single().topic }, BULK, URGENT)
        waitFor { router1.getPeerTopics().join().values.flatten().containsAll(listOf(BULK.topic, URGENT.topic)) }
        if (lanes != null) waitFor { router1.hasOutboundLane(1) }

        val publisher = gossip1.first.createPublisher(null)
        val sent = (0 until BULK_COUNT).map {
            publisher.publish(ByteArray(BULK_SIZE) { b -> (it + b).toByte() }.toByteBuf(), BULK)
        } + publisher.publish(byteArrayOf(1).toByteBuf(), URGENT)
        CompletableFuture.allOf(*sent.toTypedArray()).get(30, TimeUnit.SECONDS)

        waitFor { arrivals.size == BULK_COUNT + 1 }
        println("Lanes: ${lanes != null}, urgent message arrived after ${arrivals.indexOf(URGENT.topic)} of $BULK_COUNT bulk messages")
        return arrivals.toList()
    }

    private fun GossipRouter.hasOutboundLane(lane: Int) =
        submitOnEventThread {
            (peers.singleOrNull() as P2PServiceSemiDuplex.SDPeerHandler?)?.getOutboundHandler(lane)?.lane == lane
        }.get()

    private fun connectedRouters(lanes: GossipStreamLanes?): Pair<GossipRouter, GossipRouter> =
        connectedGossips(lanes).let { (g1, g2) -> g1.second to g2.second }

    private fun connectedGossips(lanes: GossipStreamLanes?): Pair<Pair<Gossip, GossipRouter>, Pair<Gossip, GossipRouter>> {
        val gossip1 = createGossip(lanes)
        val gossip2 = createGossip(lanes)
        val address = "/ip4/127.0.0.1/udp/${Random().nextInt(20_000) + 10_000}/quic-v1"
        val host1 = createHost(gossip1.first, null)
        val host2 = createHost(gossip2.first, address)
        hosts += listOf(host1, host2)
        CompletableFuture.allOf(host1.start(), host2.start()).get(5, TimeUnit.SECONDS)

        host1.network.connect(host2.peerId, Multiaddr(address)).get(10, TimeUnit.SECONDS)
        listOf(gossip1, gossip2).forEach { (_, router) ->
            waitFor { router.submitOnEventThread { router.activePeers.isNotEmpty() }.get() }
        }
        return gossip1 to gossip2
    }

    private fun createGossip(lanes: GossipStreamLanes?): Pair<Gossip, GossipRouter> {
        val router = GossipRouterBuilder(params = params, streamLanes = lanes).build()
        return Gossip(router) to router
    }

    private fun createHost(gossip: Gossip, listenAddress: String?): Host {
        val quic = BiFunction<PrivKey, List<ProtocolBinding<*>>, Transport> { key, protocols -> QuicTransport.ECDSA(key, protocols) }
        return HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(quic)
            .protocol(gossip)
            .apply { listenAddress?.let { listen(it) } }
            .build()
    }

    private fun waitFor(predicate: () -> Boolean) {
        repeat(200) {
            if (predicate()) return
            Thread.sleep(50)
        }
        throw TimeoutException("Timeout waiting for condition")
    }

    companion object {
        private val URGENT = Topic("urgent")
        private val BULK = Topic("bulk")
        private const val BULK_COUNT = 40
        private const val BULK_SIZE = 512 * 1024
    }
}
