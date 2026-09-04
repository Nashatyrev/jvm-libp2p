package io.libp2p.example.dc

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class DcAttestationStatsTest {

    private fun delivery(id: Int, receiver: Int, latencyMs: Int, wave: Int = 0) =
        DcDelivery(
            attestationId = id,
            waveIndex = wave,
            receiverNodeId = receiver,
            subnetId = 0,
            latency = latencyMs.milliseconds
        )

    private fun attestation(id: Int, wave: Int = 0) =
        DcAttestation(id = id, waveIndex = wave, attesterNodeId = 0, subnetId = 0)

    @Test
    fun `percentiles use nearest rank over the observed latencies`() {
        // 1..100 ms: p50 = 50th value, p95 = 95th, p99 = 99th
        val latencies = (1..100).map { it.milliseconds }
        assertThat(latencies.percentile(0.50)).isEqualTo(50.milliseconds)
        assertThat(latencies.percentile(0.95)).isEqualTo(95.milliseconds)
        assertThat(latencies.percentile(0.99)).isEqualTo(99.milliseconds)
        assertThat(latencies.percentile(1.0)).isEqualTo(100.milliseconds)
        assertThat(latencies.percentile(0.0)).isEqualTo(1.milliseconds)
    }

    @Test
    fun `percentiles of a single sample are that sample`() {
        val latencies = listOf(7.milliseconds)
        assertThat(latencies.percentile(0.50)).isEqualTo(7.milliseconds)
        assertThat(latencies.percentile(0.99)).isEqualTo(7.milliseconds)
    }

    @Test
    fun `percentiles of nothing are null`() {
        assertThat(emptyList<Duration>().percentile(0.5)).isNull()
    }

    @Test
    fun `reports percentiles and delivery ratio`() {
        val published = listOf(attestation(0))
        val deliveries = (1..10).map { delivery(id = 0, receiver = it, latencyMs = it * 10) }

        val stats = DcDeliveryStats.of(published, deliveries, expectedDeliveries = 10)

        assertThat(stats.publishedCount).isEqualTo(1)
        assertThat(stats.actualDeliveries).isEqualTo(10)
        assertThat(stats.p50).isEqualTo(50.milliseconds)
        assertThat(stats.p95).isEqualTo(100.milliseconds)
        assertThat(stats.p99).isEqualTo(100.milliseconds)
        assertThat(stats.min).isEqualTo(10.milliseconds)
        assertThat(stats.max).isEqualTo(100.milliseconds)
        assertThat(stats.mean).isEqualTo(55.milliseconds)
        assertThat(stats.deliveryRatio).isEqualTo(1.0)
        assertThat(stats.missingDeliveries).isZero()
    }

    @Test
    fun `counts deliveries that never arrived`() {
        val published = listOf(attestation(0))
        val deliveries = (1..8).map { delivery(id = 0, receiver = it, latencyMs = 20) }

        val stats = DcDeliveryStats.of(published, deliveries, expectedDeliveries = 10)

        assertThat(stats.actualDeliveries).isEqualTo(8)
        assertThat(stats.missingDeliveries).isEqualTo(2)
        assertThat(stats.deliveryRatio).isEqualTo(0.8)
        // percentiles only describe what arrived, which is exactly why the ratio is reported too
        assertThat(stats.p99).isEqualTo(20.milliseconds)
    }

    @Test
    fun `breaks statistics down per wave`() {
        val published = listOf(attestation(0, wave = 0), attestation(1, wave = 1))
        val deliveries = listOf(
            delivery(id = 0, receiver = 1, latencyMs = 10, wave = 0),
            delivery(id = 0, receiver = 2, latencyMs = 30, wave = 0),
            delivery(id = 1, receiver = 1, latencyMs = 100, wave = 1),
            delivery(id = 1, receiver = 2, latencyMs = 300, wave = 1)
        )

        val report = DcAttestationReport.of(published, deliveries) { 2 }

        assertThat(report.overall.actualDeliveries).isEqualTo(4)
        assertThat(report.overall.expectedDeliveries).isEqualTo(4)
        assertThat(report.perWave.keys).containsExactly(0, 1)
        assertThat(report.perWave.getValue(0).p50).isEqualTo(10.milliseconds)
        assertThat(report.perWave.getValue(1).p50).isEqualTo(100.milliseconds)
        assertThat(report.perWave.getValue(1).max).isEqualTo(300.milliseconds)
    }

    @Test
    fun `recorder keeps everything published and delivered`() {
        val recorder = DcAttestationRecorder()
        recorder.recordPublished(attestation(1))
        recorder.recordPublished(attestation(0))
        recorder.recordDelivered(delivery(id = 1, receiver = 5, latencyMs = 10))
        recorder.recordDelivered(delivery(id = 0, receiver = 4, latencyMs = 20))

        assertThat(recorder.published().map { it.id }).containsExactly(0, 1)
        assertThat(recorder.deliveries().map { it.attestationId }).containsExactly(0, 1)
    }
}
