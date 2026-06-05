package io.libp2p.quicsim.program.gossip.attestation

import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

data class AttestationGossipNodeConfig(
    val topicName: String = "/quicsim/attestation-aggregates",
    val slotDuration: Duration,
    val slotCount: Int = 1,
    val firstSlot: Long = 0,
    val firstSlotDelay: Duration = ZERO,
    val aggregators: List<AttestationAggregatorConfig> = emptyList(),
    val aggregateMessageSizeBytes: Int = 180,
) {
    init {
        require(topicName.isNotBlank()) { "topicName must not be blank" }
        require(slotDuration > ZERO) { "slotDuration must be positive" }
        require(slotCount > 0) { "slotCount must be positive" }
        require(firstSlot >= 0) { "firstSlot must be non-negative" }
        require(!firstSlotDelay.isNegative()) { "firstSlotDelay must be non-negative" }
        require(aggregateMessageSizeBytes > 0) { "aggregateMessageSizeBytes must be positive" }
        require(aggregators.map { it.aggregatorId }.toSet().size == aggregators.size) {
            "aggregator ids must be unique within a node"
        }
    }
}
