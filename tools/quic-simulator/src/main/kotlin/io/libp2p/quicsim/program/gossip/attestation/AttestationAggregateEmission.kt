package io.libp2p.quicsim.program.gossip.attestation

import kotlin.time.Duration

data class AttestationAggregateEmission(
    val aggregatorId: String,
    val slot: Long,
    val emittedAt: Duration,
    val timeIntoSlot: Duration,
    val attestationPercent: Double,
    val ruleId: String,
)
