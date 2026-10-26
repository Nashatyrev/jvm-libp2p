package io.libp2p.example.dc

import io.libp2p.quicsim.runner.DatagramPacketTraceEvent
import io.libp2p.quicsim.runner.DatagramPacketTraceRecorder
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.time.Duration

/**
 * Counts each node's sent datagrams by size, within [from, until): what the byte totals of
 * [io.libp2p.quicsim.runner.DatagramTrafficAggregate] cannot say, e.g. how many packets are
 * full-sized and how many are ACK-only. Sizes at or above [maxSize] share the last slot.
 */
class DcDatagramSizes(
    private val nodeCount: Int,
    private val from: Duration,
    private val until: Duration,
    private val maxSize: Int = 2048
) : DatagramPacketTraceRecorder {
    private val counts = AtomicLongArray(nodeCount * maxSize)

    override val needsPayloadDigest: Boolean get() = false
    override val needsEndpoints: Boolean get() = false

    override fun record(event: DatagramPacketTraceEvent) {
        if (event.direction != DatagramPacketTraceEvent.Direction.OUTBOUND) return
        if (event.at < from || event.at >= until) return
        counts.incrementAndGet(event.nodeId * maxSize + event.bytes.coerceAtMost(maxSize - 1))
    }

    /** Node's sent-datagram count by size, sizes never seen left out. */
    fun sentSizes(nodeId: Int): Map<Int, Long> =
        (0 until maxSize).mapNotNull { size ->
            counts.get(nodeId * maxSize + size).takeIf { it > 0 }?.let { size to it }
        }.toMap()
}

/** Hands every datagram to each of [recorders]; needs what any of them needs. */
internal class DcCompositeDatagramRecorder(
    private val recorders: List<DatagramPacketTraceRecorder>
) : DatagramPacketTraceRecorder {
    override val needsPayloadDigest: Boolean get() = recorders.any { it.needsPayloadDigest }
    override val needsEndpoints: Boolean get() = recorders.any { it.needsEndpoints }

    override fun record(event: DatagramPacketTraceEvent) {
        recorders.forEach { it.record(event) }
    }
}
