package io.libp2p.quicsim.program.gossip.attestation

import kotlin.time.Duration

fun interface AttestationArrivalDistribution {
    fun arrivals(slot: Long): List<AttestationArrivalBucket>
}

data class AttestationArrivalBucket(
    val timeIntoSlot: Duration,
    val attestationPercent: Double,
) {
    init {
        require(!timeIntoSlot.isNegative()) { "timeIntoSlot must be non-negative" }
        require(attestationPercent > 0.0 && attestationPercent <= 100.0) {
            "attestationPercent must be in (0, 100]"
        }
    }
}
