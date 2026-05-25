package io.libp2p.quicsim.program

import io.libp2p.quicsim.scenario.QuicScenarioEvent
import io.libp2p.quicsim.sim.SimNodeId
import kotlin.time.Duration

object GossipMetrics {
    data class MessageReceipt(
        val receivedAt: Duration,
        val receivingNodeId: SimNodeId,
        val publishingNodeId: SimNodeId
    )

    fun messageReceipts(events: List<QuicScenarioEvent>): List<MessageReceipt> =
        events.filterIsInstance<QuicScenarioEvent.GossipMessageReceived>()
            .map {
                MessageReceipt(
                    receivedAt = it.at,
                    receivingNodeId = it.nodeId,
                    publishingNodeId = it.publisherNodeId
                )
            }
            .sortedWith(compareBy({ it.receivedAt }, { it.receivingNodeId }, { it.publishingNodeId }))
}
