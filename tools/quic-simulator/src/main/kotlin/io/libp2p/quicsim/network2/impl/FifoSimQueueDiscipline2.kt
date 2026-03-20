package io.libp2p.quicsim.network2.impl

import io.libp2p.quicsim.network.SimPacket
import io.libp2p.quicsim.network.impl.TransmissionMode
import io.libp2p.quicsim.network2.SimQueueDiscipline2
import java.util.ArrayDeque
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

class FifoSimQueueDiscipline2(
    private val bandwidthBytesPerSecond: Long,
    private val transmissionMode: TransmissionMode = TransmissionMode.SERIALIZED
) : SimQueueDiscipline2 {
    init {
        require(bandwidthBytesPerSecond > 0) { "bandwidthBytesPerSecond must be > 0" }
    }

    private data class QueuedPacket(
        val packet: SimPacket,
        val enqueueAt: Duration
    )

    private val queue = ArrayDeque<QueuedPacket>()
    private val readyPackets = ArrayDeque<SimPacket>()

    private var currentTime: Duration = Duration.Companion.ZERO
    private var nextDequeueAvailableTime: Duration = Duration.Companion.ZERO

    override fun deliver(inboundData: List<SimPacket>): List<SimPacket> {
        inboundData.forEach { packet ->
            queue.addLast(QueuedPacket(packet, currentTime))
        }

        val ready = ArrayList<SimPacket>(readyPackets.size)
        while (readyPackets.isNotEmpty()) {
            ready += readyPackets.removeFirst()
        }
        return ready
    }

    override fun advanceAndExecuteAll(advanceDuration: Duration) {
        require(!advanceDuration.isNegative()) { "advanceDuration must be non-negative" }
        val targetTime = currentTime + advanceDuration

        while (queue.isNotEmpty()) {
            val queued = queue.first()
            val transmitStart = maxOf(nextDequeueAvailableTime, queued.enqueueAt)
            val serviceTime = serializationDuration(queued.packet.bytes)
            val dequeueTime = when (transmissionMode) {
                TransmissionMode.SERIALIZED -> transmitStart + serviceTime
                TransmissionMode.SHAPED_IMMEDIATE -> transmitStart
            }

            if (dequeueTime > targetTime) {
                break
            }

            currentTime = dequeueTime
            queue.removeFirst()
            nextDequeueAvailableTime = transmitStart + serviceTime
            readyPackets.addLast(queued.packet)
        }

        currentTime = targetTime
    }

    override fun nextTaskDuration(): Duration? {
        if (readyPackets.isNotEmpty()) {
            return Duration.Companion.ZERO
        }
        val queued = queue.firstOrNull() ?: return null
        val transmitStart = maxOf(nextDequeueAvailableTime, queued.enqueueAt)
        val serviceTime = serializationDuration(queued.packet.bytes)
        val dequeueTime = when (transmissionMode) {
            TransmissionMode.SERIALIZED -> transmitStart + serviceTime
            TransmissionMode.SHAPED_IMMEDIATE -> transmitStart
        }
        return (dequeueTime - currentTime).coerceAtLeast(Duration.Companion.ZERO)
    }

    private fun serializationDuration(bytes: Int): Duration {
        val nanos = ((bytes.toLong() * NANOS_PER_SECOND) + bandwidthBytesPerSecond - 1) / bandwidthBytesPerSecond
        return nanos.coerceAtLeast(1L).nanoseconds
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}