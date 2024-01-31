package io.libp2p.simulate.main.erasure

import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.erasure.message.SampledMessage
import io.libp2p.pubsub.erasure.router.strategy.AckSendStrategy
import io.libp2p.pubsub.erasure.router.strategy.SampleSendStrategy
import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.delay.bandwidth.FairQDisk
import io.libp2p.simulate.delay.bandwidth.QDiscBandwidthTracker
import io.libp2p.simulate.delay.latency.LatencyDistribution
import io.libp2p.simulate.main.SimulationLogger
import io.libp2p.simulate.main.SimulationRunner
import io.libp2p.simulate.mbitsPerSecond
import io.libp2p.simulate.pubsub.PubsubMessageSizes
import io.libp2p.simulate.pubsub.createGenericPubsubMessageSizes
import io.libp2p.simulate.pubsub.erasure.ErasureSimConfig
import io.libp2p.simulate.pubsub.erasure.ErasureSimNetwork
import io.libp2p.simulate.pubsub.erasure.ErasureSimPeerConfigGenerator
import io.libp2p.simulate.pubsub.erasure.ErasureSimulation
import io.libp2p.simulate.pubsub.erasure.router.SimErasureCoder
import io.libp2p.simulate.pubsub.erasure.router.SimErasureRouterBuilder
import io.libp2p.simulate.pubsub.trickyMessageBodyGenerator
import io.libp2p.simulate.stats.ResultPrinter
import io.libp2p.simulate.stats.collect.pubsub.SimulationResult
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.simulate.util.cartesianProduct
import io.libp2p.simulate.util.smartRound
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    ErasureSimulationRunner().runAllAndPrintResults()
}

class ErasureSimulationRunner(
    val logger: (String) -> Unit = ::println,
    val simulatorThreadCount: Int = 8,
    val publishMessageCount: Int = 1,
    val messageSizes: PubsubMessageSizes = trickyMessageBodyGenerator.createGenericPubsubMessageSizes(),

    nodeCountParams: List<Int> = listOf(
        100,
        1000
    ),
    peerCountPrams: List<Int> = listOf(
//        2,
        3,
        4,
        5,
        7,
        10,
        15,
        20
    ),
    messageSizeParams: List<Int> = listOf(
        5 * 128 * 1024
    ),
    sampleSizeParams: List<Int> = listOf(
        8 * 1024
    ),
    extensionFactorParams: List<Int> = listOf(
        40,
    ),
    sampleExtraSizeParams: List<Int> = listOf(
        0
//        1024
    ),
    headerSizeParams: List<Int> = listOf(
        32
//        512
    ),
    cWndSizeParams: List<Int> = listOf(
//        1,
//        2,
//        4,
        8,
//        16,
//        32,
//        64,
    ),
    bandwidthsParams: List<RandomDistribution<Bandwidth>> = listOf(
        RandomDistribution.const(10.mbitsPerSecond),
//        RandomDistribution.const(100.mbitsPerSecond),
    ),
    latencyParams: List<LatencyDistribution> =
        listOf(
//            LatencyDistribution.createConst(0.milliseconds),
//            LatencyDistribution.createConst(5.milliseconds),
//            LatencyDistribution.createConst(10.milliseconds),
//            LatencyDistribution.createConst(25.milliseconds),
//            LatencyDistribution.createConst(50.milliseconds),
            LatencyDistribution.createConst(100.milliseconds),
        )
) {
    val testTopic: Topic = Topic("Topic-1")

    class SampleAckSendConfig(
        val sampleSendStrategy: (SampledMessage) -> SampleSendStrategy,
        val ackSendStrategy: (SampledMessage) -> AckSendStrategy,
        val descr: String
    ) {
        override fun equals(other: Any?)= descr == (other as SampleAckSendConfig).descr
        override fun hashCode() = descr.hashCode()
        override fun toString() = descr
    }
    val sendStrategyParams = cWndSizeParams
        .map { cWndSize ->
            SampleAckSendConfig(
                sampleSendStrategy = { SampleSendStrategy.cWndStrategy(cWndSize) },
                ackSendStrategy = AckSendStrategy.Companion::allInboundAndWhenComplete,
                "aiwc/wnd($cWndSize)"
            )
        }

    data class SimParams(
        val nodeCount: Int,
        val peerCount: Int,
        val messageSize: Int,
        val sampleSize: Int,
        val extensionFactor: Int,
        val sampleExtraSize: Int,
        val headerSize: Int,
        val sendConfig: SampleAckSendConfig,
        val bandwidth: RandomDistribution<Bandwidth>,
        val latency: LatencyDistribution,
    )

    val SimParams.minTheoreticalTraffic get() = messageSize * (nodeCount - 1)
    val SimParams.minTheoreticalSamples get() = (messageSize / sampleSize) * (nodeCount - 1)


    class RunResult(simResult: SimulationResult) {
        val deliveryDelays = simResult.apiDeliverDelays.values
        val msgCount = simResult.pubsubMessageResult.getTotalMessageCount()
        val traffic = simResult.pubsubMessageResult.getTotalTraffic()
        val preDeliverTraffic = simResult.messagesPriorToDelivery.getTotalTraffic()
        val samples = simResult.pubsubMessageResult.erasureSampleMessages.size
        val preDeliverSamples = simResult.messagesPriorToDelivery.erasureSampleMessages.size
        val headers = simResult.pubsubMessageResult.erasureHeaderMessages.size
        val acks = simResult.pubsubMessageResult.erasureAckMessages.size
    }

    val allParams = cartesianProduct(
        nodeCountParams,
        peerCountPrams,
        messageSizeParams,
        sampleSizeParams,
        extensionFactorParams,
        sampleExtraSizeParams,
        headerSizeParams,
        sendStrategyParams,
        bandwidthsParams,
        latencyParams,
        ::SimParams
    )

    fun createSimConfig(params: SimParams) = ErasureSimConfig(
        peerConfigs = ErasureSimPeerConfigGenerator(
            topics = listOf(testTopic),
            bandwidths = params.bandwidth,
            messageValidationDelays = RandomDistribution.const(0.milliseconds)
        ).generate(0, params.nodeCount),
        bandwidthTrackerFactory =
//                BandwidthTrackerFactory.fromLambda(::AccurateBandwidthTracker),
                QDiscBandwidthTracker.createFactory { FairQDisk(it) },
        topology = RandomNPeers(params.peerCount),
        latency = params.latency,
        sampleSendStrategy = params.sendConfig.sampleSendStrategy,
        ackSendStrategy = params.sendConfig.ackSendStrategy,
        simErasureCoder = SimErasureCoder(
            sampleSize = params.sampleSize,
            extensionFactor = params.extensionFactor,
            sampleExtraSize = params.sampleExtraSize,
            headerSize = params.headerSize,
            messageBodyGenerator = messageSizes.messageBodyGenerator
        )
    )


    fun createSimulation(params: SimParams, logger: SimulationLogger): ErasureSimulation {
        val simConfig = createSimConfig(params)
        val simNetwork = ErasureSimNetwork(simConfig) { SimErasureRouterBuilder() }
        logger("Creating peers...")
        simNetwork.createAllPeers()
        logger("Connecting peers...")
        simNetwork.connectAllPeers()
        logger("Peers connected. Graph diameter is " + simNetwork.network.topologyGraph.calcDiameter())

        logger("Creating simulation...")
        return ErasureSimulation(simConfig, simNetwork)
    }

    fun publishMessage(
        params: SimParams,
        simulation: ErasureSimulation,
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
        val pubsubMessageResult = simulation.messageCollector.gatherResult()
        val simResult = SimulationResult(pubsubMessageResult, simulation.apiMessageDeliveries)
//        printExtraInfo1(params, simulation)
        val messageDelays = simulation.apiMessageDeliveries.map { it.receiveTime - it.message.sentTime }
        return RunResult(simResult)
    }

    fun printExtraInfo1(params: SimParams, simulation: ErasureSimulation) {
        val result = simulation.messageCollector.gatherResult()
        simulation.apiMessageDeliveries.forEach { apiDelivery ->
            val simPeer = result.allPeers.first { it.simPeerId == apiDelivery.receivingPeer }
            val inboundRpcMessages = result.messages
                .filter { it.receivingPeer == simPeer }
                .filter { it.receiveTime <= apiDelivery.receiveTime }
            val totSize = inboundRpcMessages.sumOf { messageSizes.sizeEstimator.estimateSize(it.message) }
            val samples = result
                .peerReceivedMessages[simPeer]!!
                .erasureSampleMessages
                .filter { it.origMsg.receiveTime <= apiDelivery.receiveTime }

            println("$simPeer: t=${apiDelivery.receiveTime}, rpcCnt=${inboundRpcMessages.size}, totSize=$totSize, sampleCnt=${samples.size}")
        }
//        val res = simulation.messageCollector.gatherResult()
//        res.allPeers.forEach { peer ->
//            val receivedMessages = res.peerReceivedMessages[peer]!!
//            println("$peer: inbound sample count: " + receivedMessages.erasureSampleMessages.size)
//        }
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
//                    addLong("5%") { it.getPercentile(5.0) }
                    addLong("50%") { it.getPercentile(50.0) }
//                    addLong("mean") { it.mean }
                    addLong("95%") { it.getPercentile(95.0) }
                    addLong("max") { it.max }
                }
            addMetric("msgCount") { it.msgCount }
            addMetricWithParams("traffic") { p, res ->
                (res.traffic.toDouble() / p.minTheoreticalTraffic).smartRound()
            }
            addMetricWithParams("preTraffic") { p, res ->
                (res.preDeliverTraffic.toDouble() / p.minTheoreticalTraffic).smartRound()
            }
            addMetric("samples") { it.samples }
            addMetricWithParams("dupSamples") { p, res ->
                (res.preDeliverSamples.toDouble() / p.minTheoreticalSamples).smartRound()
            }
            addMetric("headers") { it.headers }
            addMetric("acks") { it.acks }
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