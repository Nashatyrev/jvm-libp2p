package playground

import io.libp2p.example.dc.Bandwidths
import io.libp2p.example.dc.DcAttestationConfig
import io.libp2p.example.dc.DcAttestationSchedule
import io.libp2p.example.dc.DcAttestationScenario
import io.libp2p.example.dc.DcNetworkBuilder
import io.libp2p.example.dc.DcRunConfigYaml
import io.libp2p.example.dc.DcScenarioRunner
import io.libp2p.example.dc.peerGraph
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Scenario runners. One `@Test` per scenario file; the file supplies the parameters, the defaults in
 * [io.libp2p.example.dc.DcRunConfig] supply the rest.
 *
 * Tagged `simulation` and excluded from the normal `test` task, since these are studies rather than
 * tests — a 1000-node run is minutes to hours, not seconds.
 *
 * ```
 * ./gradlew :examples:dc:simulation --tests "*DcScenarioRunnerTest.attestation 1000 residential*"
 * ```
 *
 * To vary a run, copy the YAML rather than editing it in place: each file is then a durable record
 * of how a particular result was produced.
 */
@Tag("simulation")
class DcScenarioRunnerTest {

    @Test
    fun `attestation 1000 residential`() {
        val config = DcRunConfigYaml.loadResource("attestation-1000-residential")
        val result = DcScenarioRunner.run(config)

        println(result)
        val csv = DcScenarioRunner.writeCsv(result)
        println("wrote $csv")

        // 1000 nodes x 2 validators, all attesting once
        Assertions.assertThat(result.report.overall.publishedCount).isEqualTo(2000)
        Assertions.assertThat(result.report.overall.deliveryRatio)
            .describedAs("delivery ratio; percentiles mean nothing if attestations went missing")
            .isEqualTo(1.0)
    }

    /**
     * Same scenario as [`attestation 1000 residential`] above, but with every piece of config built
     * by hand right here instead of going through the YAML file / [DcRunConfigYaml] / [DcScenarioRunner]
     * indirection — useful as a template when sketching a one-off run directly in code.
     */
    @Test
    fun `attestation 1000 residential - inlined`() {
        val network = DcNetworkBuilder.world(randomSeed = 1)
            .addGroup(count = 1000) {
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 2
                peers = 20
                randomSubnets(count = 2, of = 64)
            }
            .build()

        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 1)
        Assertions.assertThat(graph.subnetDeficiencies()).isEmpty()

        val attestationConfig = DcAttestationConfig(
            waveCount = 1,
            attestationSizeBytes = 240,
            warmup = 60.seconds,
            settle = 30.seconds,
            randomSeed = 1
        )
        val schedule = DcAttestationSchedule.allValidators(
            network = network,
            waveTimes = attestationConfig.waveTimes,
            randomSeed = 1
        )

        val report = DcAttestationScenario.run(
            network = network,
            graph = graph,
            config = attestationConfig,
            latencyWindowParallelism = 8,
            schedule = schedule
        )
        println(report)

        // 1000 nodes x 2 validators, all attesting once
        Assertions.assertThat(report.overall.publishedCount).isEqualTo(2000)
        Assertions.assertThat(report.overall.deliveryRatio)
            .describedAs("delivery ratio; percentiles mean nothing if attestations went missing")
            .isEqualTo(1.0)
    }
}