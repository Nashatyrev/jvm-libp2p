package io.libp2p.quicsim.program.gossip.attestation

import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class AttestationAggregateMessageCodecTest {
    @Test
    fun `round trips padded aggregate message`() {
        val message = AttestationAggregateMessage(
            publisherNodeId = 12,
            aggregatorId = "committee-7",
            slot = 42,
            attestationPercent = 50.0,
            emittedAt = 1500.milliseconds,
            ruleId = "fifty-percent",
        )

        val encoded = AttestationAggregateMessageCodec.encode(message, messageSizeBytes = 180)

        assertEquals(message, AttestationAggregateMessageCodec.decode(Unpooled.wrappedBuffer(encoded)))
    }

    @Test
    fun `ignores unrelated payloads`() {
        val payload = "not-an-attestation-aggregate".toByteArray()

        assertNull(AttestationAggregateMessageCodec.decode(Unpooled.wrappedBuffer(payload)))
    }
}
