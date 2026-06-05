package io.libp2p.quicsim.program.gossip.attestation

import io.libp2p.quicsim.core.schedule.SimpleScheduler
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

class AttestationAggregator(
    private val config: AttestationAggregatorConfig,
    private val slotCount: Int,
    private val slotDuration: Duration,
    private val firstSlot: Long = 0,
    private val firstSlotDelay: Duration = ZERO,
    private val scheduler: SimpleScheduler,
    private val now: () -> Duration,
    private val publishAggregate: (AttestationAggregateEmission) -> Unit,
) {
    private val slotStates = mutableMapOf<Long, SlotState>()

    init {
        require(slotCount > 0) { "slotCount must be positive" }
        require(slotDuration > ZERO) { "slotDuration must be positive" }
        require(!firstSlotDelay.isNegative()) { "firstSlotDelay must be non-negative" }
    }

    fun schedule() {
        repeat(slotCount) { slotIndex ->
            scheduleSlot(firstSlot + slotIndex, slotStart(slotIndex))
        }
    }

    private fun scheduleSlot(slot: Long, slotStart: Duration) {
        val state = slotStates.computeIfAbsent(slot) { SlotState(slot) }
        val arrivalsByTime = config.distribution.arrivals(slot)
            .groupBy { it.timeIntoSlot }
            .mapValues { (_, buckets) -> buckets.sumOf { it.attestationPercent } }

        arrivalsByTime.entries
            .sortedBy { it.key }
            .forEach { (timeIntoSlot, percent) ->
                scheduleAt(slotStart + timeIntoSlot) {
                    state.attestationPercent = (state.attestationPercent + percent).coerceAtMost(MAX_PERCENT)
                    maybeEmitAfterAttestation(state, timeIntoSlot)
                }
            }

        scheduleAt(slotStart + config.rule.timeIntoSlot) {
            emitIfNeeded(state, config.rule.timeIntoSlot, config.rule)
        }
    }

    private fun maybeEmitAfterAttestation(state: SlotState, timeIntoSlot: Duration) {
        val aggregationState = state.toAggregationState(timeIntoSlot)
        if (config.rule.shouldEmitAfterAttestation(aggregationState)) {
            emitIfNeeded(state, timeIntoSlot, config.rule)
        }
    }

    private fun emitIfNeeded(
        state: SlotState,
        timeIntoSlot: Duration,
        rule: AggregatePublishRule,
    ) {
        if (state.emitted) return

        state.emitted = true
        publishAggregate(
            AttestationAggregateEmission(
                aggregatorId = config.aggregatorId,
                slot = state.slot,
                emittedAt = now(),
                timeIntoSlot = timeIntoSlot,
                attestationPercent = state.attestationPercent,
                ruleId = rule.id,
            )
        )
    }

    private fun scheduleAt(at: Duration, task: () -> Unit) {
        val delay = (at - now()).coerceAtLeast(ZERO)
        scheduler.executeAfterDelay(delay, Runnable { task() })
    }

    private fun slotStart(slotIndex: Int): Duration =
        firstSlotDelay + slotDuration * slotIndex

    private fun SlotState.toAggregationState(timeIntoSlot: Duration): AttestationAggregationState =
        AttestationAggregationState(
            aggregatorId = config.aggregatorId,
            slot = slot,
            timeIntoSlot = timeIntoSlot,
            attestationPercent = attestationPercent,
        )

    private data class SlotState(
        val slot: Long,
        var attestationPercent: Double = 0.0,
        var emitted: Boolean = false,
    )

    private companion object {
        const val MAX_PERCENT = 100.0
    }
}
