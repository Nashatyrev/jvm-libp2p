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
    init {
        require(!latency.isNegative()) { "latency must be non-negative" }
    }

    private val lock = Any()
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

    override val emitter: PacketEmitter<TPacket> = Emitter()

    override val receiver: PacketReceiver<TPacket> = primaryReceiver

    internal fun receivePacketsAt(
        packets: List<TPacket>,
        at: Duration,
        deliveryFloor: Duration = Duration.Companion.ZERO
    ) {
        locked {
            packets.forEach { packet ->
                queue.addLast(QueuedPacket(packet, (at + latency).coerceAtLeast(deliveryFloor)))
            }
        }
    }

    internal fun emitPacketsAt(at: Duration): List<TPacket> =
        locked {
            drainReady(at)
        }

    internal fun nextTaskDurationAt(at: Duration): Duration? =
        locked {
            queue.peekFirst()?.let { it.emitAt - at }
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

    private inner class Emitter : PacketEmitter<TPacket> {
        private var emitterTime: Duration = Duration.Companion.ZERO

        override fun emitPackets(): List<TPacket> =
            locked {
                drainReady(emitterTime)
            }

        override fun advance(advanceDuration: Duration) {
            require(!advanceDuration.isNegative()) { "advanceDuration must be non-negative" }
            locked {
                val nextTime = emitterTime + advanceDuration
                val maxEmitterTime = primaryReceiver.receiverTime + latency
                require(nextTime <= maxEmitterTime) {
                    "Emitter cannot advance past latency bound $maxEmitterTime"
                }
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
            private set

        override fun receivePackets(packets: List<TPacket>) {
            locked {
                packets.forEach { packet ->
                    queue.addLast(QueuedPacket(packet, receiverTime + latency))
                }
            }
        }

        override fun advance(advanceDuration: Duration) {
            require(!advanceDuration.isNegative()) { "advanceDuration must be non-negative" }
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
