package io.libp2p.quicsim.network.impl

import io.libp2p.quicsim.network.SimLink
import io.libp2p.quicsim.network.SimNetwork
import io.libp2p.quicsim.network.SimNetworkEngine
import io.libp2p.quicsim.network.SimPacket
import io.libp2p.quicsim.network.SimQueueDiscipline
import java.util.ArrayDeque
import java.util.PriorityQueue
import java.util.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class BasicSimNetworkEngine(
    override val network: SimNetwork,
    random: Random = Random(1)
) : SimNetworkEngine {
    private data class LinkState(
        var readyEventScheduled: Boolean = false
    )

    private sealed interface Event {
        val time: Long
    }

    private data class NodeIngressEvent(
        override val time: Long,
        val nodeId: String,
        val packet: SimPacket
    ) : Event

    private data class LinkReadyEvent(
        override val time: Long,
        val linkIndex: Int
    ) : Event

    var currentTimeMillis: Long = 0
        private set

    private var sequence = 0L
    private val rng = random

    private val linkByIndex = network.links.withIndex().associate { it.index to it.value }
    private val linkStateByIndex = network.links.indices.associateWith { LinkState() }.toMutableMap()
    private val linksFromNode = network.links.groupBy { it.from.id }

    private val eventQueue = PriorityQueue(compareBy<QueuedEvent> { it.time }.thenBy { it.sequence })
    private val pendingDelivered = ArrayDeque<SimPacket>()

    private data class QueuedEvent(
        val time: Long,
        val sequence: Long,
        val event: Event
    )

    override fun deliver(inboundData: List<SimPacket>): List<SimPacket> {
        inboundData.forEach { injectPacket(it) }

        val ready = ArrayList<SimPacket>(pendingDelivered.size)
        while (pendingDelivered.isNotEmpty()) {
            ready += pendingDelivered.removeFirst()
        }

        ready += processEventsUpTo(currentTimeMillis, stopAtFirstDeliveryTime = false)
        if (ready.isNotEmpty()) {
            return ready
        }

        return processUntilFirstDelivery()
    }

    override fun advanceAndExecuteAll(advanceDuration: Duration) {
        require(!advanceDuration.isNegative()) { "advanceDuration must be non-negative" }
        val targetTime = currentTimeMillis + advanceDuration.inWholeMilliseconds
        pendingDelivered += processEventsUpTo(targetTime, stopAtFirstDeliveryTime = false)
    }

    override fun nextTaskDuration(): Duration? {
        val next = eventQueue.peek() ?: return null
        val untilNext = (next.time - currentTimeMillis).coerceAtLeast(0)
        return untilNext.milliseconds
    }

    private fun injectPacket(packet: SimPacket) {
        enqueueEvent(NodeIngressEvent(currentTimeMillis, packet.srcNodeId, packet), currentTimeMillis)
    }

    private fun processEventsUpTo(maxMillis: Long, stopAtFirstDeliveryTime: Boolean): List<SimPacket> {
        require(maxMillis >= currentTimeMillis) {
            "maxMillis must be >= currentTimeMillis"
        }

        val delivered = ArrayDeque<SimPacket>()
        var deliveryTime: Long? = null

        while (true) {
            val next = eventQueue.peek() ?: break
            if (next.time > maxMillis) {
                break
            }
            if (deliveryTime != null && next.time > deliveryTime) {
                break
            }

            val queued = eventQueue.poll()
            currentTimeMillis = queued.time
            when (val e = queued.event) {
                is NodeIngressEvent -> {
                    if (e.nodeId == e.packet.dstNodeId) {
                        delivered += e.packet
                        if (deliveryTime == null) {
                            deliveryTime = queued.time
                            if (!stopAtFirstDeliveryTime) {
                                deliveryTime = null
                            }
                        }
                    } else {
                        forwardPacket(e)
                    }
                }

                is LinkReadyEvent -> {
                    onLinkReady(e)
                }
            }
        }

        if (deliveryTime == null || !stopAtFirstDeliveryTime) {
            currentTimeMillis = maxMillis
        }
        return delivered.toList()
    }

    private fun processUntilFirstDelivery(): List<SimPacket> {
        val delivered = ArrayDeque<SimPacket>()
        var deliveryTime: Long? = null

        while (true) {
            val next = eventQueue.peek() ?: break
            if (deliveryTime != null && next.time > deliveryTime) {
                break
            }

            val queued = eventQueue.poll()
            currentTimeMillis = queued.time
            when (val e = queued.event) {
                is NodeIngressEvent -> {
                    if (e.nodeId == e.packet.dstNodeId) {
                        delivered += e.packet
                        if (deliveryTime == null) {
                            deliveryTime = queued.time
                        }
                    } else {
                        forwardPacket(e)
                    }
                }

                is LinkReadyEvent -> {
                    onLinkReady(e)
                }
            }
        }

        return delivered.toList()
    }

    private fun forwardPacket(event: NodeIngressEvent) {
        val nextLink = findNextLink(event.nodeId, event.packet.dstNodeId) ?: return
        val linkIndex = network.links.indexOf(nextLink)
        if (linkIndex < 0) return

        // Keep queue internal time aligned with engine time before enqueueing into an idle queue.
        if (!nextLink.qdisc.hasPendingPackets && nextLink.qdisc.currentTimeMillis < currentTimeMillis) {
            nextLink.qdisc.advanceUntilDequeueOr(currentTimeMillis)
        }

        val enqueueDecision = nextLink.qdisc.enqueue(event.packet)
        if (enqueueDecision != SimQueueDiscipline.EnqueueDecision.QUEUED) {
            return
        }

        scheduleLinkReadyIfNeeded(linkIndex, currentTimeMillis)
    }

    private fun onLinkReady(event: LinkReadyEvent) {
        val link = linkByIndex[event.linkIndex] ?: return
        val state = linkStateByIndex[event.linkIndex] ?: return
        state.readyEventScheduled = false

        val dequeued = link.qdisc.advanceUntilDequeueOr(currentTimeMillis)
        if (dequeued.isEmpty()) {
            if (link.qdisc.hasPendingPackets) {
                scheduleLinkReadyIfNeeded(event.linkIndex, currentTimeMillis + 1)
            }
            return
        }

        for (packet in dequeued) {
            val arrivalTime = currentTimeMillis + link.latency.toMillis()

            if (link.lossProbability > 0.0 && rng.nextDouble() < link.lossProbability) {
                continue
            }
            enqueueEvent(NodeIngressEvent(arrivalTime, link.to.id, packet), arrivalTime)
        }

        if (link.qdisc.hasPendingPackets) {
            scheduleLinkReadyIfNeeded(event.linkIndex, currentTimeMillis + 1)
        }
    }

    private fun scheduleLinkReadyIfNeeded(linkIndex: Int, time: Long) {
        val state = linkStateByIndex[linkIndex] ?: return
        if (state.readyEventScheduled) return
        state.readyEventScheduled = true
        enqueueEvent(LinkReadyEvent(time, linkIndex), time)
    }

    private fun enqueueEvent(event: Event, time: Long) {
        eventQueue += QueuedEvent(time, sequence++, event)
    }

    private fun findNextLink(fromNodeId: String, dstNodeId: String): SimLink? {
        val direct = linksFromNode[fromNodeId].orEmpty().firstOrNull { it.to.id == dstNodeId }
        if (direct != null) {
            return direct
        }

        val queue = ArrayDeque<String>()
        val prev = mutableMapOf<String, String?>()
        queue += fromNodeId
        prev[fromNodeId] = null

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node == dstNodeId) break
            linksFromNode[node].orEmpty().forEach { link ->
                val next = link.to.id
                if (prev.containsKey(next)) return@forEach
                prev[next] = node
                queue += next
            }
        }

        if (!prev.containsKey(dstNodeId)) {
            return null
        }

        var step = dstNodeId
        var parent = prev[step]
        while (parent != null && parent != fromNodeId) {
            step = parent
            parent = prev[step]
        }

        return linksFromNode[fromNodeId].orEmpty().firstOrNull { it.to.id == step }
    }
}
