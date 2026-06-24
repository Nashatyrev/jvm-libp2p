package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.UdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import java.util.ArrayDeque
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

class CodelUdpSimBandwidthQueue(
    override val bandwidth: Bandwidth,
    private val targetDelay: Duration = 10.milliseconds,
    private val interval: Duration = 100.milliseconds,
    private val limitPackets: Int = Int.MAX_VALUE,
    private val mtuBytes: Int = 1_500
) : UdpSimBandwidthQueue {
    override val maxQueueWaitTime: Duration = Duration.INFINITE

    init {
        require(!targetDelay.isNegative()) { "targetDelay must not be negative" }
        require(interval.isPositive()) { "interval must be positive" }
        require(limitPackets > 0) { "limitPackets must be positive" }
        require(mtuBytes > 0) { "mtuBytes must be positive" }
    }

    private data class PacketEntry(
        val packet: UdpSimPacket,
        val enqueuedAt: Duration
    )

    private data class ScheduledPacket(
        val packet: UdpSimPacket,
        val deliverAt: Duration
    )

    private data class CodelPopItem(
        val packet: UdpSimPacket,
        val okToDrop: Boolean
    )

    private enum class Mode {
        STORE,
        DROP
    }

    private val queue = ArrayDeque<PacketEntry>()
    private val scheduledPackets = ArrayDeque<ScheduledPacket>()
    private var totalBytesStored = 0
    private var currentTime: Duration = Duration.ZERO
    private var nextAvailableAt: Duration? = null
    private var mode = Mode.STORE
    private var intervalEnd: Duration? = null
    private var dropNext: Duration? = null
    private var currentDropCount = 0
    private var previousDropCount = 0

    override fun deliver(inboundData: List<UdpSimPacket>): List<UdpSimPacket> {
        inboundData.forEach { packet ->
            if (queue.size < limitPackets) {
                queue += PacketEntry(packet, currentTime)
                totalBytesStored += packet.bytes
            }
        }

        val ready = mutableListOf<UdpSimPacket>()
        do {
            scheduleNextIfNeeded()
            val drained = drainReady()
            ready += drained
        } while (drained.isNotEmpty())
        return ready
    }

    override fun advance(advanceDuration: Duration) {
        currentTime += advanceDuration
    }

    override fun executePending() {
    }

    override fun nextTaskDuration(): Duration? {
        scheduleNextIfNeeded()
        return scheduledPackets.peekFirst()?.let { it.deliverAt - currentTime }
    }

    private fun scheduleNextIfNeeded() {
        if (scheduledPackets.isNotEmpty()) {
            return
        }
        val deliverAt = maxOf(nextAvailableAt ?: currentTime, currentTime)
        val packet = pop(deliverAt) ?: return
        scheduledPackets += ScheduledPacket(packet, deliverAt)
        nextAvailableAt = deliverAt + bandwidth.durationToTransfer(packet.bytes)
    }

    private fun pop(now: Duration): UdpSimPacket? {
        val item = codelPop(now)
        val packet = when {
            item == null -> {
                mode = Mode.STORE
                null
            }
            item.okToDrop && mode == Mode.STORE -> dropFromStoreMode(now)
            item.okToDrop && mode == Mode.DROP -> dropFromDropMode(now, item.packet)
            else -> {
                mode = Mode.STORE
                item.packet
            }
        }
        return packet
    }

    private fun dropFromStoreMode(now: Duration): UdpSimPacket? {
        dropPacket()
        val nextItem = codelPop(now)
        mode = Mode.DROP

        val delta = currentDropCount - previousDropCount
        currentDropCount = if (wasDroppingRecently(now) && delta > 1) {
            delta
        } else {
            1
        }
        dropNext = applyControlLaw(now, currentDropCount)
        previousDropCount = currentDropCount

        return nextItem?.packet
    }

    private fun dropFromDropMode(now: Duration, packet: UdpSimPacket): UdpSimPacket? {
        var item: CodelPopItem? = CodelPopItem(packet, okToDrop = true)
        while (item != null && mode == Mode.DROP && shouldDrop(now)) {
            dropPacket()
            currentDropCount++

            item = codelPop(now)
            if (item?.okToDrop == true) {
                dropNext = applyControlLaw(dropNext ?: now, currentDropCount)
            } else {
                mode = Mode.STORE
            }
        }
        return item?.packet
    }

    private fun codelPop(now: Duration): CodelPopItem? {
        val entry = queue.pollFirst()
        if (entry == null) {
            intervalEnd = null
            return null
        }

        totalBytesStored = (totalBytesStored - entry.packet.bytes).coerceAtLeast(0)
        val standingDelay = now - entry.enqueuedAt
        return CodelPopItem(
            packet = entry.packet,
            okToDrop = processStandingDelay(now, standingDelay)
        )
    }

    private fun processStandingDelay(now: Duration, standingDelay: Duration): Boolean {
        if (standingDelay < targetDelay || totalBytesStored <= mtuBytes) {
            intervalEnd = null
            return false
        }

        val end = intervalEnd
        return if (end == null) {
            intervalEnd = now + interval
            false
        } else {
            now >= end
        }
    }

    private fun shouldDrop(now: Duration): Boolean =
        dropNext?.let { now >= it } ?: false

    private fun wasDroppingRecently(now: Duration): Boolean =
        dropNext?.let { now - it < interval * 16 } ?: false

    private fun applyControlLaw(time: Duration, count: Int): Duration {
        val divisor = sqrt(count.coerceAtLeast(1).toDouble())
        val incrementNanos = (interval.inWholeNanoseconds / divisor).toLong()
        return time + incrementNanos.nanoseconds
    }

    private fun dropPacket() {
        // Dropped by CoDel.
    }

    private fun drainReady(): List<UdpSimPacket> {
        val ready = mutableListOf<UdpSimPacket>()
        while (scheduledPackets.isNotEmpty()) {
            val packet = scheduledPackets.peekFirst()
            if (packet.deliverAt < currentTime) {
                throw IllegalStateException("Internal error: Missed packet")
            }
            if (packet.deliverAt > currentTime) {
                break
            }
            ready += scheduledPackets.removeFirst().packet
        }
        return ready
    }
}
