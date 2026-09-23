package io.libp2p.compression

import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.Subscriber
import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.pubsub.gossip.Gossip
import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.tools.Echo
import io.libp2p.tools.HostFactory
import io.libp2p.transport.implementation.StreamOverNetty
import io.libp2p.transport.tcp.TcpTransport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random

class SnappyStreamCompressionTest {

    private val hostFactory = HostFactory().also { it.muxLogLevel = null }

    @AfterEach
    fun cleanup() = hostFactory.shutdown()

    private fun Stream.codec(): SnappyStreamCodec? =
        (this as StreamOverNetty).nettyChannel.pipeline().get(SnappyStreamCodec::class.java)

    private fun createHost(compress: Boolean) = hostFactory.also {
        it.hostBuilderModifier = { if (compress) streamCompression = ProtocolMatcher.prefix("/test/echo") }
    }.createHost()

    private fun echoOver(initiatorCompress: Boolean, responderCompress: Boolean): Pair<Stream, Stream> {
        val initiator = createHost(initiatorCompress)
        val responder = createHost(responderCompress)
        val responderStreams = CopyOnWriteArrayList<Stream>()
        responder.host.addStreamVisitor { responderStreams += it }

        val echo = Echo().dial(initiator.host, responder.peerId, responder.listenAddress)
        val ctrl = echo.controller.get(5, TimeUnit.SECONDS)
        repeat(3) {
            assertThat(ctrl.echo("hello snappy stream $it").get(5, TimeUnit.SECONDS)).isEqualTo("hello snappy stream $it")
        }
        val stream = echo.stream.get()
        val remote = responderStreams.single { it.getProtocol().getNow(null) == "/test/echo" }
        return stream to remote
    }

    @Test
    fun `compressed when both sides support it`() {
        val (local, remote) = echoOver(initiatorCompress = true, responderCompress = true)

        // the protocol sees the plain id on both sides
        assertThat(local.getProtocol().get()).isEqualTo("/test/echo")
        assertThat(remote.getProtocol().get()).isEqualTo("/test/echo")
        assertThat(local.codec()).isNotNull
        assertThat(remote.codec()).isNotNull
        assertThat(local.codec()!!.plainBytesOut).isGreaterThan(0)
        assertThat(remote.codec()!!.plainBytesIn).isEqualTo(local.codec()!!.plainBytesOut)
    }

    @Test
    fun `falls back to the plain protocol when the responder doesn't support it`() {
        val (local, remote) = echoOver(initiatorCompress = true, responderCompress = false)

        assertThat(local.getProtocol().get()).isEqualTo("/test/echo")
        assertThat(local.codec()).isNull()
        assertThat(remote.codec()).isNull()
    }

    @Test
    fun `plain when only the responder supports it`() {
        val (local, remote) = echoOver(initiatorCompress = false, responderCompress = true)

        assertThat(local.codec()).isNull()
        assertThat(remote.codec()).isNull()
    }

    private fun gossipHost(port: Int, gossip: Gossip) = host {
        identity { random() }
        transports { add(::TcpTransport) }
        network { listen("/ip4/127.0.0.1/tcp/$port") }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { +StreamMuxerProtocol.Mplex }
        protocols { +gossip }
        streamCompression = ProtocolMatcher.prefix("/meshsub/")
    }

    private fun waitFor(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            Thread.sleep(100)
        }
        throw TimeoutException("Timeout waiting for condition")
    }

    @Test
    fun `gossip runs over compressed streams`() {
        val router1 = GossipRouterBuilder().build()
        val router2 = GossipRouterBuilder().build()
        val gossip1 = Gossip(router1)
        val gossip2 = Gossip(router2)
        val port = 20000 + Random.nextInt(20000)
        val host1 = gossipHost(port, gossip1)
        val host2 = gossipHost(port + 1, gossip2)
        val streams = CopyOnWriteArrayList<Stream>()
        host1.addStreamVisitor { streams += it }
        host2.addStreamVisitor { streams += it }
        host1.start().get(5, TimeUnit.SECONDS)
        host2.start().get(5, TimeUnit.SECONDS)
        try {
            host1.network.connect(host2.peerId, Multiaddr.fromString("/ip4/127.0.0.1/tcp/${port + 1}/p2p/${host2.peerId}"))
                .get(10, TimeUnit.SECONDS)
            fun connected(r: GossipRouter) =
                r.peers.isNotEmpty() && r.peers[0].getInboundHandler() != null && r.peers[0].getOutboundHandler() != null
            waitFor { connected(router1) && connected(router2) }

            val topic = Topic("/eth2/00000000/beacon_attestation_7/ssz_snappy")
            val received = CopyOnWriteArrayList<MessageApi>()
            gossip2.subscribe(Subscriber { received += it }, topic)
            waitFor { router1.getPeerTopics().join().values.flatten().contains(topic.topic) }

            // attestation-like payloads: a shared 128-byte "AttestationData" and a random signature
            val rnd = Random(3)
            val data = rnd.nextBytes(128)
            val payloads = List(200) { i -> byteArrayOf(i.toByte(), (i shr 8).toByte()) + data + rnd.nextBytes(96) }
            val publisher = gossip1.createPublisher(null)
            payloads.forEach { publisher.publish(it.toByteBuf(), topic).get(5, TimeUnit.SECONDS) }
            waitFor { received.size == payloads.size }

            assertThat(received.map { it.data.toByteArray().toList() }).containsExactlyInAnyOrderElementsOf(payloads.map { it.toList() })
            val gossipStreams = streams.filter { it.getProtocol().getNow(null)?.startsWith("/meshsub/") == true }
            assertThat(gossipStreams).isNotEmpty.allMatch { it.codec() != null }
            val sender = gossipStreams.mapNotNull { it.codec() }.maxByOrNull { it.plainBytesOut }!!
            println("gossip plain ${sender.plainBytesOut} B -> wire ${sender.wireBytesOut} B")
            assertThat(sender.wireBytesOut).isLessThan(sender.plainBytesOut * 3 / 4)
        } finally {
            host1.stop().get(5, TimeUnit.SECONDS)
            host2.stop().get(5, TimeUnit.SECONDS)
        }
    }
}
