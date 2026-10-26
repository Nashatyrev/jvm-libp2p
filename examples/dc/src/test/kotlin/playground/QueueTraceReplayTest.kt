package playground

import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.UdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.CodelUdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.FifoUdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.FqCodelUdpSimBandwidthQueue
import io.netty.buffer.Unpooled
import io.netty.channel.socket.DatagramPacket
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * Replays `t_us,flow,size` traces through one bandwidth queue at a time, outside any network, so
 * the queue disciplines can be compared packet by packet -- with each other and with eth-slot-sim's
 * simnet link replaying the same trace (work.dir/queue-cmp/simnet-replay). Writes, per queue and
 * trace, `idx,flow,size,enq_us,deq_us` with `deq_us` the moment the queue released the packet onto
 * the wire, or -1 if it dropped it.
 *
 * ```
 * ./gradlew :examples:dc:simulation --tests "*QueueTraceReplayTest*" -Ddc.queueTraces=<dir> -Ddc.queueOut=<dir>
 * ```
 */
@Tag("simulation")
class QueueTraceReplayTest {

    private val rate = Bandwidth(25_000_000L / 8)

    private val queues: Map<String, () -> UdpSimBandwidthQueue> = mapOf(
        "fifo" to { FifoUdpSimBandwidthQueue(rate) },
        "codel" to { CodelUdpSimBandwidthQueue(rate) },
        "codel5ms" to { CodelUdpSimBandwidthQueue(rate, targetDelay = 5.milliseconds) },
        "fq_codel" to { FqCodelUdpSimBandwidthQueue(rate) },
        // simnet's quantum is its 1500-byte MTU rather than Linux's 1514
        "fq_codel_q1500" to { FqCodelUdpSimBandwidthQueue(rate, quantumBytes = 1500) },
        "fq_codel_cap" to { FqCodelUdpSimBandwidthQueue(rate, maxQueueWaitTime = 100.milliseconds) }
    )

    @Test
    fun `replay traces`() {
        val traces = File(System.getProperty("dc.queueTraces") ?: error("set -Ddc.queueTraces"))
        val out = File(System.getProperty("dc.queueOut") ?: error("set -Ddc.queueOut")).apply { mkdirs() }
        traces.listFiles { f -> f.name.endsWith(".csv") }!!.sorted().forEach { trace ->
            val rows = trace.readLines().drop(1).map { line ->
                val (t, flow, size) = line.split(",")
                Row(t.toLong().microseconds, flow, size.toInt())
            }
            queues.forEach { (name, create) ->
                val released = replay(create(), rows)
                File(out, "$name-${trace.name}").printWriter().use { w ->
                    w.println("idx,flow,size,enq_us,deq_us")
                    rows.forEachIndexed { i, r ->
                        w.println("$i,${r.flow},${r.size},${r.t.inWholeMicroseconds},${released[i]?.inWholeMicroseconds ?: -1}")
                    }
                }
                println("$name ${trace.name}: dropped ${released.count { it == null }} of ${rows.size}")
            }
        }
    }

    private data class Row(val t: Duration, val flow: String, val size: Int)

    /** Release time of every packet, null for those the queue dropped. */
    private fun replay(queue: UdpSimBandwidthQueue, rows: List<Row>): Array<Duration?> {
        val released = arrayOfNulls<Duration>(rows.size)
        var now = Duration.ZERO
        fun collect() = queue.emitPackets().forEach { p ->
            released[p.content().getInt(0)] = now
            p.release()
        }
        fun runUntil(t: Duration?) {
            while (true) {
                val next = queue.nextTaskDuration() ?: break
                if (t != null && now + next > t) break
                queue.advance(next)
                now += next
                collect()
            }
        }
        rows.forEachIndexed { i, r ->
            runUntil(r.t)
            queue.advance(r.t - now)
            now = r.t
            val buf = Unpooled.buffer(r.size).writeInt(i).writeZero(r.size - 4)
            queue.receivePackets(listOf(DatagramPacket(buf, address(r.flow), address("src"))))
            collect()
        }
        runUntil(null)
        return released
    }

    private val addresses = mutableMapOf<String, InetSocketAddress>()
    private fun address(node: String) = addresses.getOrPut(node) { InetSocketAddress.createUnresolved(node, 1) }
}
