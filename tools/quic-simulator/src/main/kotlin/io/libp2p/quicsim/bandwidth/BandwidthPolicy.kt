package io.libp2p.quicsim.bandwidth

import io.libp2p.quicsim.NodeBandwidth
import io.libp2p.quicsim.SimulatedDatagramNetwork
import java.net.InetSocketAddress

/**
 * Pluggable policy for bandwidth admission in [SimulatedDatagramNetwork].
 *
 * The network delegates all bandwidth decisions to this interface. Implementations decide
 * whether a queued datagram may be delivered at the current simulated time.
 *
 * Units:
 * - bandwidth is expressed in bytes per second
 * - `nowMillis` is the simulator wall-clock in milliseconds
 * - `bytes` is datagram payload size in bytes
 *
 * Lifecycle:
 * 1. [onBind] is called when a simulated UDP parent channel is created.
 * 2. [onTimeAdvanced] is called whenever the simulator clock is advanced.
 * 3. [decide] is called for queued datagrams before delivery.
 * 4. [onUnbind] is called when a channel is closed/unbound.
 */
interface BandwidthPolicy {
    /**
     * Registers a node address in the policy with its initial bandwidth configuration.
     */
    fun onBind(address: InetSocketAddress, initialBandwidth: NodeBandwidth, nowMillis: Long)

    /**
     * Removes all policy state associated with a node address.
     */
    fun onUnbind(address: InetSocketAddress)

    /**
     * Notifies the policy that simulator time has moved forward.
     *
     * Implementations may refill token buckets or apply other time-based logic.
     */
    fun onTimeAdvanced(nowMillis: Long)

    /**
     * Decides how to handle one queued datagram transfer.
     *
     * [sender] and [recipient] identify transfer direction,
     * [bytes] is payload size,
     * [enqueueTimeMillis] is packet enqueue timestamp,
     * and [nowMillis] is current simulator time.
     */
    fun decide(
        sender: InetSocketAddress,
        recipient: InetSocketAddress,
        bytes: Int,
        enqueueTimeMillis: Long,
        nowMillis: Long
    ): BandwidthDecision
}
