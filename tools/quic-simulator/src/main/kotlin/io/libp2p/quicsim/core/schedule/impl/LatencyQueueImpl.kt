package io.libp2p.quicsim.core.schedule.impl

import io.libp2p.quicsim.core.LatencyQueue
import io.libp2p.quicsim.core.PacketEmitter
import io.libp2p.quicsim.core.PacketReceiver
import java.util.ArrayDeque
import kotlin.time.Duration

/**
 * Thread-safe fixed-latency [io.libp2p.quicsim.core.LatencyQueue] implementation backed by a single FIFO queue.
 */
class LatencyQueueImpl<TPacket>(
    val latency: Duration
) : LatencyQueue<TPacket> {
    data class TimedPackets<TPacket>(
        val at: Duration,
        val packets: List<TPacket>
    )

    init {
        require(!latency.isNegative()) { "latency must be non-negative" }
    }

    private val lock = Any()
    private val primaryEmitter = Emitter()
    private val primaryReceiver = Receiver()

    private data class QueuedPacket<TPacket>(
        val packet: TPacket,
        val emitAt: Duration
    )

    private val queue = ArrayDeque<QueuedPacket<TPacket>>()

    override val minimalLatency: Duration
        get() = latency

    private inline fun <T> locked(block: () -> T): T =
        synchronized(lock) {
            block()
        }

    override val emitter: PacketEmitter<TPacket> = primaryEmitter

    override val receiver: PacketReceiver<TPacket> = primaryReceiver

    fun emitPacketsUntil(targetTime: Duration): List<TimedPackets<TPacket>> =
        locked {
            check(targetTime >= primaryEmitter.emitterTime)
            val emitted = mutableListOf<TimedPackets<TPacket>>()
            while (queue.isNotEmpty()) {
                val queuedPacket = queue.peekFirst()
                if (queuedPacket.emitAt > targetTime) {
                    break
                }
                primaryEmitter.emitterTime = queuedPacket.emitAt
                emitted += TimedPackets(primaryEmitter.emitterTime, drainReady(primaryEmitter.emitterTime))
            }
            primaryEmitter.emitterTime = targetTime
            emitted
        }

    fun receivePacketsAt(packets: List<TPacket>, at: Duration) {
        if (packets.isEmpty()) {
            return
        }
        locked {
            check(at >= primaryReceiver.receiverTime)
            primaryReceiver.receiverTime = at
            packets.forEach { packet ->
                enqueue(packet, primaryReceiver.receiverTime + latency)
            }
        }
    }

    fun <TInput> receiveTimedPackets(
        packets: List<TInput>,
        timeExtractor: (TInput) -> Duration,
        packetExtractor: (TInput) -> TPacket
    ) {
        if (packets.isEmpty()) {
            return
        }
        locked {
            packets.forEach { input ->
                val at = timeExtractor(input)
                check(at >= primaryReceiver.receiverTime)
                primaryReceiver.receiverTime = at
                enqueue(packetExtractor(input), primaryReceiver.receiverTime + latency)
            }
        }
    }

    fun nextEmitTime(): Duration? =
        locked {
            queue.peekFirst()?.emitAt
        }

    private fun drainReady(at: Duration): List<TPacket> {
        val ready = mutableListOf<TPacket>()
        while (queue.isNotEmpty()) {
            val queuedPacket = queue.peekFirst()
            if (queuedPacket.emitAt < at) {
                throw IllegalStateException("Internal error: Missed packet")
            }
            if (queuedPacket.emitAt > at) {
                break
            }
            ready += queue.removeFirst().packet
        }
        return ready
    }

    private fun enqueue(packet: TPacket, emitAt: Duration) {
        queue.addLast(QueuedPacket(packet, emitAt.coerceAtLeast(primaryEmitter.emitterTime)))
    }

    private inner class Emitter : PacketEmitter<TPacket> {
        var emitterTime: Duration = Duration.Companion.ZERO

        override fun emitPackets(): List<TPacket> =
            locked {
                drainReady(emitterTime)
            }

        override fun advance(advanceDuration: Duration) {
            locked {
                val nextTime = emitterTime + advanceDuration
                emitterTime = nextTime
            }
        }

        override fun executePending() {
        }

        override fun nextTaskDuration(): Duration? =
            locked {
                queue.peekFirst()?.let { it.emitAt - emitterTime }
            }
    }

    private inner class Receiver : PacketReceiver<TPacket> {
        var receiverTime: Duration = Duration.Companion.ZERO

        override fun receivePackets(packets: List<TPacket>) {
            locked {
                packets.forEach { packet ->
                    enqueue(packet, receiverTime + latency)
                }
            }
        }

        override fun advance(advanceDuration: Duration) {
            locked {
                receiverTime += advanceDuration
            }
        }

        override fun executePending() {
        }

        override fun nextTaskDuration(): Duration? =
            null
    }

}
