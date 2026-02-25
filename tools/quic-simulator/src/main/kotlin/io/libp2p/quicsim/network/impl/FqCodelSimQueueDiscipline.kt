package io.libp2p.quicsim.network.impl

import io.libp2p.quicsim.network.SimPacket
import io.libp2p.quicsim.network.SimQueueDiscipline
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Simplified FQ-CoDel queue discipline with link-rate shaping.
 *
 * - Per-flow FIFO queues keyed by [SimPacket.flowKey]
 * - DRR flow scheduling with configurable [quantumBytes]
 * - CoDel drop control using packet sojourn time
 * - Serialization delay based on [bandwidthBytesPerSecond]
 */
class FqCodelSimQueueDiscipline(
    private val bandwidthBytesPerSecond: Long,
    private val quantumBytes: Int = 1514,
    private val targetMillis: Long = 5,
    private val intervalMillis: Long = 100,
    private val maxQueuePackets: Int = Int.MAX_VALUE,
    private val transmissionMode: TransmissionMode = TransmissionMode.SERIALIZED
) : SimQueueDiscipline {

    private data class QueuedPacket(
        val packet: SimPacket,
        val enqueueAtMillis: Long
    )

    private data class FlowState(
        val queue: ArrayDeque<QueuedPacket> = ArrayDeque(),
        var deficitBytes: Int = 0,
        var dropping: Boolean = false,
        var firstAboveTimeMillis: Long = 0,
        var dropCount: Int = 0,
        var dropNextMillis: Long = 0
    )

    init {
        require(bandwidthBytesPerSecond > 0) { "bandwidthBytesPerSecond must be > 0" }
        require(quantumBytes > 0) { "quantumBytes must be > 0" }
        require(targetMillis > 0) { "targetMillis must be > 0" }
        require(intervalMillis > 0) { "intervalMillis must be > 0" }
        require(maxQueuePackets > 0) { "maxQueuePackets must be > 0" }
    }

    override var currentTimeMillis: Long = 0
        private set

    override val hasPendingPackets: Boolean
        get() = totalQueuedPackets > 0

    private val flows = linkedMapOf<String, FlowState>()
    private val activeFlows = ArrayDeque<String>()
    private val activeFlowSet = linkedSetOf<String>()
    private var totalQueuedPackets = 0
    private var nextTransmitFreeMillis: Long = 0

    override fun enqueue(packet: SimPacket): SimQueueDiscipline.EnqueueDecision {
        if (totalQueuedPackets >= maxQueuePackets) {
            return SimQueueDiscipline.EnqueueDecision.DROPPED
        }

        val flowId = packet.flowKey
        val flow = flows.getOrPut(flowId) { FlowState(deficitBytes = quantumBytes) }
        flow.queue.addLast(QueuedPacket(packet, currentTimeMillis))
        totalQueuedPackets += 1

        if (activeFlowSet.add(flowId)) {
            activeFlows.addLast(flowId)
        }

        return SimQueueDiscipline.EnqueueDecision.QUEUED
    }

    override fun advanceUntilDequeueOr(maxMillis: Long): List<SimPacket> {
        require(maxMillis >= currentTimeMillis) {
            "maxMillis must be >= currentTimeMillis"
        }

        if (!hasPendingPackets) {
            currentTimeMillis = maxMillis
            return emptyList()
        }

        while (activeFlows.isNotEmpty()) {
            val flowId = activeFlows.removeFirst()
            activeFlowSet.remove(flowId)
            val flow = flows[flowId] ?: continue
            if (flow.queue.isEmpty()) {
                continue
            }

            val head = flow.queue.first()
            if (flow.deficitBytes < head.packet.bytes) {
                flow.deficitBytes += quantumBytes
                requeueFlow(flowId, flow)
                continue
            }

            val txStart = maxOf(nextTransmitFreeMillis, head.enqueueAtMillis)
            if (txStart > maxMillis) {
                requeueFlow(flowId, flow)
                currentTimeMillis = maxMillis
                return emptyList()
            }

            // CoDel decision at dequeue eligibility time before transmission.
            val sojourn = txStart - head.enqueueAtMillis
            if (shouldDrop(flow, sojourn, txStart)) {
                flow.queue.removeFirst()
                totalQueuedPackets -= 1
                flow.deficitBytes -= head.packet.bytes
                if (flow.queue.isEmpty()) {
                    flows.remove(flowId)
                } else {
                    requeueFlow(flowId, flow)
                }
                currentTimeMillis = txStart
                if (!hasPendingPackets) {
                    currentTimeMillis = maxMillis.coerceAtLeast(currentTimeMillis)
                    return emptyList()
                }
                continue
            }

            val serviceMillis = serializationMillis(head.packet.bytes)
            val dequeueTime = when (transmissionMode) {
                TransmissionMode.SERIALIZED -> txStart + serviceMillis
                TransmissionMode.SHAPED_IMMEDIATE -> txStart
            }
            if (dequeueTime > maxMillis) {
                requeueFlow(flowId, flow)
                currentTimeMillis = maxMillis
                return emptyList()
            }

            currentTimeMillis = dequeueTime
            nextTransmitFreeMillis = txStart + serviceMillis
            flow.queue.removeFirst()
            totalQueuedPackets -= 1
            flow.deficitBytes -= head.packet.bytes

            if (flow.queue.isEmpty()) {
                flows.remove(flowId)
            } else {
                requeueFlow(flowId, flow)
            }

            return listOf(head.packet)
        }

        currentTimeMillis = maxMillis
        return emptyList()
    }

    private fun requeueFlow(flowId: String, flow: FlowState) {
        if (flow.queue.isNotEmpty() && activeFlowSet.add(flowId)) {
            activeFlows.addLast(flowId)
        }
    }

    private fun shouldDrop(flow: FlowState, sojournMillis: Long, nowMillis: Long): Boolean {
        if (sojournMillis < targetMillis) {
            flow.firstAboveTimeMillis = 0
            if (flow.dropping) {
                flow.dropping = false
                flow.dropCount = 0
            }
            return false
        }

        if (!flow.dropping) {
            if (flow.firstAboveTimeMillis == 0L) {
                flow.firstAboveTimeMillis = nowMillis + intervalMillis
                return false
            }
            if (nowMillis >= flow.firstAboveTimeMillis) {
                flow.dropping = true
                flow.dropCount = 1
                flow.dropNextMillis = controlLaw(nowMillis, flow.dropCount)
                return true
            }
            return false
        }

        if (nowMillis >= flow.dropNextMillis) {
            flow.dropCount += 1
            flow.dropNextMillis = controlLaw(flow.dropNextMillis, flow.dropCount)
            return true
        }

        return false
    }

    private fun controlLaw(baseMillis: Long, count: Int): Long {
        val spacing = intervalMillis.toDouble() / sqrt(count.toDouble())
        return baseMillis + spacing.toLong().coerceAtLeast(1L)
    }

    private fun serializationMillis(bytes: Int): Long {
        return ceil(bytes.toDouble() * 1000.0 / bandwidthBytesPerSecond.toDouble()).toLong().coerceAtLeast(1L)
    }
}
