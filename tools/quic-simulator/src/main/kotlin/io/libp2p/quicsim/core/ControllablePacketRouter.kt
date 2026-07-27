package io.libp2p.quicsim.core

import io.libp2p.quicsim.core.schedule.AggregateControllable
import io.libp2p.quicsim.core.schedule.Controllable
import kotlin.time.Duration

typealias RouteId = Int

open class ControllablePacketRouter<TPacket>(
    private val routeProcessors: List<PacketProcessor<TPacket>>,
    private val routeSelector: (from: RouteId, packet: TPacket) -> RouteId,
) : Controllable {

    private val routeCount: RouteId = routeProcessors.size

    private val aggregateControllable = AggregateControllable(routeProcessors)

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
                    inboundPackets[destinationRouteId] += outboundPacket
                }
                unprocessedPacketsCount += outboundPackets.size
            }
        } while (unprocessedPacketsCount > 0)
    }

    companion object {

        fun <TPacket> createSimplePump(
            packetProcessor1: PacketProcessor<TPacket>,
            packetProcessor2: PacketProcessor<TPacket>,
        ) = ControllablePacketRouter(
            listOf(packetProcessor1, packetProcessor2),
            { from, _ -> 1 - from }
        )
    }
}
