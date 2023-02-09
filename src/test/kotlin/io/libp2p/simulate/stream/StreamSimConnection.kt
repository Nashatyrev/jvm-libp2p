package io.libp2p.simulate.stream

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PubKey
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


        val fromIsInitiator = from === dialer
        val toIsInitiator = !fromIsInitiator
        val fromInitiatorSign = if (fromIsInitiator) "*" else ""
        val toInitiatorSign = if (toIsInitiator) "*" else ""
        val fromChannelName = "$fromInitiatorSign${from.name}=>$toInitiatorSign${to.name}"
        val toChannelName = "$toInitiatorSign${to.name}=>$fromInitiatorSign${from.name}"

        val fromSideChannel =
            newChannel(fromChannelName, from, to, streamProtocol, wireLogs, fromIsInitiator, true)
        val toSideChannel =
            newChannel(toChannelName, to, from, streamProtocol, wireLogs, toIsInitiator, false)

        val stream =
            StreamSimStream.interConnect(toSideChannel, fromSideChannel, streamInitiator, streamProtocol)
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
    ): StreamNettyChannel {

        return StreamNettyChannel(
            channelName,
            remote.inboundBandwidth,
            local.outboundBandwidth,
            nettyInitializer {
                val channel = it.channel
                wireLogs?.also { channel.pipeline().addFirst(LoggingHandler(channelName, it)) }
                val connection =
                    DummyConnection(remote.address, connectionInitiator, local.keyPair.second, remote.keyPair.second)
                val stream = Libp2pStreamImpl(connection, channel, streamInitiator)
                channel.attr(PROTOCOL).get().complete(streamProtocol)
                local.simHandleStream(stream)
            }
        ).also {
            it.executor = local.simExecutor
            it.currentTime = local.currentTime
            it.msgSizeEstimator = local.msgSizeEstimator
        }
    }

    private class DummyConnection(
        val remoteAddr:
        Multiaddr,
        isInitiator: Boolean,
        localPubkey: PubKey,
        remotePubkey: PubKey,
    ) : ConnectionOverNetty(
        DummyChannel(),
        NullTransport(),
        isInitiator
    ) {
        init {
            setSecureSession(
                SecureChannel.Session(
                    PeerId.fromPubKey(localPubkey),
                    PeerId.fromPubKey(remotePubkey),
                    remotePubkey
                )
            )
        }

        override fun remoteAddress() = remoteAddr
    }
}


fun StreamSimConnection.simpleLatencyDelayer(latency: Duration) =
    TimeDelayer(this.listener.simExecutor, { latency })

fun StreamSimConnection.randomLatencyDelayer(latency: RandomValue) =
    TimeDelayer(this.listener.simExecutor, { latency.next().toLong().milliseconds })