package io.libp2p.quicsim.core

import io.libp2p.quicsim.core.schedule.impl.OptimizedAggregateProcessor
import io.libp2p.quicsim.core.schedule.Controllable
import java.util.ArrayDeque
import kotlin.time.Duration

typealias RouteId = Int

open class ControllablePacketRouter<TPacket>(
    routeProcessors: List<InOutProcessor<TPacket>>,
    private val routeSelector: (from: RouteId, packet: TPacket) -> RouteId,
) : Controllable {

    private val routeCount: RouteId = routeProcessors.size

    private val aggregateProcessor = OptimizedAggregateProcessor(routeProcessors)
    private val inboundPackets = mutableMapOf<RouteId, MutableList<TPacket>>()
    private val routesToProcess = ArrayDeque<RouteId>()
    private val queuedRoutes = BooleanArray(routeCount)
//    private val aggregateProcessor: AggregateProcessor<TPacket> = SimpleAggregateProcessor(routeProcessors)

    override fun advance(advanceDuration: Duration) {
        aggregateProcessor.advance(advanceDuration)
    }

    override fun executePending() {
        aggregateProcessor.executePending()
        pumpPackets()
    }

    override fun nextTaskDuration(): Duration? =
        aggregateProcessor.nextTaskDuration()

    fun pumpPackets() {
        fun enqueueRoute(routeId: RouteId) {
            if (!queuedRoutes[routeId]) {
                queuedRoutes[routeId] = true
                routesToProcess.addLast(routeId)
            }
        }

        aggregateProcessor.drainPendingEmitRoutes().forEach(::enqueueRoute)
        while (routesToProcess.isNotEmpty()) {
            val routeId = routesToProcess.removeFirst()
            queuedRoutes[routeId] = false
            inboundPackets.remove(routeId)?.let { packets ->
                aggregateProcessor.receivePackets(routeId, packets)
            }
            aggregateProcessor.emitPackets(routeId).forEach { outboundPacket ->
                val destinationRouteId = routeSelector(routeId, outboundPacket)
                assert(destinationRouteId != routeId)
                inboundPackets.getOrPut(destinationRouteId) { mutableListOf() } += outboundPacket
                enqueueRoute(destinationRouteId)
            }
        }
    }

    companion object {

        fun <TPacket> createSimplePump(
            packetProcessor1: PacketProcessor<TPacket>,
            packetProcessor2: PacketProcessor<TPacket>,
        ) =
            ControllablePacketPump(packetProcessor1, packetProcessor2)
    }
}
