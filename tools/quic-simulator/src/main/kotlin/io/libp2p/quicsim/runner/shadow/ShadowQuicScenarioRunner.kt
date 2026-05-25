package io.libp2p.quicsim.runner.shadow

import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.scenario.QuicNetworkTopology
import io.libp2p.quicsim.scenario.QuicScenario
import io.libp2p.quicsim.scenario.QuicScenarioEventFileCodec
import io.libp2p.quicsim.scenario.QuicScenarioResult
import io.libp2p.quicsim.scenario.QuicScenarioRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration

class ShadowQuicScenarioRunner(
    private val shadowPath: Path = Path("shadow"),
    private val javaPath: Path = Path(System.getProperty("java.home"), "bin", "java"),
    private val classpath: String = System.getProperty("java.class.path"),
    private val workDir: Path = Files.createTempDirectory("quic-shadow-"),
    private val listenPortStartRange: Int = 17000,
    private val nodeIpPrefix: String = "11.0.0.",
    private val parallelism: Int = 1,
    private val javaOptions: List<String> = DEFAULT_SHADOW_NODE_JAVA_OPTIONS
) : QuicScenarioRunner {

    override fun <F : NodeProgramFactory> run(scenario: QuicScenario<F>): QuicScenarioResult<F> {
        workDir.createDirectories()
        val nodeProgramFactory = scenario.createNodeProgramFactory()
        val eventsDir = workDir.resolve("events").also { it.createDirectories() }
        val config = ShadowConfigBuilder(
            scenario = scenario,
            javaPath = javaPath,
            classpath = classpath,
            eventsDir = eventsDir,
            listenPortStartRange = listenPortStartRange,
            nodeIpPrefix = nodeIpPrefix,
            javaOptions = javaOptions
        ).build()
        val configPath = workDir.resolve("shadow.yaml")
        configPath.writeText(config)

        val process = ProcessBuilder(
            shadowPath.commandString(),
            "--data-directory",
            workDir.resolve("shadow.data").absolutePathString(),
            "--parallelism",
            parallelism.toString(),
            configPath.absolutePathString()
        )
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException(
                "Shadow scenario ${scenario.name} failed with exit code $exitCode\n$output"
            )
        }

        val events = scenario.network.hosts.indices.flatMap { nodeId ->
            QuicScenarioEventFileCodec.readEvents(eventsDir.resolve("node-$nodeId.events"))
        }.sortedBy { it.at }

        return QuicScenarioResult(
            scenarioName = scenario.name,
            runnerName = "shadow",
            nodeProgramFactory = nodeProgramFactory,
            events = events
        )
    }

    private companion object {
        val DEFAULT_SHADOW_NODE_JAVA_OPTIONS = listOf(
            "-Xmx96m",
            "-XX:MaxDirectMemorySize=32m",
            "-XX:ReservedCodeCacheSize=32m",
            "-Xss256k"
        )
    }
}

class ShadowConfigBuilder(
    private val scenario: QuicScenario<*>,
    private val javaPath: Path,
    private val classpath: String,
    private val eventsDir: Path,
    private val listenPortStartRange: Int,
    private val nodeIpPrefix: String = "11.0.0.",
    private val javaOptions: List<String> = emptyList()
) {
    fun build(): String =
        buildString {
            appendLine("general:")
            appendLine("  stop_time: ${scenario.maxRunDuration.toShadowTime()}")
            appendLine("  model_unblocked_syscall_latency: true")
            appendLine()
            appendLine("network:")
            appendLine("  graph:")
            appendLine("    type: gml")
            appendLine("    inline: |")
            scenario.network.toShadowGml().lineSequence().forEach { line ->
                appendLine("      $line")
            }
            appendLine()
            appendLine("hosts:")
            scenario.network.hosts.forEachIndexed { nodeId, host ->
                appendLine("  ${host.id}:")
                appendLine("    network_node_id: ${scenario.network.networkNodeIndex(host.id)}")
                appendLine("    ip_addr: \"$nodeIpPrefix${nodeId + 1}\"")
                appendLine("    processes:")
                appendLine("    - path: ${javaPath.absolutePathString().yamlQuote()}")
                appendLine("      args:")
                shadowNodeArgs(nodeId).forEach { arg ->
                    appendLine("      - ${arg.yamlQuote()}")
                }
                appendLine("      start_time: 1s")
                appendLine("      expected_final_state: running")
            }
        }

    private fun shadowNodeArgs(nodeId: Int): List<String> =
        javaOptions + listOf(
            "-cp",
            classpath,
            "io.libp2p.quicsim.runner.shadow.ShadowScenarioNode",
            "--scenario",
            scenario.name,
            "--node-id",
            nodeId.toString(),
            "--node-count",
            scenario.nodeCount.toString(),
            "--events-file",
            eventsDir.resolve("node-$nodeId.events").absolutePathString(),
            "--listen-port-start-range",
            listenPortStartRange.toString(),
            "--node-ip-prefix",
            nodeIpPrefix
        )
}

private fun QuicNetworkTopology.toShadowGml(): String {
    val nodeIds = hosts.map { it.id } + routers.map { it.id }
    val bandwidthByNode = nodeIds.associateWith { id ->
        links
            .filter { it.from == id || it.to == id }
            .maxOfOrNull { it.bandwidthBytesPerSecond }
            ?: 1_000_000L
    }

    return buildString {
        appendLine("graph [")
        appendLine("  directed 1")
        nodeIds.forEachIndexed { index, nodeId ->
            val bandwidthBits = bandwidthByNode.getValue(nodeId) * 8
            appendLine("  node [")
            appendLine("    id $index")
            appendLine("    label ${nodeId.yamlQuote()}")
            appendLine("    host_bandwidth_down \"$bandwidthBits bit\"")
            appendLine("    host_bandwidth_up \"$bandwidthBits bit\"")
            appendLine("  ]")
        }
        nodeIds.forEachIndexed { index, nodeId ->
            appendLine("  edge [")
            appendLine("    source $index")
            appendLine("    target $index")
            appendLine("    label ${"$nodeId self".yamlQuote()}")
            appendLine("    latency \"1 ns\"")
            appendLine("    packet_loss 0.0")
            appendLine("  ]")
        }
        links.forEach { link ->
            appendLine("  edge [")
            appendLine("    source ${networkNodeIndex(link.from)}")
            appendLine("    target ${networkNodeIndex(link.to)}")
            appendLine("    label ${"${link.from} to ${link.to}".yamlQuote()}")
            appendLine("    latency \"${link.latency.toShadowTime(nonZero = true)}\"")
            appendLine("    packet_loss 0.0")
            appendLine("  ]")
        }
        appendLine("]")
    }
}

private fun QuicNetworkTopology.networkNodeIndex(id: String): Int {
    val nodeIds = hosts.map { it.id } + routers.map { it.id }
    return nodeIds.indexOf(id).also {
        require(it >= 0) { "Unknown network node id: $id" }
    }
}

private fun Duration.toShadowTime(nonZero: Boolean = false): String {
    val nanos = inWholeNanoseconds.let { if (nonZero && it == 0L) 1L else it }
    return "$nanos ns"
}

private fun String.yamlQuote(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun Path.commandString(): String =
    if (isAbsolute) absolutePathString() else toString()
