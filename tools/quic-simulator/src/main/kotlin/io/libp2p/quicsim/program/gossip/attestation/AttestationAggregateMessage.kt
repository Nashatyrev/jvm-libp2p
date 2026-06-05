package io.libp2p.quicsim.program.gossip.attestation

import io.libp2p.quicsim.sim.SimNodeId
import kotlin.time.Duration

data class AttestationAggregateKey(
    val publisherNodeId: SimNodeId,
    val aggregatorId: String,
    val slot: Long,
)

data class AttestationAggregateMessage(
    val publisherNodeId: SimNodeId,
    val aggregatorId: String,
    val slot: Long,
    val attestationPercent: Double,
    val emittedAt: Duration,
    val ruleId: String,
) {
    val key: AttestationAggregateKey
        get() = AttestationAggregateKey(publisherNodeId, aggregatorId, slot)

    init {
        require(publisherNodeId >= 0) { "publisherNodeId must be non-negative" }
        require(aggregatorId.isValidAggregateField()) {
            "aggregatorId must not be blank or contain tabs/new lines"
        }
        require(slot >= 0) { "slot must be non-negative" }
        require(attestationPercent.isValidPercent()) { "attestationPercent must be in [0, 100]" }
        require(!emittedAt.isNegative()) { "emittedAt must be non-negative" }
        require(ruleId.isValidAggregateField()) { "ruleId must not be blank or contain tabs/new lines" }
    }
}

internal fun String.isValidAggregateField(): Boolean =
    isNotBlank() && none { it == '\t' || it == '\n' || it == '\r' }

internal fun Double.isValidPercent(): Boolean =
    !isNaN() && !isInfinite() && this >= 0.0 && this <= 100.0
