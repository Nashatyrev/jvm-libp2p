package io.libp2p.quicsim.network.impl

import io.libp2p.quicsim.network.SimLink
import io.libp2p.quicsim.network.SimNetwork
import io.libp2p.quicsim.network.SimNetworkEngine
import io.libp2p.quicsim.network.SimPacket
import java.util.ArrayDeque
import java.util.PriorityQueue
import java.util.Random
import kotlin.math.ceil

class BasicSimNetworkEngine(
    override val network: SimNetwork,
    random: Random = Random(1)
) : SimNetworkEngine {

    private data class LinkState(
        var nextTransmitFreeMillis: Long = 0,
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

    override var currentTimeMillis: Long = 0
        private set

    private var sequence = 0L
    private val rng = random

    private val linkByIndex = network.links.withIndex().associate { it.index to it.value }
    private val linkStateByIndex = network.links.indices.associateWith { LinkState() }.toMutableMap()
    private val linksFromNode = network.links.groupBy { it.from.id }

    private val eventQueue = PriorityQueue(compareBy<QueuedEvent> { it.time }.thenBy { it.sequence })

    private data class QueuedEvent(
        val time: Long,
        val sequence: Long,
        val event: Event
    )

    override fun injectPacket(packet: SimPacket) {
        enqueueEvent(NodeIngressEvent(currentTimeMillis, packet.srcNodeId, packet), currentTimeMillis)
    }

    override fun advanceUntilDeliveryOr(maxMillis: Long): List<SimPacket> {
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

        if (deliveryTime == null) {
            currentTimeMillis = maxMillis
        }
        return delivered.toList()
    }

    private fun forwardPacket(event: NodeIngressEvent) {
        val nextLink = findNextLink(event.nodeId, event.packet.dstNodeId) ?: return
        val linkIndex = network.links.indexOf(nextLink)
        if (linkIndex < 0) return

        val enqueueDecision = nextLink.qdisc.enqueue(event.packet)
        if (enqueueDecision != io.libp2p.quicsim.network.SimQueueDiscipline.EnqueueDecision.QUEUED) {
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
            return
        }

        for (packet in dequeued) {
            val start = maxOf(currentTimeMillis, state.nextTransmitFreeMillis)
            val txMillis = serializationMillis(packet.bytes, link)
            val finishTransmit = start + txMillis
            state.nextTransmitFreeMillis = finishTransmit
            val arrivalTime = finishTransmit + link.latency.toMillis()

            if (link.lossProbability > 0.0 && rng.nextDouble() < link.lossProbability) {
                continue
            }
            enqueueEvent(NodeIngressEvent(arrivalTime, link.to.id, packet), arrivalTime)
        }

        scheduleLinkReadyIfNeeded(event.linkIndex, state.nextTransmitFreeMillis)
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

    private fun serializationMillis(bytes: Int, link: SimLink): Long {
        return ceil(bytes.toDouble() * 1000.0 / link.bandwidthBytesPerSecond.toDouble()).toLong().coerceAtLeast(1)
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
