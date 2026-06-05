package io.libp2p.quicsim.program.gossip.attestation

import io.libp2p.quicsim.core.schedule.DeterministicScheduler
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AttSimRunner {
    fun run(params: AttSimParams): AttSimResult {
        val scheduler = DeterministicScheduler()
        val epoch = scheduler.time()
        val emissions = mutableListOf<AttestationAggregateEmission>()

        params.aggregateRules.forEachIndexed { index, rule ->
            AttestationAggregator(
                config = AttestationAggregatorConfig(
                    aggregatorId = "aggregator-$index",
                    distribution = params.attestDistribution,
                    rule = rule,
                ),
                slotCount = 1,
                slotDuration = params.slotDuration,
                scheduler = scheduler,
                now = { scheduler.time() - epoch },
                publishAggregate = emissions::add,
            ).schedule()
        }

        val nextSlotEnd = params.slotDuration * 2
        while (true) {
            val nextTaskDuration = scheduler.nextTaskDuration() ?: break
            val nextTaskTime = scheduler.time() - epoch + nextTaskDuration
            if (nextTaskTime > nextSlotEnd) break

            scheduler.advanceAndExecuteAll(nextTaskDuration)
        }

        val aggregateEmissions = emissions
            .map {
                AttSimAggregateEmission(
                    aggregatorId = it.aggregatorId,
                    slot = it.slot,
                    ruleId = it.ruleId,
                    emittedAt = it.emittedAt,
                    timeIntoSlot = it.timeIntoSlot,
                    attestationPercent = it.attestationPercent,
                )
            }
            .sortedWith(compareBy({ it.emittedAt }, { it.aggregatorId }))

        val bestBySlotEnd = aggregateEmissions.bestBy(params.slotDuration)
        val bestByNextSlotEnd = aggregateEmissions.bestBy(nextSlotEnd)
        return AttSimResult(
            maxAggregatePercentTillSlotEnd = bestBySlotEnd?.attestationPercent ?: 0.0,
            maxAggregatePercentTillNextSlotEnd = bestByNextSlotEnd?.attestationPercent ?: 0.0,
            bestAggregateTillSlotEnd = bestBySlotEnd,
            bestAggregateTillNextSlotEnd = bestByNextSlotEnd,
            aggregateEmissions = aggregateEmissions,
        )
    }

    private fun List<AttSimAggregateEmission>.bestBy(deadline: Duration): AttSimAggregateEmission? =
        filter { it.emittedAt <= deadline }
            .maxWithOrNull(
                compareBy<AttSimAggregateEmission> { it.attestationPercent }
                    .thenByDescending { it.emittedAt }
                    .thenBy { it.aggregatorId }
            )

    private val aggregatorsCount = 16
    private val aggregateRulesCurrent = List(aggregatorsCount) {
        AggregatePublishRule(100.0, 8.seconds)
    }
    private val aggregateRulesNew = listOf(
        AggregatePublishRule(60.0, 3.seconds),
        AggregatePublishRule(70.0, 3.seconds + 500.milliseconds),
        AggregatePublishRule(80.0, 4.seconds + 200.milliseconds),
        AggregatePublishRule(85.0, 4.seconds + 400.milliseconds),
        AggregatePublishRule(90.0, 4.seconds + 600.milliseconds),
        AggregatePublishRule(93.0, 4.seconds + 800.milliseconds),
        AggregatePublishRule(95.0, 5.seconds),
        AggregatePublishRule(96.0, 6.seconds),
        AggregatePublishRule(97.0, 7.seconds),
        AggregatePublishRule(98.0, 8.seconds),
        AggregatePublishRule(98.5, 9.seconds),
        AggregatePublishRule(99.0, 10.seconds),
        AggregatePublishRule(99.5, 11.seconds),
        AggregatePublishRule(100.0, 12.seconds),
        AggregatePublishRule(100.0, 14.seconds),
        AggregatePublishRule(100.0, 18.seconds),
    )

    private fun realSlotDistributionBuckets(): List<AttestationArrivalBucket> {
        val countLikeWeights = listOf(
            1500 to 10.0,
            1600 to 250.0,
            1700 to 600.0,
            1800 to 900.0,
            1900 to 1200.0,
            2000 to 1300.0,
            2100 to 1150.0,
            2200 to 1050.0,
            2300 to 1000.0,
            2400 to 820.0,
            2500 to 500.0,
            2600 to 430.0,
            2700 to 360.0,
            2800 to 330.0,
            2900 to 260.0,
            3000 to 180.0,
            3100 to 140.0,
            3200 to 120.0,
            3300 to 90.0,
            3400 to 60.0,
            3500 to 40.0,
            3600 to 25.0,
            3700 to 45.0,
            3800 to 80.0,
            3900 to 100.0,
            4000 to 160.0,
            4100 to 550.0,
            4200 to 800.0,
            4300 to 260.0,
            4400 to 180.0,
            4500 to 130.0,
            4600 to 120.0,
            4700 to 90.0,
            4800 to 70.0,
            4900 to 55.0,
            5000 to 45.0,
            5100 to 40.0,
            5200 to 30.0,
            5300 to 25.0,
            5400 to 15.0,
            5500 to 10.0,
            6000 to 8.0,
            6500 to 5.0,
        )
        val totalWeight = countLikeWeights.sumOf { it.second }
        return countLikeWeights.map { (timeMillis, weight) ->
            AttestationArrivalBucket(timeMillis.milliseconds, weight * 100.0 / totalWeight)
        }
    }
}

data class AttSimParams(
    val slotDuration: Duration,
    val attestDistribution: AttestationArrivalDistribution,
    val aggregateRules: List<AggregatePublishRule>,
) {
    init {
        require(slotDuration > ZERO) { "slotDuration must be positive" }
        require(aggregateRules.isNotEmpty()) { "aggregateRules must not be empty" }
    }
}

data class AttSimResult(
    val maxAggregatePercentTillSlotEnd: Double,
    val maxAggregatePercentTillNextSlotEnd: Double,
    val bestAggregateTillSlotEnd: AttSimAggregateEmission?,
    val bestAggregateTillNextSlotEnd: AttSimAggregateEmission?,
    val aggregateEmissions: List<AttSimAggregateEmission>,
)

data class AttSimAggregateEmission(
    val aggregatorId: String,
    val slot: Long,
    val ruleId: String,
    val emittedAt: Duration,
    val timeIntoSlot: Duration,
    val attestationPercent: Double,
)
