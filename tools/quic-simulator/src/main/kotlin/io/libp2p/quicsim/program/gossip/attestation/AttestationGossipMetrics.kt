package io.libp2p.quicsim.program.gossip.attestation

import io.libp2p.quicsim.scenario.QuicScenarioEvent
import io.libp2p.quicsim.sim.SimNodeId
import kotlin.time.Duration

object AttestationGossipMetrics {
    data class AggregatePublication(
        val publishedAt: Duration,
        val publishingNodeId: SimNodeId,
        val aggregatorId: String,
        val slot: Long,
        val attestationPercent: Double,
        val ruleId: String,
    )

    data class AggregateReceipt(
        val receivedAt: Duration,
        val receivingNodeId: SimNodeId,
        val publishingNodeId: SimNodeId,
        val aggregatorId: String,
        val slot: Long,
        val attestationPercent: Double,
        val ruleId: String,
    )

    fun aggregatePublications(events: List<QuicScenarioEvent>): List<AggregatePublication> =
        events.filterIsInstance<QuicScenarioEvent.AttestationAggregatePublished>()
            .map {
                AggregatePublication(
                    publishedAt = it.at,
                    publishingNodeId = it.nodeId,
                    aggregatorId = it.aggregatorId,
                    slot = it.slot,
                    attestationPercent = it.attestationPercent,
                    ruleId = it.ruleId,
                )
            }
            .sortedWith(compareBy({ it.publishedAt }, { it.publishingNodeId }, { it.aggregatorId }, { it.slot }))

    fun aggregateReceipts(events: List<QuicScenarioEvent>): List<AggregateReceipt> =
        events.filterIsInstance<QuicScenarioEvent.AttestationAggregateReceived>()
            .map {
                AggregateReceipt(
                    receivedAt = it.at,
                    receivingNodeId = it.nodeId,
                    publishingNodeId = it.publisherNodeId,
                    aggregatorId = it.aggregatorId,
                    slot = it.slot,
                    attestationPercent = it.attestationPercent,
                    ruleId = it.ruleId,
                )
            }
            .sortedWith(
                compareBy(
                    { it.receivedAt },
                    { it.receivingNodeId },
                    { it.publishingNodeId },
                    { it.aggregatorId },
                    { it.slot }
                )
            )
}
