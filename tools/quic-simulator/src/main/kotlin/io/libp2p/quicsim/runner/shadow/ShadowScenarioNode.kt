package io.libp2p.quicsim.runner.shadow

import io.libp2p.core.Host
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.HostBuilder
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.quicsim.core.schedule.impl.NanoMonotonicTimer
import io.libp2p.quicsim.core.schedule.impl.toScheduledExecutorService
import io.libp2p.quicsim.core.schedule.impl.toSimpleScheduler
import io.libp2p.quicsim.program.NodeProgram
import io.libp2p.quicsim.runner.DeterministicNodeIdentity
import io.libp2p.quicsim.scenario.FileQuicScenarioEventSink
import io.libp2p.quicsim.scenario.QuicScenarios
import io.libp2p.quicsim.sim.NetworkContext
import io.libp2p.quicsim.sim.SimContext
import io.libp2p.transport.quic.QuicTransport
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.system.exitProcess
import kotlin.time.Duration

object ShadowScenarioNode {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            ShadowScenarioNodeApp(ShadowScenarioNodeArgs.parse(args)).run()
        } catch (t: Throwable) {
            t.printStackTrace(System.err)
            exitProcess(1)
        }
    }
}

private data class ShadowScenarioNodeArgs(
    val scenarioName: String,
    val nodeId: Int,
    val nodeCount: Int,
    val eventsFile: Path,
    val listenIp: String = "0.0.0.0",
    val listenPortStartRange: Int = 17000,
    val nodeIpPrefix: String = "11.0.0.",
    val pollMillis: Long = 100,
) {
    companion object {
        fun parse(args: Array<String>): ShadowScenarioNodeArgs {
            val values = args.toList().chunked(2).associate { pair ->
                require(pair.size == 2 && pair[0].startsWith("--")) { "Arguments must be --key value pairs" }
                pair[0].removePrefix("--") to pair[1]
            }
            return ShadowScenarioNodeArgs(
                scenarioName = values.getValue("scenario"),
                nodeId = values.getValue("node-id").toInt(),
                nodeCount = values.getValue("node-count").toInt(),
                eventsFile = Path(values.getValue("events-file")),
                listenIp = values["listen-ip"] ?: "0.0.0.0",
                listenPortStartRange = values["listen-port-start-range"]?.toInt() ?: 17000,
                nodeIpPrefix = values["node-ip-prefix"] ?: "11.0.0.",
                pollMillis = values["poll-millis"]?.toLong() ?: 100
            )
        }
    }
}

private class ShadowScenarioNodeApp(
    private val args: ShadowScenarioNodeArgs
) {
    fun run() {
        val scenario = QuicScenarios.byName(
            args.scenarioName,
            eventSink = FileQuicScenarioEventSink(args.eventsFile)
        )
        val nodeProgramFactory = scenario.createNodeProgramFactory()
        val nodeProgram = nodeProgramFactory.createNode(args.nodeId)
        val scheduler = Executors.newSingleThreadScheduledExecutor().toSimpleScheduler()
        val simContext = SimContext(scheduler, NanoMonotonicTimer.CPU)
        val host = createHost(nodeProgram, simContext)

        try {
            host.start().get(60, TimeUnit.SECONDS)
            nodeProgram.start(
                simContext,
                NetworkContext(
                    myHost = host,
                    allNodes = shadowNodeAddresses(args.nodeCount, args.listenPortStartRange, args.nodeIpPrefix)
                )
            ).get(60, TimeUnit.SECONDS)

            val deadline = System.nanoTime() + scenario.maxRunDuration.toLongNanosecondsSaturating()
            var complete = false
            while (System.nanoTime() <= deadline) {
                if (!complete && nodeProgram.isComplete()) {
                    complete = true
                }
                Thread.sleep(args.pollMillis)
            }
            if (!complete) {
                throw IllegalStateException("Scenario ${scenario.name} node ${args.nodeId} did not complete")
            }
        } finally {
            runCatching {
                CompletableFuture.allOf(host.stop()).get(30, TimeUnit.SECONDS)
            }
            scheduler.toScheduledExecutorService().shutdownNow()
        }
    }

    private fun createHost(nodeProgram: NodeProgram, simContext: SimContext): Host {
        val protocols = nodeProgram.createProtocols(simContext)
        val nodeId = nodeProgram.simNodeId
        val port = args.listenPortStartRange + nodeId

        return HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport.Companion::ECDSA)
            .protocol(*(protocols.toTypedArray()))
            .listen("/ip4/${args.listenIp}/udp/$port/quic-v1")
            .builderModifier { builder ->
                builder.identity { factory = { DeterministicNodeIdentity.privateKey(nodeId) } }
                nodeProgram.modifyBuilder(simContext, builder)
            }
            .build()
    }
}

fun shadowNodeAddresses(
    nodeCount: Int,
    listenPortStartRange: Int,
    nodeIpPrefix: String = "11.0.0."
): Map<Int, Multiaddr> =
    (0 until nodeCount).associateWith { nodeId ->
        Multiaddr(
            "/ip4/$nodeIpPrefix${nodeId + 1}/udp/${listenPortStartRange + nodeId}/quic-v1" +
                "/p2p/${DeterministicNodeIdentity.peerId(nodeId)}"
        )
    }

private fun Duration.toLongNanosecondsSaturating(): Long =
    if (this == Duration.INFINITE) Long.MAX_VALUE else inWholeNanoseconds
