package io.libp2p.example.dc

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end attestation dissemination runs on the QUIC simulator.
 *
 * These run in virtual time, but the packet-level simulation is real work: keep node counts modest
 * unless you are deliberately running a large study.
 */
class DcAttestationScenarioTest {

    private fun population(nodeCount: Int, subnetCount: Int, subnetsPerNode: Int, peers: Int) =
        DcNetworkBuilder.world(randomSeed = 1)
            .addGroup(count = nodeCount) {
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 1
                this.peers = peers
                randomSubnets(count = subnetsPerNode, of = subnetCount)
            }
            .build()

    @Test
    fun `attestation waves reach every subscriber and report latency percentiles`() {
        val network = population(nodeCount = 40, subnetCount = 4, subnetsPerNode = 2, peers = 10)
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcAttestationConfig(
            waveCount = 2,
            attestersPerWave = 8,
            attestationSizeBytes = 240,
            warmup = 30.seconds,
            waveInterval = 12.seconds,
            settle = 12.seconds,
            randomSeed = 7
        )

        val report = DcAttestationScenario.run(network, graph, config)
        println(report)

        assertThat(report.overall.publishedCount).isEqualTo(config.waveCount * config.attestersPerWave)
        assertThat(report.overall.deliveryRatio)
            .describedAs("delivery ratio; percentiles are meaningless if attestations went missing")
            .isEqualTo(1.0)
        assertThat(report.overall.p50).isNotNull()
        assertThat(report.overall.p50!!).isLessThanOrEqualTo(report.overall.p95!!)
        assertThat(report.overall.p95!!).isLessThanOrEqualTo(report.overall.p99!!)
        assertThat(report.overall.p99!!).isLessThanOrEqualTo(config.settle)
        assertThat(report.perWave.keys).containsExactly(0, 1)

        // Publish bytes are attributed to a wave by reading the wave index back out of the payload.
        // Requiring the per-wave figures to add up to the aggregate proves every publish message
        // was recognised: an unparsed payload would be silently dropped from the breakdown.
        assertThat(report.gossipPublishBytesReceivedByWave.keys).containsExactlyInAnyOrder(0, 1)
        assertThat(report.gossipPublishBytesReceivedByWave.values.sum())
            .isEqualTo(report.gossipPublishBytesReceived)
        assertThat(report.gossipPublishBytesSentByWave.values.sum())
            .isEqualTo(report.gossipPublishBytesSent)
    }

    @Test
    fun `the same seed produces the same latencies`() {
        val network = population(nodeCount = 24, subnetCount = 4, subnetsPerNode = 2, peers = 8)
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 3)
        val config = DcAttestationConfig(
            waveCount = 1,
            attestersPerWave = 4,
            warmup = 30.seconds,
            settle = 12.seconds,
            randomSeed = 11
        )

        val first = DcAttestationScenario.run(network, graph, config)
        val second = DcAttestationScenario.run(network, graph, config)

        assertThat(second.overall.p50).isEqualTo(first.overall.p50)
        assertThat(second.overall.p99).isEqualTo(first.overall.p99)
        assertThat(second.overall.actualDeliveries).isEqualTo(first.overall.actualDeliveries)
    }

    @Test
    fun `schedule picks the requested number of distinct attesters per wave`() {
        val network = population(nodeCount = 40, subnetCount = 8, subnetsPerNode = 2, peers = 10)
        val schedule = DcAttestationSchedule.random(
            network = network,
            waveTimes = DcAttestationSchedule.waveTimes(count = 3, first = 30.seconds, interval = 12.seconds),
            attestersPerWave = 10,
            randomSeed = 4
        )

        assertThat(schedule.attestations).hasSize(30)
        schedule.attestations.groupBy { it.waveIndex }.forEach { (wave, attestations) ->
            assertThat(attestations.map { it.attesterNodeId })
                .describedAs("attesters in wave %s", wave)
                .hasSize(10)
                .doesNotHaveDuplicates()
        }
        // an attester always attests on a subnet it actually subscribes to
        schedule.attestations.forEach { attestation ->
            assertThat(network.node(attestation.attesterNodeId).attestationSubnetIds)
                .contains(attestation.subnetId)
        }
        assertThat(schedule.attestationsOf(schedule.attestations.first().attesterNodeId)).isNotEmpty()
    }

    @Test
    fun `rejects asking for more attesters than there are eligible validators`() {
        val network = population(nodeCount = 10, subnetCount = 4, subnetsPerNode = 1, peers = 4)

        assertThatThrownBy {
            DcAttestationSchedule.random(
                network = network,
                waveTimes = listOf(30.seconds),
                attestersPerWave = 50
            )
        }.hasMessageContaining("exceeds the 10 nodes")
    }
}
