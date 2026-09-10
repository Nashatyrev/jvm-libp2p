package io.libp2p.example.dc

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit-level tests of the bucketing itself ([DcSlotTrafficProfile.of]), plus one end-to-end run
 * confirming the report is reachable from a real scenario. [DcAttestationScenarioTest] and
 * [DcBlockScenarioTest] already exercise real runs at scale; what is worth checking in isolation
 * here is where a delivery lands and how it is normalized, which a full run would only show
 * indirectly.
 */
class DcSlotTrafficProfileTest {

    private fun delivery(latencyMs: Int, slotIndex: Int = 0) =
        DcSlotMessageDelivery(messageId = 0, slotIndex = slotIndex, receiverNodeId = 0, latency = latencyMs.milliseconds)

    @Test
    fun `a delivery lands in the bucket its publishOffset plus latency falls into`() {
        val config = DcSlotMessageConfig(DcSlotMessageType.BLOCK, sizeBytes = 1000, publishOffset = 2.seconds)
        val profile = DcSlotTrafficProfile.of(
            deliveriesByType = mapOf(DcSlotMessageType.BLOCK to listOf(delivery(latencyMs = 300))),
            configsByType = mapOf(DcSlotMessageType.BLOCK to config),
            slotDuration = 12.seconds,
            nodeCount = 1,
            slotsMeasured = 1
        )

        // offset 2000ms + latency 300ms = 2300ms -> bucket 23 of [2300, 2400)
        assertThat(profile.bucketCount).isEqualTo(120)
        val bytes = profile.averageBytesPerNode.getValue(DcSlotMessageType.BLOCK)
        assertThat(bytes[23]).isEqualTo(1000.0)
        assertThat(bytes.filterIndexed { index, _ -> index != 23 }).allMatch { it == 0.0 }
    }

    @Test
    fun `values are bytes per node per slot, not a raw sum`() {
        val config = DcSlotMessageConfig(DcSlotMessageType.PAYLOAD, sizeBytes = 100)
        // 2 nodes each deliver in slot 0, 1 node delivers again in slot 1: 3 deliveries over 2 slots.
        val profile = DcSlotTrafficProfile.of(
            deliveriesByType = mapOf(
                DcSlotMessageType.PAYLOAD to listOf(
                    delivery(latencyMs = 10, slotIndex = 0).copy(receiverNodeId = 0),
                    delivery(latencyMs = 10, slotIndex = 0).copy(receiverNodeId = 1),
                    delivery(latencyMs = 10, slotIndex = 1).copy(receiverNodeId = 0)
                )
            ),
            configsByType = mapOf(DcSlotMessageType.PAYLOAD to config),
            slotDuration = 12.seconds,
            nodeCount = 2,
            slotsMeasured = 2
        )

        // 300 bytes total / 2 nodes / 2 slots = 75 bytes/node/slot, all landing in bucket 0.
        assertThat(profile.averageBytesPerNode.getValue(DcSlotMessageType.PAYLOAD)[0]).isEqualTo(75.0)
    }

    @Test
    fun `a delivery beyond the slot duration is counted as overflow rather than dropped silently`() {
        val config = DcSlotMessageConfig(DcSlotMessageType.BLOCK, sizeBytes = 1000)
        val profile = DcSlotTrafficProfile.of(
            deliveriesByType = mapOf(
                DcSlotMessageType.BLOCK to listOf(delivery(latencyMs = 20_000))
            ),
            configsByType = mapOf(DcSlotMessageType.BLOCK to config),
            slotDuration = 12.seconds,
            nodeCount = 1,
            slotsMeasured = 1
        )

        assertThat(profile.overflowDeliveries.getValue(DcSlotMessageType.BLOCK)).isEqualTo(1)
        assertThat(profile.averageBytesPerNode.getValue(DcSlotMessageType.BLOCK)).allMatch { it == 0.0 }
    }

    @Test
    fun `warmup slots are excluded the same way as everywhere else in the report`() {
        val config = DcSlotMessageConfig(DcSlotMessageType.BLOCK, sizeBytes = 1000)
        val profile = DcSlotTrafficProfile.of(
            deliveriesByType = mapOf(
                DcSlotMessageType.BLOCK to listOf(
                    delivery(latencyMs = 10, slotIndex = 0),
                    delivery(latencyMs = 10, slotIndex = 1)
                )
            ),
            configsByType = mapOf(DcSlotMessageType.BLOCK to config),
            slotDuration = 12.seconds,
            nodeCount = 1,
            slotsMeasured = 1,
            warmupWaves = 1
        )

        // Only slot 1's delivery counts; slot 0 is warm-up.
        assertThat(profile.averageBytesPerNode.getValue(DcSlotMessageType.BLOCK)[0]).isEqualTo(1000.0)
    }

    @Test
    fun `a type with no deliveries still gets an all-zero column`() {
        val profile = DcSlotTrafficProfile.of(
            deliveriesByType = emptyMap(),
            configsByType = mapOf(DcSlotMessageType.BLOCK to DcSlotMessageConfig(DcSlotMessageType.BLOCK, sizeBytes = 1000)),
            slotDuration = 1.seconds,
            nodeCount = 1,
            slotsMeasured = 1,
            bucketDuration = 100.milliseconds
        )

        assertThat(profile.bucketCount).isEqualTo(10)
        assertThat(profile.averageBytesPerNode.getValue(DcSlotMessageType.BLOCK)).allMatch { it == 0.0 }
        assertThat(profile.overflowDeliveries.getValue(DcSlotMessageType.BLOCK)).isZero()
    }

    @Test
    fun `bucket count rounds up when the slot does not divide evenly`() {
        val profile = DcSlotTrafficProfile.of(
            deliveriesByType = emptyMap(),
            configsByType = mapOf(DcSlotMessageType.BLOCK to DcSlotMessageConfig(DcSlotMessageType.BLOCK, sizeBytes = 1000)),
            slotDuration = 250.milliseconds,
            nodeCount = 1,
            slotsMeasured = 1,
            bucketDuration = 100.milliseconds
        )

        // 250ms / 100ms = 2.5 -> 3 buckets, the last one a partial [200, 250) window.
        assertThat(profile.bucketCount).isEqualTo(3)
    }

    @Test
    fun `the table prints one row per bucket and one column per message type`() {
        val config = DcSlotMessageConfig(DcSlotMessageType.BLOCK, sizeBytes = 1000, publishOffset = 200.milliseconds)
        val profile = DcSlotTrafficProfile.of(
            deliveriesByType = mapOf(DcSlotMessageType.BLOCK to listOf(delivery(latencyMs = 0))),
            configsByType = mapOf(DcSlotMessageType.BLOCK to config),
            slotDuration = 500.milliseconds,
            nodeCount = 1,
            slotsMeasured = 1,
            bucketDuration = 100.milliseconds
        )

        val text = profile.toString()
        assertThat(text).contains("block")
        assertThat(text.lines().filter { it.isNotBlank() }).hasSize(2 + profile.bucketCount)
        assertThat(text).contains("200")
        assertThat(text).contains("1000.0")
    }

    @Test
    fun `an empty profile prints nothing`() {
        assertThat(DcSlotTrafficProfile(100.milliseconds, 1.seconds, 1, 1, emptyMap(), emptyMap()).toString())
            .isEmpty()
    }

    @Test
    fun `rejects a non-positive bucket or slot duration`() {
        val config = mapOf(DcSlotMessageType.BLOCK to DcSlotMessageConfig(DcSlotMessageType.BLOCK, sizeBytes = 1000))
        assertThatThrownBy {
            DcSlotTrafficProfile.of(emptyMap(), config, slotDuration = 1.seconds, nodeCount = 1, slotsMeasured = 1, bucketDuration = 0.milliseconds)
        }.hasMessageContaining("bucketDuration must be > 0")
        assertThatThrownBy {
            DcSlotTrafficProfile.of(emptyMap(), config, slotDuration = 0.milliseconds, nodeCount = 1, slotsMeasured = 1)
        }.hasMessageContaining("slotDuration must be > 0")
    }

    @Test
    fun `a full run reports a slot traffic profile with every configured message type`() {
        val network = DcNetworkBuilder.world(randomSeed = 1, subnetCount = 2)
            .addGroup(count = 12) {
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 1
                peers = 5
                randomSubnets(count = 1, of = 2)
            }
            .build()
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcAttestationConfig(
            waveCount = 2,
            attestersPerWave = 4,
            waveInterval = 12.seconds,
            settle = 12.seconds,
            blocks = DcBlockConfig(sizeBytes = 8 * 1024, publishOffset = 2.seconds),
            randomSeed = 7
        )

        val report = DcAttestationScenario.run(network, graph, config)

        val profile = requireNotNull(report.slotTraffic)
        assertThat(profile.bucketCount).isEqualTo(120)
        assertThat(profile.averageBytesPerNode.keys)
            .containsExactlyInAnyOrder(DcSlotMessageType.FFG_ATTESTATION, DcSlotMessageType.BLOCK)
        assertThat(profile.averageBytesPerNode.getValue(DcSlotMessageType.BLOCK).sum())
            .describedAs("total bytes/node/slot should be in the right ballpark of one block's size")
            .isGreaterThan(0.0)
        assertThat(profile.overflowDeliveries.values).allMatch { it == 0 }
    }

    @Test
    fun `slotTrafficBucketDuration configures the resolution`() {
        val config = DcAttestationConfig(waveInterval = 4.seconds, slotTrafficBucketDuration = 500.milliseconds)
        assertThat(config.slotTrafficBucketDuration).isEqualTo(500.milliseconds)
    }

    @Test
    fun `a non-positive slotTrafficBucketDuration is rejected`() {
        assertThatThrownBy { DcAttestationConfig(slotTrafficBucketDuration = Duration.ZERO) }
            .hasMessageContaining("slotTrafficBucketDuration must be > 0")
    }
}
