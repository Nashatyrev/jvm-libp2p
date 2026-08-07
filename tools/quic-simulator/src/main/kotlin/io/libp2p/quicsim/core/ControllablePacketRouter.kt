package io.libp2p.quicsim.core

import io.libp2p.quicsim.core.schedule.AggregateControllable
import io.libp2p.quicsim.core.schedule.AggregateControllable2
import io.libp2p.quicsim.core.schedule.Controllable
import kotlin.time.Duration

typealias RouteId = Int

open class ControllablePacketRouter<TPacket>(
    private val routeProcessors: List<InOutProcessor<TPacket>>,
    private val routeSelector: (from: RouteId, packet: TPacket) -> RouteId,
) : Controllable {

    private val routeCount: RouteId = routeProcessors.size

    private val aggregateControllable = AggregateControllable2(routeProcessors)

    override fun advance(advanceDuration: Duration) {
        aggregateControllable.advance(advanceDuration)
    }

    override fun executePending() {
        aggregateControllable.executePending()
        pumpPackets()
    }

    override fun nextTaskDuration(): Duration? =
        aggregateControllable.nextTaskDuration()

    fun pumpPackets() {

        val inboundPackets = List(routeCount) { mutableListOf<TPacket>() }

        var unprocessedPacketsCount = 0
        do {

            for (i: RouteId in 0 until routeCount) {
                val outboundPackets = routeProcessors[i].deliver(inboundPackets[i])
                unprocessedPacketsCount -= inboundPackets[i].size
                outboundPackets.forEach { outboundPacket ->
                    val destinationRouteId = routeSelector(i, outboundPacket)
                    assert(destinationRouteId != i)
                    inboundPackets[destinationRouteId] += outboundPacket
                }
                unprocessedPacketsCount += outboundPackets.size
                inboundPackets[i].clear()
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
