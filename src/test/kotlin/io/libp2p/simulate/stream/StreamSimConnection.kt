package io.libp2p.simulate.stream

import io.libp2p.etc.types.forward
import io.libp2p.simulate.ConnectionStat
import io.libp2p.simulate.MessageDelayer
import io.libp2p.simulate.RandomValue
import io.libp2p.simulate.SimConnection
import io.libp2p.simulate.stats.StatsFactory
import java.util.concurrent.CompletableFuture
import kotlin.properties.Delegates
import kotlin.properties.ObservableProperty

class StreamSimConnection(
    override val dialer: StreamSimPeer<*>,
    override val listener: StreamSimPeer<*>,
    val dialerConnection: StreamSimChannel.Connection,
    var listenerConnection: StreamSimChannel.Connection? = null,
) : SimConnection {

    init {
        dialerConnection.ch1.msgSizeHandler = { dialerStatsS.addValue(it.toDouble()) }
        dialerConnection.ch2.msgSizeHandler = { listenerStatsS.addValue(it.toDouble()) }
        listenerConnection?.ch1?.msgSizeHandler = { listenerStatsS.addValue(it.toDouble()) }
        listenerConnection?.ch2?.msgSizeHandler = { dialerStatsS.addValue(it.toDouble()) }
    }

    override val closed = CompletableFuture<Unit>()

    override fun close() {
        CompletableFuture.allOf(
            dialerConnection.disconnect(),
            listenerConnection?.disconnect() ?: CompletableFuture.completedFuture(Unit)
        ).thenApply { Unit }
            .forward(closed)
    }

    val dialerStatsS = StatsFactory.DEFAULT.createStats()
    val listenerStatsS = StatsFactory.DEFAULT.createStats()
    override val dialerStat = ConnectionStat(dialerStatsS)
    override val listenerStat = ConnectionStat(listenerStatsS)

    override var connectionLatency by Delegates.observable(MessageDelayer.NO_DELAYER)
            { _, _, n ->
                dialerConnection.setLatency(n)
                listenerConnection?.setLatency(n)
            }
}