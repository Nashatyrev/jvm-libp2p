package io.libp2p.simulate.stream

import io.libp2p.etc.types.forward
import io.libp2p.simulate.*
import io.libp2p.simulate.stats.StatsFactory
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class StreamSimConnection(
    override val dialer: StreamSimPeer<*>,
    override val listener: StreamSimPeer<*>,
    val dialerStream: StreamSimStream,
    val listenerStream: StreamSimStream? = null,
) : SimConnection {

    init {
        dialerStream.ch1.msgSizeHandler = { dialerStatsS.addValue(it.toDouble()) }
        dialerStream.ch2.msgSizeHandler = { listenerStatsS.addValue(it.toDouble()) }
        listenerStream?.ch1?.msgSizeHandler = { listenerStatsS.addValue(it.toDouble()) }
        listenerStream?.ch2?.msgSizeHandler = { dialerStatsS.addValue(it.toDouble()) }
    }

    override val streams: List<StreamSimStream>
        get() = listOfNotNull(dialerStream, listenerStream)

    override val closed = CompletableFuture<Unit>()

    override fun close() {
        CompletableFuture.allOf(
            dialerStream.disconnect(),
            listenerStream?.disconnect() ?: CompletableFuture.completedFuture(Unit)
        ).thenApply { Unit }
            .forward(closed)
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
}


fun StreamSimConnection.simpleLatencyDelayer(latency: Duration) =
    TimeDelayer(this.listener.simExecutor, { latency })

fun StreamSimConnection.randomLatencyDelayer(latency: RandomValue) =
    TimeDelayer(this.listener.simExecutor, { latency.next().toLong().milliseconds })