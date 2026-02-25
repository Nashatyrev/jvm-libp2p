package io.libp2p.quicsim

import java.net.InetSocketAddress
import kotlin.math.max
import kotlin.math.min

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
 * 3. [tryConsume] is called for queued datagrams before delivery.
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
     * Notifies policy that simulator time has moved forward.
     *
     * Implementations may refill token buckets or apply other time-based logic.
     */
    fun onTimeAdvanced(nowMillis: Long)

    /**
     * Returns `true` if a datagram can be delivered and consumes corresponding budget.
     *
     * The call models one directional transfer from [sender] to [recipient].
     */
    fun tryConsume(sender: InetSocketAddress, recipient: InetSocketAddress, bytes: Int, nowMillis: Long): Boolean
}

/**
 * Default [BandwidthPolicy] based on per-node inbound/outbound token buckets.
 *
 * A transfer is allowed only if:
 * - sender has enough outbound tokens, and
 * - recipient has enough inbound tokens.
 */
class TokenBucketBandwidthPolicy : BandwidthPolicy {

    private data class ChannelState(
        val inboundBytesPerSecond: Long,
        val outboundBytesPerSecond: Long,
        val inboundCapacity: Double,
        val outboundCapacity: Double,
        var inboundTokens: Double,
        var outboundTokens: Double,
        var lastRefillMillis: Long
    )

    private val statesByAddress = linkedMapOf<InetSocketAddress, ChannelState>()

    override fun onBind(address: InetSocketAddress, initialBandwidth: NodeBandwidth, nowMillis: Long) {
        statesByAddress[address] = createState(initialBandwidth, nowMillis)
    }

    override fun onUnbind(address: InetSocketAddress) {
        statesByAddress.remove(address)
    }

    override fun onTimeAdvanced(nowMillis: Long) {
        statesByAddress.values.forEach { refillTokens(it, nowMillis) }
    }

    override fun tryConsume(sender: InetSocketAddress, recipient: InetSocketAddress, bytes: Int, nowMillis: Long): Boolean {
        val senderState = statesByAddress[sender] ?: return false
        val recipientState = statesByAddress[recipient] ?: return false
        refillTokens(senderState, nowMillis)
        refillTokens(recipientState, nowMillis)

        if (!hasEnoughTokens(senderState.outboundTokens, senderState.outboundBytesPerSecond, bytes) ||
            !hasEnoughTokens(recipientState.inboundTokens, recipientState.inboundBytesPerSecond, bytes)
        ) {
            return false
        }

        senderState.outboundTokens = consumeTokens(senderState.outboundTokens, senderState.outboundBytesPerSecond, bytes)
        recipientState.inboundTokens = consumeTokens(recipientState.inboundTokens, recipientState.inboundBytesPerSecond, bytes)
        return true
    }

    private fun createState(bandwidth: NodeBandwidth, nowMillis: Long) = ChannelState(
        inboundBytesPerSecond = bandwidth.inboundBytesPerSecond,
        outboundBytesPerSecond = bandwidth.outboundBytesPerSecond,
        inboundCapacity = capacityFor(bandwidth.inboundBytesPerSecond),
        outboundCapacity = capacityFor(bandwidth.outboundBytesPerSecond),
        inboundTokens = initialTokensFor(bandwidth.inboundBytesPerSecond),
        outboundTokens = initialTokensFor(bandwidth.outboundBytesPerSecond),
        lastRefillMillis = nowMillis
    )

    private fun refillTokens(state: ChannelState, nowMillis: Long) {
        val elapsedMillis = nowMillis - state.lastRefillMillis
        if (elapsedMillis <= 0) return

        state.inboundTokens = refillTokenBucket(
            tokens = state.inboundTokens,
            bytesPerSecond = state.inboundBytesPerSecond,
            capacity = state.inboundCapacity,
            elapsedMillis = elapsedMillis
        )
        state.outboundTokens = refillTokenBucket(
            tokens = state.outboundTokens,
            bytesPerSecond = state.outboundBytesPerSecond,
            capacity = state.outboundCapacity,
            elapsedMillis = elapsedMillis
        )
        state.lastRefillMillis = nowMillis
    }

    private fun refillTokenBucket(tokens: Double, bytesPerSecond: Long, capacity: Double, elapsedMillis: Long): Double {
        if (bytesPerSecond == Long.MAX_VALUE) return Double.POSITIVE_INFINITY
        val replenished = tokens + bytesPerSecond.toDouble() * elapsedMillis.toDouble() / 1000.0
        return min(capacity, replenished)
    }

    private fun hasEnoughTokens(tokens: Double, bytesPerSecond: Long, bytes: Int): Boolean {
        if (bytesPerSecond == Long.MAX_VALUE) return true
        return tokens + 1e-9 >= bytes
    }

    private fun consumeTokens(tokens: Double, bytesPerSecond: Long, bytes: Int): Double {
        if (bytesPerSecond == Long.MAX_VALUE) return Double.POSITIVE_INFINITY
        return max(0.0, tokens - bytes.toDouble())
    }

    private fun capacityFor(bytesPerSecond: Long): Double {
        if (bytesPerSecond == Long.MAX_VALUE) return Double.POSITIVE_INFINITY
        return max(bytesPerSecond.toDouble(), 65535.0)
    }

    private fun initialTokensFor(bytesPerSecond: Long): Double {
        if (bytesPerSecond == Long.MAX_VALUE) return Double.POSITIVE_INFINITY
        return bytesPerSecond.toDouble()
    }
}
