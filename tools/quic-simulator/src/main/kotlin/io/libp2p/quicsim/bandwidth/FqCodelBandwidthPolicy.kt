package io.libp2p.quicsim.bandwidth

import io.libp2p.quicsim.NodeBandwidth
import java.net.InetSocketAddress
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Simplified FQ-CoDel policy:
 * - flow-queuing by `(sender, recipient)`
 * - DRR deficit accounting per flow
 * - CoDel drop scheduling per flow based on sojourn time
 * - node-level in/out bandwidth gates (same as token bucket policy)
 */
class FqCodelBandwidthPolicy(
    private val targetMillis: Long = 5,
    private val intervalMillis: Long = 100,
    private val quantumBytes: Int = 1514
) : BandwidthPolicy {

    private data class ChannelState(
        val inboundBytesPerSecond: Long,
        val outboundBytesPerSecond: Long,
        val inboundCapacity: Double,
        val outboundCapacity: Double,
        var inboundTokens: Double,
        var outboundTokens: Double,
        var lastRefillMillis: Long
    )

    private data class FlowId(val sender: InetSocketAddress, val recipient: InetSocketAddress)

    private data class FlowState(
        var deficitBytes: Int,
        var dropping: Boolean = false,
        var firstAboveTime: Long = 0,
        var dropCount: Int = 0,
        var dropNext: Long = 0
    )

    private val channels = linkedMapOf<InetSocketAddress, ChannelState>()
    private val flows = linkedMapOf<FlowId, FlowState>()

    override fun onBind(address: InetSocketAddress, initialBandwidth: NodeBandwidth, nowMillis: Long) {
        channels[address] = createChannelState(initialBandwidth, nowMillis)
    }

    override fun onUnbind(address: InetSocketAddress) {
        channels.remove(address)
        flows.keys.removeIf { it.sender == address || it.recipient == address }
    }

    override fun onTimeAdvanced(nowMillis: Long) {
        channels.values.forEach { refillTokens(it, nowMillis) }
    }

    override fun decide(
        sender: InetSocketAddress,
        recipient: InetSocketAddress,
        bytes: Int,
        enqueueTimeMillis: Long,
        nowMillis: Long
    ): BandwidthDecision {
        val senderChannel = channels[sender] ?: return BandwidthDecision.DROP
        val recipientChannel = channels[recipient] ?: return BandwidthDecision.DROP
        refillTokens(senderChannel, nowMillis)
        refillTokens(recipientChannel, nowMillis)
        if (!hasEnoughTokens(senderChannel.outboundTokens, senderChannel.outboundBytesPerSecond, bytes) ||
            !hasEnoughTokens(recipientChannel.inboundTokens, recipientChannel.inboundBytesPerSecond, bytes)
        ) {
            return BandwidthDecision.HOLD
        }

        val flow = flows.getOrPut(FlowId(sender, recipient)) { FlowState(deficitBytes = quantumBytes) }
        if (flow.deficitBytes < bytes) {
            flow.deficitBytes += quantumBytes
            return BandwidthDecision.HOLD
        }

        val sojourn = nowMillis - enqueueTimeMillis
        if (sojourn < targetMillis) {
            flow.firstAboveTime = 0
            if (flow.dropping) {
                flow.dropping = false
                flow.dropCount = 0
            }
        } else if (flow.firstAboveTime == 0L) {
            flow.firstAboveTime = nowMillis + intervalMillis
        } else if (!flow.dropping && nowMillis >= flow.firstAboveTime) {
            flow.dropping = true
            flow.dropCount = 1
            flow.dropNext = controlLaw(nowMillis, flow.dropCount)
            flow.deficitBytes -= bytes
            return BandwidthDecision.DROP
        }

        if (flow.dropping && sojourn >= targetMillis && nowMillis >= flow.dropNext) {
            flow.dropCount += 1
            flow.dropNext = controlLaw(flow.dropNext, flow.dropCount)
            flow.deficitBytes -= bytes
            return BandwidthDecision.DROP
        }

        senderChannel.outboundTokens = consumeTokens(senderChannel.outboundTokens, senderChannel.outboundBytesPerSecond, bytes)
        recipientChannel.inboundTokens = consumeTokens(recipientChannel.inboundTokens, recipientChannel.inboundBytesPerSecond, bytes)
        flow.deficitBytes -= bytes
        return BandwidthDecision.ALLOW
    }

    private fun controlLaw(baseMillis: Long, count: Int): Long {
        val spacing = intervalMillis.toDouble() / sqrt(count.toDouble())
        return baseMillis + spacing.toLong().coerceAtLeast(1L)
    }

    private fun createChannelState(bandwidth: NodeBandwidth, nowMillis: Long) = ChannelState(
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
        state.inboundTokens = refillTokenBucket(state.inboundTokens, state.inboundBytesPerSecond, state.inboundCapacity, elapsedMillis)
        state.outboundTokens =
            refillTokenBucket(state.outboundTokens, state.outboundBytesPerSecond, state.outboundCapacity, elapsedMillis)
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
