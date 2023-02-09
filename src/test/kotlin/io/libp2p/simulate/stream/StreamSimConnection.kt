package io.libp2p.simulate.stream

import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolId
import io.libp2p.core.security.SecureChannel
import io.libp2p.etc.PROTOCOL
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.simulate.*
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.tools.DummyChannel
import io.libp2p.tools.NullTransport
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class StreamSimConnection(
    override val dialer: StreamSimPeer<*>,
    override val listener: StreamSimPeer<*>,
) : SimConnection {

    private val streamsMut = mutableListOf<StreamSimStream>()
    override val streams: List<StreamSimStream>
        get() = streamsMut

    override val closed = CompletableFuture<Unit>()

    override fun close() {
        CompletableFuture.allOf(
            *streams.map { it.disconnect() }.toTypedArray()
        ).thenAccept { closed.complete(Unit) }
    }

    val dialerStatsS = StatsFactory.DEFAULT.createStats()
    val listenerStatsS = StatsFactory.DEFAULT.createStats()
    override val dialerStat = ConnectionStat(dialerStatsS)
    override val listenerStat = ConnectionStat(listenerStatsS)

    override var connectionLatency = MessageDelayer.NO_DELAYER
        set(value) {
            streams.forEach { it.setLatency(value) }
            field = value
        }

    fun createStream(streamInitiator: SimStream.StreamInitiator, streamProtocol: ProtocolId, wireLogs: LogLevel? = null): StreamSimStream {
        val from =
            if (streamInitiator == SimStream.StreamInitiator.CONNECTION_DIALER) dialer
            else listener
        val to =
            if (streamInitiator == SimStream.StreamInitiator.CONNECTION_LISTENER) dialer
            else listener


        val fromSideChannel = newChannel("${from.name}=>${to.name}",
            from, to, streamProtocol, wireLogs, from === dialer, true)
        val toSideChannel = newChannel("${to.name}=>${from.name}",
            to, from, streamProtocol, wireLogs, to === dialer, false)

        val stream = StreamSimStream.interConnect(toSideChannel, fromSideChannel, streamInitiator, streamProtocol)
        stream.connection = this
        streamsMut += stream

        if (streamInitiator == SimStream.StreamInitiator.CONNECTION_DIALER) {
            stream.ch1.msgSizeHandler = { dialerStatsS.addValue(it.toDouble()) }
            stream.ch2.msgSizeHandler = { listenerStatsS.addValue(it.toDouble()) }
        } else {
            stream.ch1.msgSizeHandler = { listenerStatsS.addValue(it.toDouble()) }
            stream.ch2.msgSizeHandler = { dialerStatsS.addValue(it.toDouble()) }
        }
        return stream
    }

    private fun newChannel(
        channelName: String,
        local: StreamSimPeer<*>,
        remote: StreamSimPeer<*>,
        streamProtocol: ProtocolId,
        wireLogs: LogLevel? = null,
        connectionInitiator: Boolean,
        streamInitiator: Boolean
    ): StreamSimChannel {

        val connection =  DummyConnection(remote.address, connectionInitiator)

        connection.setSecureSession(
            SecureChannel.Session(
                PeerId.fromPubKey(local.keyPair.second),
                PeerId.fromPubKey(remote.keyPair.second),
                remote.keyPair.second
            )
        )

        return StreamSimChannel(
            channelName,
            remote.inboundBandwidth,
            local.outboundBandwidth,
            nettyInitializer {
                val ch = it.channel
                wireLogs?.also { ch.pipeline().addFirst(LoggingHandler(channelName, it)) }
                val stream = Libp2pStreamImpl(connection, ch, streamInitiator)
                ch.attr(PROTOCOL).get().complete(streamProtocol)
                local.simHandleStream(stream)
            }
        ).also {
            it.executor = local.simExecutor
            it.currentTime = local.currentTime
            it.msgSizeEstimator = local.msgSizeEstimator
        }
    }

    private class DummyConnection(val remoteAddr: Multiaddr, isInitiator: Boolean) : ConnectionOverNetty(
        DummyChannel(),
        NullTransport(),
        isInitiator
    ) {
        override fun remoteAddress() = remoteAddr
    }
}


fun StreamSimConnection.simpleLatencyDelayer(latency: Duration) =
    TimeDelayer(this.listener.simExecutor, { latency })

fun StreamSimConnection.randomLatencyDelayer(latency: RandomValue) =
    TimeDelayer(this.listener.simExecutor, { latency.next().toLong().milliseconds })