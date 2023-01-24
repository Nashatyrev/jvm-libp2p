package io.libp2p.simulate.stream

import io.libp2p.core.PeerId
import io.libp2p.core.StreamHandler
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.MultiaddrComponent
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.security.SecureChannel
import io.libp2p.etc.PROTOCOL
import io.libp2p.etc.types.forward
import io.libp2p.etc.types.lazyVar
import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.simulate.AbstractSimPeer
import io.libp2p.simulate.SimConnection
import io.libp2p.simulate.SimPeer
import io.libp2p.simulate.util.GeneralSizeEstimator
import io.libp2p.simulate.util.MessageDelayer
import io.libp2p.tools.DummyChannel
import io.libp2p.tools.NullTransport
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicLong

private val counter = AtomicLong()

abstract class StreamSimPeer<TProtocolController>(
    val isSemiDuplex: Boolean = false,
    val streamProtocol: String
) : AbstractSimPeer(), StreamHandler<TProtocolController> {

    val protocolController: CompletableFuture<TProtocolController> = CompletableFuture()

    var address = Multiaddr(listOf(
        MultiaddrComponent(Protocol.IP4, counter.incrementAndGet().toBytesBigEndian()),
        MultiaddrComponent(Protocol.TCP, byteArrayOf(0, 0xFF.toByte()))
    ))

    abstract val random: Random

    var simExecutor: ScheduledExecutorService by lazyVar { Executors.newSingleThreadScheduledExecutor() }
    var currentTime: () -> Long = System::currentTimeMillis
    var keyPair by lazyVar { generateKeyPair(KEY_TYPE.ECDSA,
        random = SecureRandom(ByteArray(4).also { random.nextBytes(it) })) }
    override val peerId by lazy { PeerId.fromPubKey(keyPair.second) }

    var msgSizeEstimator = GeneralSizeEstimator
    var msgDelayer: MessageDelayer = MessageDelayer{ CompletableFuture.completedFuture(null) }
    var wireLogs: LogLevel? = null

    override fun connectImpl(other: SimPeer): CompletableFuture<SimConnection> {
        other as StreamSimPeer<*>

        val simConnection = if (isSemiDuplex) {
            val connections = connectSemiDuplex(other, wireLogs)
            StreamSimConnection(this, other, connections.first, connections.second)
        } else {
            StreamSimConnection(this, other, connect(other, wireLogs))
        }
        return CompletableFuture.completedFuture(simConnection)
    }

    private fun connect(
        another: StreamSimPeer<*>,
        wireLogs: LogLevel? = null
    ): StreamSimChannel.Connection {

        val thisChannel = newChannel("$name=>${another.name}", another, wireLogs, true)
        val anotherChannel = another.newChannel("${another.name}=>$name", this, wireLogs, false)
        return StreamSimChannel.interConnect(thisChannel, anotherChannel)
    }

    private fun connectSemiDuplex(
        another: StreamSimPeer<*>,
        wireLogs: LogLevel? = null
    ): Pair<StreamSimChannel.Connection, StreamSimChannel.Connection> {
        return connect(another, wireLogs) to
            another.connect(this, wireLogs)
    }

    private fun newChannel(
        channelName: String,
        remote: StreamSimPeer<*>,
        wireLogs: LogLevel? = null,
        initiator: Boolean
    ): StreamSimChannel {

        val connection = object : ConnectionOverNetty(
            DummyChannel(),
            NullTransport(),
            initiator
        ) {
            override fun remoteAddress(): Multiaddr {
                return remote.address
            }
        }

        connection.setSecureSession(
            SecureChannel.Session(
                PeerId.fromPubKey(keyPair.second),
                PeerId.fromPubKey(remote.keyPair.second),
                remote.keyPair.second
            )
        )

        return StreamSimChannel(
            channelName,
            nettyInitializer {
                val ch = it.channel
                wireLogs?.also { ch.pipeline().addFirst(LoggingHandler(channelName, it)) }
                val stream = SimStream(connection, ch, initiator)
                ch.attr(PROTOCOL).get().complete(streamProtocol)
                handleStream(stream).forward(protocolController)
            }
        ).also {
            it.executor = simExecutor
            it.currentTime = currentTime
            it.msgSizeEstimator = msgSizeEstimator
            it.msgDelayer = msgDelayer
        }
    }
}
