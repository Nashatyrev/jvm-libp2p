package io.libp2p.example.dc

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Scenario runners. One `@Test` per scenario file; the file supplies the parameters, the defaults in
 * [DcRunConfig] supply the rest.
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
        assertThat(result.report.overall.publishedCount).isEqualTo(2000)
        assertThat(result.report.overall.deliveryRatio)
            .describedAs("delivery ratio; percentiles mean nothing if attestations went missing")
            .isEqualTo(1.0)
    }
}
