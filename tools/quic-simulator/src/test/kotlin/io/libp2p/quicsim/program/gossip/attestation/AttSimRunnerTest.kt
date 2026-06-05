package io.libp2p.quicsim.program.gossip.attestation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class AttSimRunnerTest {
    @Test
    fun `reports max aggregate percent by slot and next slot end`() {
        val result = AttSimRunner().run(
            AttSimParams(
                slotDuration = 12.seconds,
                attestDistribution = DiscreteAttestationArrivalDistribution(
                    listOf(
                        AttestationArrivalBucket(4.seconds, 40.0),
                        AttestationArrivalBucket(10.seconds, 40.0),
                        AttestationArrivalBucket(14.seconds, 20.0),
                    )
                ),
                aggregateRules = listOf(
                    AggregatePublishRule(
                        thresholdPercent = 80.0,
                        timeIntoSlot = 11.seconds,
                        id = "eighty-before-slot-end",
                    ),
                    AggregatePublishRule(
                        thresholdPercent = 100.0,
                        timeIntoSlot = 18.seconds,
                        id = "complete-before-next-slot-end",
                    ),
                ),
            )
        )

        assertEquals(80.0, result.maxAggregatePercentTillSlotEnd)
        assertEquals("eighty-before-slot-end", result.bestAggregateTillSlotEnd?.ruleId)
        assertEquals(10.seconds, result.bestAggregateTillSlotEnd?.emittedAt)

        assertEquals(100.0, result.maxAggregatePercentTillNextSlotEnd)
        assertEquals("complete-before-next-slot-end", result.bestAggregateTillNextSlotEnd?.ruleId)
        assertEquals(14.seconds, result.bestAggregateTillNextSlotEnd?.emittedAt)
        assertEquals(2, result.aggregateEmissions.size)
    }
}
