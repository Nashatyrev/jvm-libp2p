package io.libp2p.example.dc

import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.quicsim.program.NodeProgram
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.runner.RecordingDatagramPacketTraceRecorder
import io.libp2p.quicsim.runner.SimulatedQuicScenarioRunner
import io.libp2p.quicsim.scenario.QuicScenario
import io.libp2p.quicsim.sim.SimNodeId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A run in which, at each of several moments, a given number of randomly chosen validators publish
 * an attestation on one of their own subnets.
 *
 * [warmup] exists because gossipsub needs time to form its meshes; attesting before that measures
 * mesh construction rather than dissemination. [settle] is how long the run keeps going after the
 * last wave, and therefore the longest latency the run is able to observe — anything slower is
 * counted as an undelivered attestation instead of a large number.
 */
data class DcAttestationConfig(
    val waveCount: Int = 3,
    val attestersPerWave: Int = 32,
    val attestationSizeBytes: Int = 240,
    val warmup: Duration = 30.seconds,
    val waveInterval: Duration = 12.seconds,
    val settle: Duration = 12.seconds,
    val gossipParams: GossipParams = GossipParams(),
    val randomSeed: Long = 0
) {
    val waveTimes: List<Duration>
        get() = DcAttestationSchedule.waveTimes(waveCount, warmup, waveInterval)

    /** Moment every node stops, and the cut-off beyond which a delivery is counted as missing. */
    val completeAt: Duration get() = waveTimes.last() + settle

    /** Given to the simulator; slightly beyond [completeAt] so the run is not cut short. */
    val maxRunDuration: Duration get() = completeAt + 10.seconds
}

/** Creates the node programs and holds on to the recorder so results survive the run. */
class DcAttestationNodeProgramFactory<R>(
    private val network: DcNetwork<R>,
    private val graph: DcPeerGraph<R>,
    private val schedule: DcAttestationSchedule,
    private val config: DcAttestationConfig
) : NodeProgramFactory {

    val recorder = DcAttestationRecorder()

    private val dialTargets = graph.dialTargets()
    private val nodePrograms = mutableListOf<DcAttestationNodeProgram>()

    override fun createNode(id: SimNodeId): NodeProgram =
        DcAttestationNodeProgram(
            simNodeId = id,
            connectToNodeIds = dialTargets.getValue(id),
            subnetIds = network.node(id).attestationSubnetIds,
            schedule = schedule,
            recorder = recorder,
            attestationSizeBytes = config.attestationSizeBytes,
            completeAt = config.completeAt,
            params = config.gossipParams,
            randomSeed = config.randomSeed + id
        ).also { nodePrograms += it }

    /**
     * Everyone subscribed to the subnet except the publisher. This is the denominator for the
     * delivery ratio, so it has to come from the population rather than from what arrived.
     */
    fun expectedDeliveriesOf(attestation: DcAttestation): Int =
        network.nodesSubscribedTo(attestation.subnetId).count { it.simNodeId != attestation.attesterNodeId }

    fun report(traffic: DcTrafficReport): DcAttestationReport =
        DcAttestationReport.of(
            published = recorder.published(),
            deliveries = recorder.deliveries(),
            expectedDeliveriesOf = ::expectedDeliveriesOf,
            traffic = traffic,
            gossipBytesSent = nodePrograms.sumOf { it.gossipByteCounter.bytesWritten },
            gossipBytesReceived = nodePrograms.sumOf { it.gossipByteCounter.bytesRead },
            gossipPublishBytesSent = nodePrograms.sumOf { it.gossipByteCounter.publishBytesWritten },
            gossipPublishBytesReceived = nodePrograms.sumOf { it.gossipByteCounter.publishBytesRead }
        )
}

object DcAttestationScenario {

    /**
     * Builds the scenario. Connections come from [DcPeerGraph.dialTargets] so each edge is dialled
     * once, and the subnet coverage guarantee of the graph carries into the run.
     */
    fun <R> of(
        network: DcNetwork<R>,
        graph: DcPeerGraph<R>,
        config: DcAttestationConfig = DcAttestationConfig(),
        schedule: DcAttestationSchedule = DcAttestationSchedule.random(
            network = network,
            waveTimes = config.waveTimes,
            attestersPerWave = config.attestersPerWave,
            randomSeed = config.randomSeed
        )
    ): QuicScenario<DcAttestationNodeProgramFactory<R>> {
        return QuicScenario(
            name = "dc-attestations-${network.nodeCount}n-" +
                "${config.attestersPerWave}x${config.waveCount}-${config.attestationSizeBytes}B",
            network = network.topology,
            maxRunDuration = config.maxRunDuration,
            createNodeProgramFactory = {
                DcAttestationNodeProgramFactory(network, graph, schedule, config)
            }
        )
    }

    /**
     * Runs the scenario on the deterministic simulator and returns the delivery-latency and traffic
     * report. Traffic is captured via a [RecordingDatagramPacketTraceRecorder], which taps every raw
     * UDP datagram sent or received by every node — so the traffic figures include QUIC's own
     * overhead (handshakes, ACKs, retransmits), not just gossip payload bytes.
     */
    fun <R> run(
        network: DcNetwork<R>,
        graph: DcPeerGraph<R>,
        config: DcAttestationConfig = DcAttestationConfig(),
        latencyWindowParallelism: Int = Runtime.getRuntime().availableProcessors(),
        schedule: DcAttestationSchedule = DcAttestationSchedule.random(
            network = network,
            waveTimes = config.waveTimes,
            attestersPerWave = config.attestersPerWave,
            randomSeed = config.randomSeed
        )
    ): DcAttestationReport {
        val traceRecorder = RecordingDatagramPacketTraceRecorder()
        val result = SimulatedQuicScenarioRunner(
            latencyWindowParallelism = latencyWindowParallelism,
            datagramPacketTraceRecorder = traceRecorder
        ).run(of(network, graph, config, schedule))
        val traffic = DcTrafficReport.of(
            events = traceRecorder.events(),
            waveTimes = config.waveTimes,
            completeAt = config.completeAt,
            nodeCount = network.nodeCount
        )
        return result.nodeProgramFactory.report(traffic)
    }
}
