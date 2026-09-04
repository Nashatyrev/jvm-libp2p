package io.libp2p.example.dc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration

/** Outcome of one scenario run: what was configured, the wiring it produced, and the latencies. */
data class DcRunResult(
    val config: DcRunConfig,
    val graphSummary: String,
    val report: DcAttestationReport
) {
    override fun toString(): String = buildString {
        append(config.describe())
        append(graphSummary)
        append(report)
    }
}

/**
 * Runs a [DcRunConfig] end to end: population, peer graph, attestation schedule, simulation, report.
 *
 * Nothing here reads system properties or the environment — a run is fully described by its config,
 * which means a scenario YAML file is a complete, checked-in record of how a result was produced.
 */
object DcScenarioRunner {

    fun run(config: DcRunConfig): DcRunResult {
        val network = config.population.build()
        val graph = network.peerGraph(
            minPeersPerSubnet = config.run.minPeersPerSubnet,
            randomSeed = config.run.seed
        )
        val deficiencies = graph.subnetDeficiencies()
        check(deficiencies.isEmpty()) {
            "${deficiencies.size} subscription(s) could not reach ${config.run.minPeersPerSubnet} " +
                "subnet peers, e.g. ${deficiencies.first()}. Increase population.nodes or " +
                "population.subnetsPerNode, or lower population.subnetCount."
        }

        val attestationConfig = config.attestation.toScenarioConfig(config.run.seed)
        val schedule = when (config.attestation.mode) {
            DcAttesterMode.ALL_VALIDATORS -> DcAttestationSchedule.allValidators(
                network = network,
                waveTimes = attestationConfig.waveTimes,
                randomSeed = config.run.seed
            )
            DcAttesterMode.SAMPLE -> DcAttestationSchedule.random(
                network = network,
                waveTimes = attestationConfig.waveTimes,
                attestersPerWave = config.attestation.attestersPerWave,
                randomSeed = config.run.seed
            )
        }

        val report = DcAttestationScenario.run(
            network = network,
            graph = graph,
            config = attestationConfig,
            latencyWindowParallelism = config.run.latencyWindowParallelism,
            schedule = schedule
        )
        return DcRunResult(config, graph.summary(), report)
    }

    /** Appends one CSV row per wave plus an `overall` row, so runs accumulate in a single file. */
    fun writeCsv(result: DcRunResult, directory: Path = Paths.get(result.config.run.outputDir)): Path {
        Files.createDirectories(directory)
        val file = directory.resolve("${result.config.name}.csv")
        val rows = buildString {
            appendLine(HEADER)
            appendRow(result, "overall", result.report.overall)
            result.report.perWave.toSortedMap().forEach { (wave, stats) ->
                appendRow(result, "wave-$wave", stats)
            }
        }
        Files.writeString(file, rows)
        return file
    }

    private fun StringBuilder.appendRow(result: DcRunResult, scope: String, stats: DcDeliveryStats) {
        val config = result.config
        appendLine(
            listOf(
                config.name,
                scope,
                config.population.nodes,
                config.population.validatorsPerNode,
                config.population.validatorCount,
                config.population.bandwidth.name.lowercase(),
                config.population.peers,
                config.population.subnetCount,
                config.population.subnetsPerNode,
                config.attestation.mode.name.lowercase(),
                config.attestation.sizeBytes,
                config.run.seed,
                stats.publishedCount,
                stats.expectedDeliveries,
                stats.actualDeliveries,
                "%.4f".format(stats.deliveryRatio),
                stats.p50.millis(),
                stats.p95.millis(),
                stats.p99.millis(),
                stats.max.millis(),
                stats.mean.millis()
            ).joinToString(",")
        )
    }

    private fun Duration?.millis(): String =
        if (this == null) "" else "%.3f".format(inWholeMicroseconds / 1000.0)

    private const val HEADER =
        "scenario,scope,nodes,validators_per_node,validators,bandwidth,peers,subnets,subnets_per_node," +
            "mode,attestation_bytes,seed,published,expected_deliveries,actual_deliveries,delivery_ratio," +
            "p50_ms,p95_ms,p99_ms,max_ms,mean_ms"
}
