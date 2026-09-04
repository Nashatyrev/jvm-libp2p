package io.libp2p.example.dc

import io.libp2p.quicsim.runner.DatagramPacketTraceEvent
import io.libp2p.quicsim.runner.DatagramPacketTraceEvent.Direction.INBOUND
import io.libp2p.quicsim.runner.DatagramPacketTraceEvent.Direction.OUTBOUND
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

    private fun packet(direction: DatagramPacketTraceEvent.Direction, nodeId: Int, atMs: Int, bytes: Int) =
        DatagramPacketTraceEvent(
            direction = direction,
            nodeId = nodeId,
            at = atMs.milliseconds,
            localHost = "127.0.0.1",
            localPort = 1000,
            remoteHost = "127.0.0.1",
            remotePort = 2000,
            bytes = bytes,
            payloadSha256 = "deadbeef"
        )

    private fun noTraffic(nodeCount: Int = 1) = DcTrafficReport(
        overall = DcTrafficStats(nodeCount, packetsSent = 0, packetsReceived = 0, bytesSent = 0, bytesReceived = 0),
        perWave = emptyMap()
    )

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

        val report = DcAttestationReport.of(published, deliveries, expectedDeliveriesOf = { 2 }, traffic = noTraffic())

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

    @Test
    fun `traffic stats average packets and bytes per node`() {
        val events = listOf(
            packet(OUTBOUND, nodeId = 0, atMs = 0, bytes = 100),
            packet(OUTBOUND, nodeId = 1, atMs = 0, bytes = 200),
            packet(INBOUND, nodeId = 2, atMs = 0, bytes = 50)
        )

        val stats = DcTrafficStats.of(events, nodeCount = 4)

        assertThat(stats.packetsSent).isEqualTo(2)
        assertThat(stats.packetsReceived).isEqualTo(1)
        assertThat(stats.bytesSent).isEqualTo(300)
        assertThat(stats.bytesReceived).isEqualTo(50)
        assertThat(stats.avgPacketsSentPerNode).isEqualTo(0.5)
        assertThat(stats.avgPacketsReceivedPerNode).isEqualTo(0.25)
        assertThat(stats.avgBytesSentPerNode).isEqualTo(75.0)
        assertThat(stats.avgBytesReceivedPerNode).isEqualTo(12.5)
    }

    @Test
    fun `traffic stats of no events are all zero`() {
        val stats = DcTrafficStats.of(emptyList(), nodeCount = 5)

        assertThat(stats.packetsSent).isZero()
        assertThat(stats.bytesSent).isZero()
        assertThat(stats.avgPacketsSentPerNode).isZero()
    }

    @Test
    fun `traffic report buckets events into waves by time, overall spans the whole run`() {
        val waveTimes = listOf(100.milliseconds, 200.milliseconds)
        val completeAt = 250.milliseconds
        val events = listOf(
            packet(OUTBOUND, nodeId = 0, atMs = 50, bytes = 10), // warmup: before wave 0, not in any wave
            packet(OUTBOUND, nodeId = 0, atMs = 100, bytes = 20), // wave 0
            packet(OUTBOUND, nodeId = 0, atMs = 150, bytes = 30), // wave 0
            packet(OUTBOUND, nodeId = 0, atMs = 200, bytes = 40), // wave 1
            packet(OUTBOUND, nodeId = 0, atMs = 240, bytes = 50) // wave 1
        )

        val report = DcTrafficReport.of(events, waveTimes, completeAt, nodeCount = 1)

        // overall covers everything, including the warmup packet
        assertThat(report.overall.bytesSent).isEqualTo(150)
        assertThat(report.overall.packetsSent).isEqualTo(5)
        assertThat(report.perWave.keys).containsExactly(0, 1)
        assertThat(report.perWave.getValue(0).bytesSent).isEqualTo(50)
        assertThat(report.perWave.getValue(1).bytesSent).isEqualTo(90)
    }
}
