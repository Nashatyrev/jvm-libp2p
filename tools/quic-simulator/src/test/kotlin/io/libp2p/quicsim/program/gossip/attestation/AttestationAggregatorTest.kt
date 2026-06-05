package io.libp2p.quicsim.program.gossip.attestation

import io.libp2p.quicsim.core.schedule.DeterministicScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AttestationAggregatorTest {
    @Test
    fun `percent threshold emits when reached`() {
        val scheduler = DeterministicScheduler()
        val epoch = scheduler.time()
        val emissions = mutableListOf<AttestationAggregateEmission>()

        AttestationAggregator(
            config = AttestationAggregatorConfig(
                aggregatorId = "committee-0",
                distribution = DiscreteAttestationArrivalDistribution(
                    listOf(
                        AttestationArrivalBucket(100.milliseconds, 30.0),
                        AttestationArrivalBucket(200.milliseconds, 40.0),
                    )
                ),
                rule = PercentThresholdAggregateRule(60.0, id = "sixty-percent"),
            ),
            slotCount = 1,
            slotDuration = 12.seconds,
            scheduler = scheduler,
            now = { scheduler.time() - epoch },
            publishAggregate = emissions::add,
        ).schedule()

        scheduler.advanceAndExecuteAll(100.milliseconds)
        assertTrue(emissions.isEmpty())

        scheduler.advanceAndExecuteAll(100.milliseconds)
        assertEquals(1, emissions.size)
        assertEquals("sixty-percent", emissions.single().ruleId)
        assertEquals(70.0, emissions.single().attestationPercent)
        assertEquals(200.milliseconds, emissions.single().timeIntoSlot)

        scheduler.advanceAndExecuteAll(10.seconds)
        assertEquals(1, emissions.size)
    }

    @Test
    fun `fixed time emits aggregate with current percent`() {
        val scheduler = DeterministicScheduler()
        val epoch = scheduler.time()
        val emissions = mutableListOf<AttestationAggregateEmission>()

        AttestationAggregator(
            config = AttestationAggregatorConfig(
                aggregatorId = "committee-1",
                distribution = DiscreteAttestationArrivalDistribution(
                    listOf(
                        AttestationArrivalBucket(100.milliseconds, 20.0),
                        AttestationArrivalBucket(400.milliseconds, 80.0),
                    )
                ),
                rule = FixedTimeIntoSlotAggregateRule(250.milliseconds, id = "fixed"),
            ),
            slotCount = 1,
            slotDuration = 12.seconds,
            scheduler = scheduler,
            now = { scheduler.time() - epoch },
            publishAggregate = emissions::add,
        ).schedule()

        scheduler.advanceAndExecuteAll(100.milliseconds)
        scheduler.advanceAndExecuteAll(150.milliseconds)

        assertEquals(1, emissions.size)
        assertEquals("fixed", emissions.single().ruleId)
        assertEquals(20.0, emissions.single().attestationPercent)
        assertEquals(250.milliseconds, emissions.single().timeIntoSlot)

        scheduler.advanceAndExecuteAll(150.milliseconds)
        assertEquals(1, emissions.size)
    }

    @Test
    fun `same-time discrete buckets are applied before threshold check`() {
        val scheduler = DeterministicScheduler()
        val epoch = scheduler.time()
        val emissions = mutableListOf<AttestationAggregateEmission>()

        AttestationAggregator(
            config = AttestationAggregatorConfig(
                aggregatorId = "committee-2",
                distribution = DiscreteAttestationArrivalDistribution(
                    listOf(
                        AttestationArrivalBucket(100.milliseconds, 25.0),
                        AttestationArrivalBucket(100.milliseconds, 75.0),
                    )
                ),
                rule = PercentThresholdAggregateRule(25.0, id = "quarter"),
            ),
            slotCount = 1,
            slotDuration = 12.seconds,
            scheduler = scheduler,
            now = { scheduler.time() - epoch },
            publishAggregate = emissions::add,
        ).schedule()

        scheduler.advanceAndExecuteAll(100.milliseconds)

        assertEquals(1, emissions.size)
        assertEquals("quarter", emissions.single().ruleId)
        assertEquals(100.0, emissions.single().attestationPercent)
    }

    @Test
    fun `chart shaped real slot distribution emits near second burst`() {
        val scheduler = DeterministicScheduler()
        val epoch = scheduler.time()
        val emissions = mutableListOf<AttestationAggregateEmission>()
        val thresholdPercent = 90.0

        AttestationAggregator(
            config = AttestationAggregatorConfig(
                aggregatorId = "committee-chart",
                distribution = DiscreteAttestationArrivalDistribution(realSlotDistributionBuckets()),
                rule = PercentThresholdAggregateRule(thresholdPercent, id = "ninety-percent"),
            ),
            slotCount = 1,
            slotDuration = 12.seconds,
            scheduler = scheduler,
            now = { scheduler.time() - epoch },
            publishAggregate = emissions::add,
        ).schedule()

        scheduler.advanceAndExecuteAll(1400.milliseconds)
        assertTrue(emissions.isEmpty())

        while (emissions.isEmpty()) {
            val nextTaskDuration = scheduler.nextTaskDuration()
                ?: error("Expected chart-shaped distribution to reach $thresholdPercent%")
            scheduler.advanceAndExecuteAll(nextTaskDuration)
        }

        assertEquals(1, emissions.size)
        assertEquals("ninety-percent", emissions.single().ruleId)
        assertEquals(4200.milliseconds, emissions.single().timeIntoSlot)
        assertTrue(emissions.single().attestationPercent >= thresholdPercent)
        assertTrue(emissions.single().attestationPercent < 95.0)
    }

    @Test
    fun `attestations may arrive after slot end`() {
        val scheduler = DeterministicScheduler()
        val epoch = scheduler.time()
        val emissions = mutableListOf<AttestationAggregateEmission>()

        AttestationAggregator(
            config = AttestationAggregatorConfig(
                aggregatorId = "committee-3",
                distribution = DiscreteAttestationArrivalDistribution(
                    listOf(
                        AttestationArrivalBucket(1500.milliseconds, 100.0),
                    )
                ),
                rule = PercentThresholdAggregateRule(100.0, id = "complete"),
            ),
            slotCount = 1,
            slotDuration = 1.seconds,
            scheduler = scheduler,
            now = { scheduler.time() - epoch },
            publishAggregate = emissions::add,
        ).schedule()

        scheduler.advanceAndExecuteAll(1.seconds)
        assertTrue(emissions.isEmpty())

        scheduler.advanceAndExecuteAll(500.milliseconds)

        assertEquals(1, emissions.size)
        assertEquals(0, emissions.single().slot)
        assertEquals(1500.milliseconds, emissions.single().emittedAt)
        assertEquals(1500.milliseconds, emissions.single().timeIntoSlot)
        assertEquals(100.0, emissions.single().attestationPercent)
    }

    @Test
    fun `fixed publish time may be after slot end`() {
        val scheduler = DeterministicScheduler()
        val epoch = scheduler.time()
        val emissions = mutableListOf<AttestationAggregateEmission>()

        AttestationAggregator(
            config = AttestationAggregatorConfig(
                aggregatorId = "committee-4",
                distribution = DiscreteAttestationArrivalDistribution(
                    listOf(
                        AttestationArrivalBucket(100.milliseconds, 25.0),
                    )
                ),
                rule = FixedTimeIntoSlotAggregateRule(1500.milliseconds, id = "late-fixed"),
            ),
            slotCount = 1,
            slotDuration = 1.seconds,
            scheduler = scheduler,
            now = { scheduler.time() - epoch },
            publishAggregate = emissions::add,
        ).schedule()

        scheduler.advanceAndExecuteAll(100.milliseconds)
        assertTrue(emissions.isEmpty())

        scheduler.advanceAndExecuteAll(900.milliseconds)
        assertTrue(emissions.isEmpty())

        scheduler.advanceAndExecuteAll(500.milliseconds)

        assertEquals(1, emissions.size)
        assertEquals("late-fixed", emissions.single().ruleId)
        assertEquals(1500.milliseconds, emissions.single().emittedAt)
        assertEquals(1500.milliseconds, emissions.single().timeIntoSlot)
        assertEquals(25.0, emissions.single().attestationPercent)
    }

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
