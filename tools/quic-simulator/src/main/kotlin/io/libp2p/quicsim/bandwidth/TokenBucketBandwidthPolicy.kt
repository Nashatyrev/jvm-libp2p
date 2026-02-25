package io.libp2p.quicsim.bandwidth

import io.libp2p.quicsim.NodeBandwidth
import java.net.InetSocketAddress
import kotlin.math.max
import kotlin.math.min

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

    override fun decide(
        sender: InetSocketAddress,
        recipient: InetSocketAddress,
        bytes: Int,
        enqueueTimeMillis: Long,
        nowMillis: Long
    ): BandwidthDecision {
        val senderState = statesByAddress[sender] ?: return BandwidthDecision.DROP
        val recipientState = statesByAddress[recipient] ?: return BandwidthDecision.DROP
        refillTokens(senderState, nowMillis)
        refillTokens(recipientState, nowMillis)

        if (!hasEnoughTokens(senderState.outboundTokens, senderState.outboundBytesPerSecond, bytes) ||
            !hasEnoughTokens(recipientState.inboundTokens, recipientState.inboundBytesPerSecond, bytes)
        ) {
            return BandwidthDecision.HOLD
        }

        senderState.outboundTokens = consumeTokens(senderState.outboundTokens, senderState.outboundBytesPerSecond, bytes)
        recipientState.inboundTokens = consumeTokens(recipientState.inboundTokens, recipientState.inboundBytesPerSecond, bytes)
        return BandwidthDecision.ALLOW
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
