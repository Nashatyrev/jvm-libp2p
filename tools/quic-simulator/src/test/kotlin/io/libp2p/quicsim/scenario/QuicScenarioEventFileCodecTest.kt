package io.libp2p.quicsim.scenario

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class QuicScenarioEventFileCodecTest {
    @Test
    fun `round trips attestation aggregate events`() {
        val events = listOf(
            QuicScenarioEvent.GossipMessagePublished(
                nodeId = 1,
                at = 100.milliseconds,
                messageIndex = 3
            ),
            QuicScenarioEvent.GossipMessageReceived(
                nodeId = 2,
                at = 110.milliseconds,
                publisherNodeId = 1,
                messageIndex = 3
            ),
            QuicScenarioEvent.GossipSymbolsRecovered(
                nodeId = 2,
                at = 115.milliseconds,
                waveIndex = 1,
                receivedSymbolCount = 64,
                republishedSymbolCount = 64
            ),
            QuicScenarioEvent.AttestationAggregatePublished(
                nodeId = 1,
                at = 120.milliseconds,
                aggregatorId = "committee-0",
                slot = 3,
                attestationPercent = 50.0,
                ruleId = "half",
            ),
            QuicScenarioEvent.AttestationAggregateReceived(
                nodeId = 2,
                at = 150.milliseconds,
                publisherNodeId = 1,
                aggregatorId = "committee-0",
                slot = 3,
                attestationPercent = 50.0,
                ruleId = "half",
            ),
        )

        val decoded = events.map { QuicScenarioEventFileCodec.decode(QuicScenarioEventFileCodec.encode(it)) }

        assertEquals(events, decoded)
    }
}
