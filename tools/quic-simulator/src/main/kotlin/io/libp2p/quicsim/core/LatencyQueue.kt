package io.libp2p.quicsim.core

import kotlin.time.Duration

/**
 * A latency-only packet queue with independently controlled write and read ends.
 *
 * Packets submitted to [receiver] are scheduled for emission after an implementation
 * selected latency has elapsed on the receiver timeline. That latency may be fixed
 * or dynamic, but it must never be lower than [minimalLatency]. The [emitter] owns
 * a separate timeline and emits packets once it catches up to their scheduled
 * emission time.
 *
 * Implementations are expected to preserve packet order for packets submitted at
 * the same receiver time. Implementations may also be used from multiple threads
 * if their concrete class documents thread safety.
 */
interface LatencyQueue<TPacket> {

    /**
     * Lower bound for propagation delay between packet receipt and packet emission.
     *
     * The queue may use a larger per-packet latency, for example to model jitter,
     * changing link conditions, or externally supplied delivery floors. All timing
     * assumptions still hold when they are phrased in terms of this minimum bound:
     * no emitted packet can be scheduled earlier than receiver time plus
     * [minimalLatency].
     */
    val minimalLatency: Duration

    /**
     * Read end of the queue.
     *
     * The emitter may be advanced independently. Calling [PacketEmitter.emitPackets]
     * drains all packets scheduled exactly at the emitter's current time.
     */
    val emitter: PacketEmitter<TPacket>

    /**
     * Write end of the queue.
     *
     * The receiver may be advanced independently to any point in the future.
     * Packets passed to [PacketReceiver.receivePackets] are enqueued for
     * emission no earlier than the receiver's current time plus [minimalLatency].
     */
    val receiver: PacketReceiver<TPacket>
}
