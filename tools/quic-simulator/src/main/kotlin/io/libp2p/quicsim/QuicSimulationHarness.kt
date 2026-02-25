package io.libp2p.quicsim

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.dsl.HostBuilder
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.transport.Transport
import io.libp2p.protocol.PingBinding
import io.libp2p.protocol.PingProtocol
import io.libp2p.transport.quic.QuicTransport
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction

class QuicSimulationHarness {
    val time = SimulatedTime()
    val network = SimulatedDatagramNetwork()

    fun createNode(
        listenAddress: Multiaddr? = null,
        bandwidth: SimulatedDatagramNetwork.NodeBandwidth = SimulatedDatagramNetwork.NodeBandwidth.UNLIMITED,
        protocols: List<ProtocolBinding<*>> = listOf(PingBinding(PingProtocol().also { it.curTime = time::nowMillis })),
        onIncomingConnection: ((Connection) -> Unit)? = null
    ): QuicSimNode {
        val boundAddresses = linkedSetOf<InetSocketAddress>()
        var currentBandwidth = bandwidth

        val transportFactory = BiFunction<PrivKey, List<ProtocolBinding<*>>, Transport> { key, configuredProtocols ->
            QuicTransport(
                key,
                "ECDSA",
                configuredProtocols,
                { bootstrap, handler ->
                    network.bindClientParent(currentBandwidth, bootstrap, handler).thenApply { channel ->
                        (channel.localAddress() as? InetSocketAddress)?.let { boundAddresses += it }
                        channel
                    }
                },
                { bootstrap, bindAddress, handler ->
                    network.bindServerParent(currentBandwidth, bootstrap, bindAddress, handler).thenApply { channel ->
                        (channel.localAddress() as? InetSocketAddress)?.let { boundAddresses += it }
                        channel
                    }
                }
            )
        }

        val builder = HostBuilder(HostBuilder.DefaultMode.None)
            .keyType(KeyType.ED25519)
            .secureTransport(transportFactory)

        protocols.forEach { builder.protocol(it) }
        listenAddress?.let { builder.listen(it.toString()) }
        onIncomingConnection?.let { callback ->
            builder.builderModifier { hostBuilder ->
                hostBuilder.connectionHandlers.add(ConnectionHandler { callback(it) })
            }
        }

        return QuicSimNode(
            host = builder.build(),
            harness = this,
            updateBandwidth = { updated ->
                currentBandwidth = updated
                boundAddresses.forEach { network.updateBandwidth(it, updated) }
            }
        )
    }

    fun runUntil(done: () -> Boolean, timeout: Duration = Duration.ofSeconds(5)) {
        network.runUntil(done, timeout)
    }

    fun runSteps(steps: Int) {
        network.runSteps(steps)
    }

    fun advanceTimeBy(duration: Duration, postAdvanceSteps: Int = 200) {
        require(!duration.isNegative) { "Duration must be non-negative" }
        time.advanceBy(duration)
        network.advanceTimeBy(duration.toMillis(), TimeUnit.MILLISECONDS)
        if (postAdvanceSteps > 0) {
            network.runSteps(postAdvanceSteps)
        }
    }
}

class QuicSimNode internal constructor(
    val host: Host,
    private val harness: QuicSimulationHarness,
    private val updateBandwidth: (SimulatedDatagramNetwork.NodeBandwidth) -> Unit
) {
    fun start(timeout: Duration = Duration.ofSeconds(5)) {
        val started = host.start()
        harness.runUntil({ started.isDone }, timeout)
        started.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    fun stop(timeout: Duration = Duration.ofSeconds(5)) {
        val stopped = host.stop()
        stopped.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    fun connect(remote: QuicSimNode, remoteAddress: Multiaddr, timeout: Duration = Duration.ofSeconds(5)): Connection {
        val connectFuture = host.network.connect(remote.host.peerId, remoteAddress)
        harness.runUntil({ connectFuture.isDone }, timeout)
        return connectFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    fun setBandwidth(bandwidth: SimulatedDatagramNetwork.NodeBandwidth) {
        updateBandwidth(bandwidth)
    }

    companion object {
        fun randomKey(): PrivKey = generateKeyPair(KeyType.ED25519).first
    }
}
