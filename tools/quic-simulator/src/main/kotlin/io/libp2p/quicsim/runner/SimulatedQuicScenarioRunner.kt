package io.libp2p.quicsim.runner

import io.libp2p.quicsim.program.DataChunkNodeProgramFactory
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.scenario.QuicScenario
import io.libp2p.quicsim.scenario.QuicScenarioEventSource
import io.libp2p.quicsim.scenario.QuicScenarioResult
import io.libp2p.quicsim.scenario.QuicScenarioRunner
import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl2

class SimulatedQuicScenarioRunner(
    private val ipManager: IPManager = IPManager.Default,
    private val listenPortStartRange: Int = 17000,
    private val latencyWindowParallelism: Int = 0
) : QuicScenarioRunner {

    override fun <F : NodeProgramFactory> run(scenario: QuicScenario<F>): QuicScenarioResult<F> {
        val nodeProgramFactory = scenario.createNodeProgramFactory()
        val runner = SimulatedRunner(
            nodeFactory = nodeProgramFactory,
            networkEngine = UdpSimNetworkEngineImpl2(scenario.network.toUdpSimNetwork()),
            ipManager = ipManager,
            listenPortStartRange = listenPortStartRange,
            maxSimulatedRunDuration = scenario.maxRunDuration,
            latencyWindowParallelism = latencyWindowParallelism
        )

        try {
            runner.run()
        } catch (t: Throwable) {
            if (nodeProgramFactory is DataChunkNodeProgramFactory) {
                println(nodeProgramFactory.debugState())
            }
            throw t
        }

        return QuicScenarioResult(
            scenarioName = scenario.name,
            runnerName = "simulated",
            nodeProgramFactory = nodeProgramFactory,
            events = (nodeProgramFactory as? QuicScenarioEventSource)?.events().orEmpty()
        )
    }
}
