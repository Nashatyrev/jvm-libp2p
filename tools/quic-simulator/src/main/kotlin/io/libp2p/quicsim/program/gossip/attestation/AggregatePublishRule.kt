package io.libp2p.quicsim.program.gossip.attestation

import kotlin.time.Duration

data class AttestationAggregationState(
    val aggregatorId: String,
    val slot: Long,
    val timeIntoSlot: Duration,
    val attestationPercent: Double,
)

data class AggregatePublishRule(
    val thresholdPercent: Double,
    val timeIntoSlot: Duration,
    val id: String = "threshold-$thresholdPercent-percent-or-time-${timeIntoSlot.inWholeNanoseconds}ns",
) {
    init {
        require(thresholdPercent > 0.0 && thresholdPercent <= 100.0) {
            "thresholdPercent must be in (0, 100]"
        }
        require(!timeIntoSlot.isNegative()) { "timeIntoSlot must be non-negative" }
        require(id.isValidAggregateField()) { "id must not be blank or contain tabs/new lines" }
    }

    fun shouldEmitAfterAttestation(state: AttestationAggregationState): Boolean =
        state.attestationPercent >= thresholdPercent
}
