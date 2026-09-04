package io.libp2p.example.dc

import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.ContinentRegion
import io.libp2p.quicsim.udpnetwork.Bandwidth
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Everything a scenario run needs, with defaults inlined here.
 *
 * A YAML file overrides only the keys it mentions (see [DcRunConfigYaml]), so a scenario file states
 * what is interesting about that run and nothing else, and the defaults stay visible in code rather
 * than spread across invocation flags.
 */
data class DcRunConfig(
    val name: String = "unnamed",
    val population: DcPopulationConfig = DcPopulationConfig(),
    val attestation: DcAttestationRunConfig = DcAttestationRunConfig(),
    val run: DcRunSettings = DcRunSettings()
) {
    fun describe(): String = buildString {
        appendLine("scenario: $name")
        appendLine(
            "  population: ${population.nodes} nodes x ${population.validatorsPerNode} validators " +
                "(${population.validatorCount} total), ${population.bandwidth}, " +
                "peers=${population.peers}, ${population.subnetsPerNode} of ${population.subnetCount} subnets"
        )
        appendLine(
            "  attestation: mode=${attestation.mode} waves=${attestation.waveCount} " +
                "size=${attestation.sizeBytes}B warmup=${attestation.warmup} settle=${attestation.settle}"
        )
        appendLine("  run: seed=${run.seed} minPeersPerSubnet=${run.minPeersPerSubnet}")
    }
}

/** Who is on the network. A single homogeneous group is enough for most runs; extend as needed. */
data class DcPopulationConfig(
    val nodes: Int = 100,
    val validatorsPerNode: Int = 1,
    val bandwidth: DcBandwidthPreset = DcBandwidthPreset.RESIDENTIAL,
    val peers: Int = 20,
    val subnetCount: Int = 64,
    val subnetsPerNode: Int = 2,
    val seed: Long = 1
) {
    val validatorCount: Int get() = nodes * validatorsPerNode

    init {
        require(nodes > 0) { "population.nodes must be > 0, got $nodes" }
        require(validatorsPerNode >= 0) { "population.validatorsPerNode must be >= 0" }
        require(subnetsPerNode in 1..subnetCount) {
            "population.subnetsPerNode must be in [1, $subnetCount], got $subnetsPerNode"
        }
    }

    fun build(): DcNetwork<ContinentRegion> =
        DcNetworkBuilder.world(randomSeed = seed, subnetCount = subnetCount)
            .addGroup(count = nodes) {
                spreadOverRegions()
                bandwidth = this@DcPopulationConfig.bandwidth.value
                validators = validatorsPerNode
                peers = this@DcPopulationConfig.peers
                randomSubnets(count = subnetsPerNode)
            }
            .build()
}

/** Named link rates, so YAML says `bandwidth: residential` rather than a byte count. */
enum class DcBandwidthPreset(val value: Bandwidth) {
    RESIDENTIAL(Bandwidths.RESIDENTIAL),
    VPS(Bandwidths.VPS),
    DATACENTER(Bandwidths.DATACENTER);

    companion object {
        fun of(name: String): DcBandwidthPreset =
            values().firstOrNull { it.matches(name) }
                ?: throw IllegalArgumentException(
                    "Unknown bandwidth '$name', expected one of ${values().joinToString { it.name.lowercase() }}"
                )
    }
}

/** Which validators attest, and when. */
data class DcAttestationRunConfig(
    val mode: DcAttesterMode = DcAttesterMode.ALL_VALIDATORS,
    /** Only used when [mode] is [DcAttesterMode.SAMPLE]. */
    val attestersPerWave: Int = 32,
    val waveCount: Int = 1,
    val sizeBytes: Int = 240,
    val warmup: Duration = 60.seconds,
    val waveInterval: Duration = 12.seconds,
    val settle: Duration = 30.seconds
) {
    init {
        require(waveCount > 0) { "attestation.waveCount must be > 0, got $waveCount" }
        require(sizeBytes >= DcAttestationNodeProgram.HEADER_BYTES) {
            "attestation.sizeBytes must be >= ${DcAttestationNodeProgram.HEADER_BYTES}, got $sizeBytes"
        }
    }

    fun toScenarioConfig(seed: Long): DcAttestationConfig =
        DcAttestationConfig(
            waveCount = waveCount,
            attestersPerWave = attestersPerWave,
            attestationSizeBytes = sizeBytes,
            warmup = warmup,
            waveInterval = waveInterval,
            settle = settle,
            randomSeed = seed
        )
}

enum class DcAttesterMode {
    /** Every validator in the network attests in every wave. */
    ALL_VALIDATORS,

    /** A random sample of `attestersPerWave` nodes attests, one attestation each. */
    SAMPLE;

    companion object {
        fun of(name: String): DcAttesterMode =
            values().firstOrNull { it.matches(name) }
                ?: throw IllegalArgumentException(
                    "Unknown attester mode '$name', expected one of " +
                        values().joinToString { it.name.lowercase() }
                )
    }
}

/**
 * Matches an enum constant against a YAML spelling, ignoring case and word separators, so that
 * `allValidators`, `all_validators` and `ALL-VALIDATORS` all name the same thing. YAML files
 * conventionally use camelCase while Kotlin enums use SCREAMING_SNAKE; neither should have to win.
 */
private fun Enum<*>.matches(text: String): Boolean =
    name.normalizedEnumName() == text.normalizedEnumName()

private fun String.normalizedEnumName(): String =
    lowercase().replace("_", "").replace("-", "").replace(" ", "")

data class DcRunSettings(
    val seed: Long = 1,
    val minPeersPerSubnet: Int = 2,
    val latencyWindowParallelism: Int = 8,
    /** Where the CSV summary goes, relative to the module directory. */
    val outputDir: String = "build/dc-reports"
)
