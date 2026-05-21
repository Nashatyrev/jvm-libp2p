package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.schedule.impl.toScheduledExecutorService
import io.libp2p.quicsim.program.DataChunkNodeProgramFactory
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.scenario.QuicScenario
import io.libp2p.quicsim.scenario.QuicScenarioEventSource
import io.libp2p.quicsim.scenario.QuicScenarioResult
import io.libp2p.quicsim.scenario.QuicScenarioRunner
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class LocalQuicScenarioRunner(
    private val listenIP: String = "127.0.0.1",
    private val listenPortStartRange: Int = 17000
) : QuicScenarioRunner {

    override fun <F : NodeProgramFactory> run(scenario: QuicScenario<F>): QuicScenarioResult<F> {
        val nodeProgramFactory = scenario.createNodeProgramFactory()
        val runner = LocalRealRunner(
            nodeFactory = nodeProgramFactory,
            nodeCount = scenario.nodeCount,
            listenIP = listenIP,
            listenPortStartRange = listenPortStartRange
        )

        try {
            runner.run()
        } catch (t: Throwable) {
            if (nodeProgramFactory is DataChunkNodeProgramFactory) {
                println(nodeProgramFactory.debugState())
            }
            throw t
        } finally {
            runCatching {
                runCatching { runner.hosts }
                    .getOrNull()
                    ?.let { hosts ->
                        CompletableFuture.allOf(*hosts.map { it.stop() }.toTypedArray()).get(30, TimeUnit.SECONDS)
                    }
            }.onFailure {
                println("Failed to stop local scenario hosts: $it")
            }

            runCatching {
                runCatching { runner.simContexts }
                    .getOrNull()
                    ?.forEach { context ->
                        context.scheduler.toScheduledExecutorService().shutdownNow()
                    }
            }.onFailure {
                println("Failed to stop local scenario schedulers: $it")
            }
        }

        return QuicScenarioResult(
            scenarioName = scenario.name,
            runnerName = "local",
            nodeProgramFactory = nodeProgramFactory,
            events = (nodeProgramFactory as? QuicScenarioEventSource)?.events().orEmpty()
        )
    }
}
