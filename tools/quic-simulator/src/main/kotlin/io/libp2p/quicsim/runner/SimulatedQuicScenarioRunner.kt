package io.libp2p.quicsim.runner

import io.libp2p.quicsim.program.DataChunkNodeProgramFactory
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.scenario.QuicScenario
import io.libp2p.quicsim.scenario.QuicScenarioEventSource
import io.libp2p.quicsim.scenario.QuicScenarioResult
import io.libp2p.quicsim.scenario.QuicScenarioRunner
import java.security.SecureRandom

class SimulatedQuicScenarioRunner(
    private val ipManager: IPManager = IPManager.Default,
    private val listenPortStartRange: Int = 17000,
    private val latencyWindowParallelism: Int = 0,
    private val bandwidthQueueDiscipline: BandwidthQueueDiscipline = BandwidthQueueDiscipline.SHADOW_LIKE,
    private val random: SecureRandom = SecureRandom(byteArrayOf(100)),
    private val datagramPacketTraceRecorder: DatagramPacketTraceRecorder = DatagramPacketTraceRecorder.Noop
) : QuicScenarioRunner {
    override fun <F : NodeProgramFactory> run(scenario: QuicScenario<F>): QuicScenarioResult<F> {
        val nodeProgramFactory = scenario.createNodeProgramFactory()
        val runner = SimulatedRunner(
            nodeFactory = nodeProgramFactory,
            udpNetwork = scenario.network.toUdpSimNetwork(bandwidthQueueDiscipline),
            ipManager = ipManager,
            listenPortStartRange = listenPortStartRange,
            maxSimulatedRunDuration = scenario.maxRunDuration,
            random = random,
            latencyWindowParallelism = latencyWindowParallelism,
            datagramPacketTraceRecorder = datagramPacketTraceRecorder
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
