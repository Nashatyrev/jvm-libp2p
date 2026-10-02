package io.libp2p.example.dc

import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.quicsim.sim.SimNodeId
import io.libp2p.transport.implementation.P2PChannelOverNetty
import io.netty.channel.Channel
import io.netty.incubator.codec.quic.QuicChannel
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration

/** One QUIC connection's path state at one moment, as quiche reports it. */
data class DcQuicPathSample(
    val at: Duration,
    val nodeId: SimNodeId,
    val cwnd: Long,
    val rttMicros: Long,
    val lost: Long,
    val retrans: Long,
    val sentBytes: Long
)

/**
 * Diagnostic: snapshots every gossip connection's congestion window and RTT at chosen moments, to
 * tell a link that is short of bandwidth from a connection that is short of window.
 */
class DcQuicStats {
    private val samples = ConcurrentLinkedQueue<DcQuicPathSample>()

    /** Call on the node's event thread: [GossipRouter.peers] belongs to it. */
    fun sample(at: Duration, nodeId: SimNodeId, router: GossipRouter) {
        router.peers.forEach { peer ->
            val quic = quicChannelOf((peer.streamHandler.stream as? P2PChannelOverNetty)?.nettyChannel) ?: return@forEach
            quic.collectPathStats(0).addListener { future ->
                if (!future.isSuccess) return@addListener
                val stats = future.now as io.netty.incubator.codec.quic.QuicConnectionPathStats
                samples += DcQuicPathSample(
                    at,
                    nodeId,
                    stats.cwnd(),
                    stats.rtt() / 1000,
                    stats.lost(),
                    stats.retrans(),
                    stats.sentBytes()
                )
            }
        }
    }

    private fun quicChannelOf(channel: Channel?): QuicChannel? {
        var current = channel
        while (current != null && current !is QuicChannel) current = current.parent()
        return current as QuicChannel?
    }

    /** Per group and moment: cwnd and RTT percentiles, and loss so far, over every connection. */
    fun render(groupOf: (SimNodeId) -> String): String = buildString {
        appendLine("quic path stats (per connection, both ends counted):")
        samples.groupBy { it.at to groupOf(it.nodeId) }
            .toSortedMap(compareBy({ it.first }, { it.second }))
            .forEach { (key, list) ->
                val cwnd = list.map { it.cwnd }.sorted()
                val rtt = list.map { it.rttMicros }.sorted()
                fun List<Long>.pct(p: Double) = this[((size - 1) * p).toInt()]
                appendLine(
                    "  t=${key.first} ${key.second}: conns=${list.size} " +
                        "cwnd p10=${cwnd.pct(0.1)} p50=${cwnd.pct(0.5)} p90=${cwnd.pct(0.9)} " +
                        "rtt p50=${rtt.pct(0.5) / 1000}ms p90=${rtt.pct(0.9) / 1000}ms " +
                        "lost=${list.sumOf { it.lost }} retrans=${list.sumOf { it.retrans }} " +
                        "sentMB=${list.sumOf { it.sentBytes } / 1_000_000}"
                )
            }
    }
}
