package io.libp2p.example.dc

import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Overlays a YAML document onto [DcRunConfig] defaults.
 *
 * Only keys present in the file are overridden, so a scenario file states what is interesting about
 * that run and inherits the rest. Unknown keys are an error rather than a silent no-op: a typo in a
 * scenario file would otherwise be discovered only after the run finished with the wrong settings.
 *
 * Durations are plain numbers of seconds (`warmupSeconds: 60`), which reads better in YAML than an
 * ISO-8601 duration and avoids a parsing dialect.
 */
object DcRunConfigYaml {

    fun load(path: Path, defaults: DcRunConfig = DcRunConfig()): DcRunConfig {
        require(Files.isRegularFile(path)) { "Scenario file not found: ${path.toAbsolutePath()}" }
        return parse(Files.readString(path), defaults).let {
            if (it.name == defaults.name) it.copy(name = path.fileName.toString().substringBeforeLast('.')) else it
        }
    }

    /** Loads `scenarios/<name>.yaml` from the classpath, the usual case for a checked-in scenario. */
    fun loadResource(name: String, defaults: DcRunConfig = DcRunConfig()): DcRunConfig {
        val resource = "/scenarios/$name.yaml"
        val text = DcRunConfigYaml::class.java.getResource(resource)?.readText()
            ?: throw IllegalArgumentException("Scenario resource not found: $resource")
        val parsed = parse(text, defaults)
        return if (parsed.name == defaults.name) parsed.copy(name = name) else parsed
    }

    fun parse(yaml: String, defaults: DcRunConfig = DcRunConfig()): DcRunConfig {
        @Suppress("UNCHECKED_CAST")
        val root = (Yaml().load<Any?>(yaml) as? Map<String, Any?>).orEmpty()
        val node = DcYamlNode("", root).checkKeys("name", "population", "attestation", "run")

        return defaults.copy(
            name = node.string("name", defaults.name) ?: defaults.name,
            population = node.section("population").let { population(it, defaults.population) },
            attestation = node.section("attestation").let { attestation(it, defaults.attestation) },
            run = node.section("run").let { run(it, defaults.run) }
        )
    }

    private fun population(node: DcYamlNode, defaults: DcPopulationConfig): DcPopulationConfig {
        node.checkKeys("nodes", "validatorsPerNode", "bandwidth", "peers", "subnetCount", "subnetsPerNode", "seed")
        return defaults.copy(
            nodes = node.int("nodes", defaults.nodes),
            validatorsPerNode = node.int("validatorsPerNode", defaults.validatorsPerNode),
            bandwidth = node.string("bandwidth", null)?.let { DcBandwidthPreset.of(it) } ?: defaults.bandwidth,
            peers = node.int("peers", defaults.peers),
            subnetCount = node.int("subnetCount", defaults.subnetCount),
            subnetsPerNode = node.int("subnetsPerNode", defaults.subnetsPerNode),
            seed = node.long("seed", defaults.seed)
        )
    }

    private fun attestation(node: DcYamlNode, defaults: DcAttestationRunConfig): DcAttestationRunConfig {
        node.checkKeys(
            "mode", "attestersPerWave", "waveCount", "sizeBytes",
            "warmupSeconds", "waveIntervalSeconds", "settleSeconds"
        )
        return defaults.copy(
            mode = node.string("mode", null)?.let { DcAttesterMode.of(it) } ?: defaults.mode,
            attestersPerWave = node.int("attestersPerWave", defaults.attestersPerWave),
            waveCount = node.int("waveCount", defaults.waveCount),
            sizeBytes = node.int("sizeBytes", defaults.sizeBytes),
            warmup = node.seconds("warmupSeconds", defaults.warmup),
            waveInterval = node.seconds("waveIntervalSeconds", defaults.waveInterval),
            settle = node.seconds("settleSeconds", defaults.settle)
        )
    }

    private fun run(node: DcYamlNode, defaults: DcRunSettings): DcRunSettings {
        node.checkKeys("seed", "minPeersPerSubnet", "latencyWindowParallelism", "outputDir")
        return defaults.copy(
            seed = node.long("seed", defaults.seed),
            minPeersPerSubnet = node.int("minPeersPerSubnet", defaults.minPeersPerSubnet),
            latencyWindowParallelism = node.int("latencyWindowParallelism", defaults.latencyWindowParallelism),
            outputDir = node.string("outputDir", defaults.outputDir) ?: defaults.outputDir
        )
    }
}

/** A YAML mapping plus its path, so errors say which key of which section is wrong. */
private class DcYamlNode(private val path: String, private val values: Map<String, Any?>) {

    fun checkKeys(vararg known: String): DcYamlNode = apply {
        val unknown = values.keys - known.toSet()
        require(unknown.isEmpty()) {
            "Unknown key(s) ${unknown.sorted()} in ${path.ifEmpty { "<root>" }}; " +
                "known keys: ${known.sorted()}"
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun section(key: String): DcYamlNode {
        val raw = values[key] ?: return DcYamlNode(childPath(key), emptyMap())
        require(raw is Map<*, *>) { "${childPath(key)} must be a mapping, got ${raw::class.simpleName}" }
        return DcYamlNode(childPath(key), raw as Map<String, Any?>)
    }

    fun string(key: String, default: String?): String? = values[key]?.toString() ?: default

    fun int(key: String, default: Int): Int = when (val raw = values[key]) {
        null -> default
        is Number -> raw.toInt()
        else -> raw.toString().toIntOrNull()
            ?: throw IllegalArgumentException("${childPath(key)} must be an integer, got '$raw'")
    }

    fun long(key: String, default: Long): Long = when (val raw = values[key]) {
        null -> default
        is Number -> raw.toLong()
        else -> raw.toString().toLongOrNull()
            ?: throw IllegalArgumentException("${childPath(key)} must be an integer, got '$raw'")
    }

    fun seconds(key: String, default: Duration): Duration = when (val raw = values[key]) {
        null -> default
        is Number -> raw.toDouble().seconds
        else -> raw.toString().toDoubleOrNull()?.seconds
            ?: throw IllegalArgumentException("${childPath(key)} must be a number of seconds, got '$raw'")
    }

    private fun childPath(key: String): String = if (path.isEmpty()) key else "$path.$key"
}
