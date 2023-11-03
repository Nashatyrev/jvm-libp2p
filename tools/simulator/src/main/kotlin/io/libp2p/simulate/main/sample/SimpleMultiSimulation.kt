package io.libp2p.simulate.main.sample

import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.delay.latency.LatencyDistribution
import io.libp2p.simulate.main.BlobDecouplingSimulation
import io.libp2p.simulate.main.SimulationLogger
import io.libp2p.simulate.main.SimulationRunner
import io.libp2p.simulate.mbitsPerSecond
import io.libp2p.simulate.pubsub.gossip.GossipSimConfig
import io.libp2p.simulate.pubsub.gossip.GossipSimNetwork
import io.libp2p.simulate.pubsub.gossip.GossipSimPeerConfigGenerator
import io.libp2p.simulate.pubsub.gossip.GossipSimulation
import io.libp2p.simulate.stats.ResultPrinter
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.stats.collect.gossip.GossipMessageResult
import io.libp2p.simulate.stats.collect.gossip.GossipPubDeliveryResult
import io.libp2p.simulate.stats.collect.gossip.duplicatePublishes
import io.libp2p.simulate.stats.collect.gossip.getDeliveriesByIWant
import io.libp2p.simulate.stats.collect.gossip.getGossipPubDeliveryResult
import io.libp2p.simulate.stats.collect.gossip.iWantRequestCount
import io.libp2p.simulate.stats.collect.gossip.publishesByIWant
import io.libp2p.simulate.stats.collect.gossip.roundPublishes
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.simulate.util.cartesianProduct
import io.libp2p.tools.log
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    SimpleMultiSimulation().runAllAndPrintResults()
}

class SimpleMultiSimulation(
    val logger: (String) -> Unit = ::println,
    val simulatorThreadCount: Int = 4,
    val publishMessageCount: Int = 3,

    nodeCountParams: List<Int> = listOf(
        1000
    ),
    peerCountPrams: List<Int> = listOf(
        10,
        30
    ),
    messageSizeParams: List<Int> = listOf(
        32 * 1024,
        128 * 1024
    ),
    gossipDParams: List<Int> = listOf(
        8
    ),
    bandwidthsParams: List<RandomDistribution<Bandwidth>> = listOf(
        RandomDistribution.const(10.mbitsPerSecond),
        RandomDistribution.const(100.mbitsPerSecond),
    ),
    latencyParams: List<LatencyDistribution> =
        listOf(
            LatencyDistribution.createUniformConst(1.milliseconds, 50.milliseconds)
        )
) {
    val testTopic: Topic = Topic("Topic-1")

    data class SimParams(
        val nodeCount: Int,
        val peerCount: Int,
        val messageSize: Int,
        val gossipD: Int,
        val bandwidth: RandomDistribution<Bandwidth>,
        val latency: LatencyDistribution,
    )

    class RunResult(messages: GossipMessageResult) {
        val deliveryDelays = messages.getGossipPubDeliveryResult().aggregateSlowestByPublishTime().deliveryDelays
        val msgCount = messages.getTotalMessageCount()
        val traffic = messages.getTotalTraffic()
        val pubCount = messages.publishMessages.size
        val iWants = messages.iWantRequestCount
    }

    val allParams = cartesianProduct(
        nodeCountParams,
        peerCountPrams,
        messageSizeParams,
        gossipDParams,
        bandwidthsParams,
        latencyParams,
        ::SimParams
    )

    fun createSimConfig(params: SimParams) = GossipSimConfig(
        peerConfigs = GossipSimPeerConfigGenerator(
            topics = listOf(testTopic),
            gossipParams = GossipParams().copy(
                D = params.gossipD
            ),
            bandwidths = params.bandwidth,
            messageValidationDelays = RandomDistribution.const(10.milliseconds)
        ).generate(0, params.nodeCount),
        topology = RandomNPeers(params.peerCount),
        latency = params.latency,
    )

    fun createSimulation(params: SimParams, logger: SimulationLogger): GossipSimulation {
        val simConfig = createSimConfig(params)
        val simNetwork = GossipSimNetwork(simConfig)
        logger("Creating peers...")
        simNetwork.createAllPeers()
        logger("Connecting peers...")
        simNetwork.connectAllPeers()
        logger("Peers connected. Graph diameter is " + simNetwork.network.topologyGraph.calcDiameter())

        logger("Creating simulation...")
        return GossipSimulation(simConfig, simNetwork)
    }

    fun publishMessage(
        params: SimParams,
        simulation: GossipSimulation,
        logger: SimulationLogger,
        publisherPeer: Int = 0
    ) {
        logger("Sending message at time ${simulation.network.timeController.time}...")
        simulation.publishMessage(publisherPeer, params.messageSize, testTopic)

        simulation.forwardTimeUntilAllPubDelivered()
        logger("All messages delivered at time ${simulation.network.timeController.time}")
        simulation.forwardTimeUntilNoPendingMessages()
        logger("No more pending messages at time ${simulation.network.timeController.time}")
    }

    fun runSingle(params: SimParams, logger: SimulationLogger): RunResult {
        val simulation = createSimulation(params, logger)
        repeat(publishMessageCount) { counter ->
            publishMessage(params, simulation, logger, counter)
        }
        val gossipMessageResult = simulation.messageCollector.gatherResult()
        return RunResult(gossipMessageResult)
    }

    fun runAll(): List<RunResult> =
        SimulationRunner<SimParams, RunResult>(
            threadCount = simulatorThreadCount,
            printLocalLogging = false,
            globalLogger = logger,
            runner = { params, logger ->
                runSingle(params, logger)
            })
            .runAll(allParams)

    private fun printResults(res: Map<SimParams, RunResult>) {
        val printer = ResultPrinter(res).apply {
            addNumberStats { it.deliveryDelays }
                .apply {
                    addGeneric("count") { it.size }
                    addLong("min") { it.min }
                    addLong("5%") { it.getPercentile(5.0) }
                    addLong("50%") { it.getPercentile(50.0) }
                    addLong("mean") { it.mean }
                    addLong("95%") { it.getPercentile(95.0) }
                    addLong("max") { it.max }
                }
            addMetric("msgCount") { it.msgCount }
            addMetric("traffic") { it.traffic }
            addMetric("pubCount") { it.pubCount }
            addMetric("iWants") { it.iWants }
        }

        println("\nResult:\n")
        println(printer.printPretty())
    }

    fun runAllAndPrintResults() {
        logger("Starting simulation with ${allParams.size} params in total on $simulatorThreadCount threads...")
        val results = runAll()
        printResults(allParams.zip(results).toMap())
        logger("Done.")
    }
}