package io.libp2p.quicsim.core

import io.libp2p.quicsim.core.schedule.AggregateProcessor
import io.libp2p.quicsim.core.schedule.impl.OptimizedAggregateProcessor
import io.libp2p.quicsim.core.schedule.Controllable
import kotlin.time.Duration

typealias RouteId = Int

open class ControllablePacketRouter<TPacket>(
    routeProcessors: List<InOutProcessor<TPacket>>,
    private val routeSelector: (from: RouteId, packet: TPacket) -> RouteId,
) : Controllable {

    private val routeCount: RouteId = routeProcessors.size

    private val aggregateProcessor: AggregateProcessor<TPacket> = OptimizedAggregateProcessor(routeProcessors)
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

        val inboundPackets = List(routeCount) { mutableListOf<TPacket>() }

        var unprocessedPacketsCount = 0
        do {

            for (i: RouteId in 0 until routeCount) {
                val inPackets = inboundPackets[i]
                if (inPackets.isNotEmpty()) {
                    aggregateProcessor.receivePackets(i, inPackets)
                    unprocessedPacketsCount -= inPackets.size
                    inPackets.clear()
                }
                val outPackets = aggregateProcessor.emitPackets(i)
                if (outPackets.isNotEmpty()) {
                    outPackets.forEach { outboundPacket ->
                        val destinationRouteId = routeSelector(i, outboundPacket)
                        assert(destinationRouteId != i)
                        inboundPackets[destinationRouteId] += outboundPacket
                    }
                    unprocessedPacketsCount += outPackets.size
                }
            }
        } while (unprocessedPacketsCount > 0)
    }

    companion object {

        fun <TPacket> createSimplePump(
            packetProcessor1: PacketProcessor<TPacket>,
            packetProcessor2: PacketProcessor<TPacket>,
        ) =
            ControllablePacketPump(packetProcessor1, packetProcessor2)
    }
}
