package io.libp2p.simulate.stream

import io.libp2p.core.multistream.ProtocolId
import io.libp2p.simulate.*
import io.libp2p.simulate.stats.StatsFactory
import io.netty.handler.logging.LogLevel
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

    override var connectionLatency = MessageDelayer.NO_DELAYER
        set(value) {
            streams.forEach { it.setLatency(value) }
            field = value
        }

    fun createStream(streamInitiator: SimStream.StreamInitiator, streamProtocol: ProtocolId, wireLogs: LogLevel? = null): StreamSimStream {
        val stream = StreamSimStream(this, streamInitiator, streamProtocol, wireLogs)
        streamsMut += stream

        return stream
    }
}


fun StreamSimConnection.simpleLatencyDelayer(latency: Duration) =
    TimeDelayer(this.listener.simExecutor, { latency })

fun StreamSimConnection.randomLatencyDelayer(latency: RandomValue) =
    TimeDelayer(this.listener.simExecutor, { latency.next().toLong().milliseconds })