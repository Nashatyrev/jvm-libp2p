package io.libp2p.quicsim.program.gossip.attestation

import kotlin.time.Duration

interface AggregatePublishRule {
    val id: String
    val fixedTimeIntoSlot: Duration?
        get() = null

    fun shouldEmitAfterAttestation(state: AttestationAggregationState): Boolean = false
}

data class AttestationAggregationState(
    val aggregatorId: String,
    val slot: Long,
    val timeIntoSlot: Duration,
    val attestationPercent: Double,
)

data class FixedTimeIntoSlotAggregateRule(
    val timeIntoSlot: Duration,
    override val id: String = "fixed-time-${timeIntoSlot.inWholeNanoseconds}ns",
) : AggregatePublishRule {
    init {
        require(!timeIntoSlot.isNegative()) { "timeIntoSlot must be non-negative" }
        require(id.isValidAggregateField()) { "id must not be blank or contain tabs/new lines" }
    }

    override val fixedTimeIntoSlot: Duration = timeIntoSlot
}

data class PercentThresholdAggregateRule(
    val thresholdPercent: Double,
    override val id: String = "threshold-$thresholdPercent-percent",
) : AggregatePublishRule {
    init {
        require(thresholdPercent > 0.0 && thresholdPercent <= 100.0) {
            "thresholdPercent must be in (0, 100]"
        }
        require(id.isValidAggregateField()) { "id must not be blank or contain tabs/new lines" }
    }

    override fun shouldEmitAfterAttestation(state: AttestationAggregationState): Boolean =
        state.attestationPercent >= thresholdPercent
}
