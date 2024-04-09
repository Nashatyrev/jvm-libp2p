package io.libp2p.simulate.main

import io.libp2p.simulate.*
import io.libp2p.simulate.delay.bandwidth.FairQDisk
import io.libp2p.simulate.delay.bandwidth.QDiscBandwidthTracker
import io.libp2p.simulate.delay.latency.LatencyDistribution
import io.libp2p.simulate.pubsub.gossip.Eth2DefaultGossipParams
import io.libp2p.simulate.main.BlobDecouplingSimulation.MessageChoke.*
import io.libp2p.simulate.main.EpisubSimulation.Companion.awsLatencyDistribution
import io.libp2p.simulate.main.scenario.BlobDecouplingScenario
import io.libp2p.simulate.main.scenario.Decoupling
import io.libp2p.simulate.stats.ResultPrinter
import io.libp2p.simulate.stats.collect.SimMessageId
import io.libp2p.simulate.stats.collect.gossip.*
import io.libp2p.simulate.stats.collect.pubsub.PubsubMessageResult
import io.libp2p.simulate.stats.collect.pubsub.gossip.GossipPubDeliveryResult
import io.libp2p.simulate.stats.collect.pubsub.gossip.duplicatePublishes
import io.libp2p.simulate.stats.collect.pubsub.gossip.getDeliveriesByIWant
import io.libp2p.simulate.stats.collect.pubsub.gossip.getGossipPubDeliveryResult
import io.libp2p.simulate.stats.collect.pubsub.gossip.getIWantsForPubMessage
import io.libp2p.simulate.stats.collect.pubsub.gossip.iWantRequestCount
import io.libp2p.simulate.stats.collect.pubsub.gossip.publishesByIWant
import io.libp2p.simulate.stats.collect.pubsub.gossip.roundPublishes
import io.libp2p.simulate.util.ReadableSize
import io.libp2p.simulate.util.cartesianProduct
import io.libp2p.tools.log
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    BlobDecouplingSimulation().runAndPrint()
}

@Suppress("UNUSED_VARIABLE")
class BlobDecouplingSimulation(
    val testMessageCount: Int = 1,
    val floodPublish: Boolean = false,

    val peerCountParams: List<Int> =
        listOf(
            4
//            30
        ),

    val sendingPeerBandwidth: Bandwidth = 10.mbitsPerSecond,
    val bandwidthsParams: List<RandomDistribution<Bandwidth>> =
        listOf(
            RandomDistribution.const(10.mbitsPerSecond),
//            RandomDistribution.const(100.mbitsPerSecond),
//            RandomDistribution.const(500.mbitsPerSecond),
//            RandomDistribution.const(1000.mbitsPerSecond),
//            RandomDistribution.const(5000.mbitsPerSecond),
        ),
//        listOf(RandomDistribution.const(sendingPeerBandwidth)),
//        bandwidthDistributions.byIndexes(0, 1, 2),
    val decouplingParams: List<Decoupling> = listOf(
//        Decoupling.Coupled,
        Decoupling.DecoupledManyTopics,
//        Decoupling.DecoupledSingleTopic,
    ),
    val latencyParams: List<LatencyDistribution> =
        listOf(
//            LatencyDistribution.createUniformConst(5.milliseconds, 50.milliseconds)
//            LatencyDistribution.createConst(10.milliseconds),
//            LatencyDistribution.createConst(50.milliseconds),
            LatencyDistribution.createConst(100.milliseconds),
//            LatencyDistribution.createConst(150.milliseconds),
//            LatencyDistribution.createConst(200.milliseconds),
//            LatencyDistribution.createUniformConst(10.milliseconds, 20.milliseconds),
//            LatencyDistribution.createUniformConst(10.milliseconds, 50.milliseconds),
//            LatencyDistribution.createUniformConst(10.milliseconds, 100.milliseconds),
//            LatencyDistribution.createUniformConst(10.milliseconds, 200.milliseconds),
//            awsLatencyDistribution
        ),
//        listOf(awsLatencyDistribution),

    val validationDelayParams: List<RandomDistribution<Duration>> =
//        listOf(RandomDistribution.const(10.milliseconds)),
        listOf(
            RandomDistribution.const(0.milliseconds),
//            RandomDistribution.discreteEven(
//                70.milliseconds to 33,
//                50.milliseconds to 33,
//                20.milliseconds to 33
//            ),
//            RandomDistribution.discreteEven(
//                300.milliseconds to 33,
//                100.milliseconds to 33,
//                10.milliseconds to 33
//            ),
//            RandomDistribution.uniform(10, 40).milliseconds(),
//            RandomDistribution.uniform(10, 100).milliseconds(),
//            RandomDistribution.uniform(10, 300).milliseconds(),
//            RandomDistribution.uniform(10, 600).milliseconds(),
        ),

    val blockConfigs: List<BlockConfig> = listOf(
//        BlockConfig.ofKilobytes(5 * 128, 128, 0)
//        BlockConfig.ofKilobytes(128, 128, 3)
        BlockConfig.ofKilobytes(64, 64, 9),
//        BlockConfig.ofKilobytes(64, 64, 9),
//        BlockConfig.ofKilobytes(32, 32, 19),
//        BlockConfig.ofKilobytes(16, 16, 39),
//        BlockConfig.ofKilobytes(8, 8, 79)
    ),

    val chokeMessageParams: List<MessageChoke> = listOf(
//        None,
        OnReceive,
//        OnNotify
    ),

    val nodeCountParams: List<Int> =
//        listOf(200/*, 100, 500, 1000*/),
//        listOf(1000),
        listOf(100),
    val randomSeedParams: List<Long> =
//        (1L..8L).toList(),
        listOf(1L),

    val paramsSet: List<SimParams> =
        cartesianProduct(
            peerCountParams,
            decouplingParams,
            bandwidthsParams,
            latencyParams,
            validationDelayParams,
            blockConfigs,
            chokeMessageParams,
            nodeCountParams,
            randomSeedParams,
            ::SimParams
        ),
) {

    enum class MessageChoke { None, OnReceive, OnNotify }

    data class BlockConfig(
        val blockSize: Int,
        val blobSize: Int,
        val blobCount: Int,
    ) {
        override fun toString() =
            "BlockConfig[${ReadableSize.create(blockSize)} + ${ReadableSize.create(blobSize)} * $blobCount]"

        companion object {
            fun ofKilobytes(blockKBytes: Int, blobKBytes: Int, blobCount: Int) =
                BlockConfig(blockKBytes * 1024, blobKBytes * 1024, blobCount)
        }
    }

    data class SimParams(
        val peerCount: Int,
        val decoupling: Decoupling,
        val bandwidth: RandomDistribution<Bandwidth>,
        val latency: LatencyDistribution,
        val validationDelays: RandomDistribution<Duration>,
        val blockConfig: BlockConfig,
        val chokeMessage: MessageChoke,
        val nodeCount: Int,
        val randomSeed: Long
    )

    data class RunResult(
        val messages: PubsubMessageResult
    ) {
        val deliveryResult =
            messages.getGossipPubDeliveryResult().aggregateSlowestByPublishTime()
    }

    fun createBlobScenario(simParams: SimParams, logger: SimulationLogger = { log(it) }): BlobDecouplingScenario =
        BlobDecouplingScenario(
            logger = logger,
            blockSize = simParams.blockConfig.blockSize,
            blobSize = simParams.blockConfig.blobSize,
            blobCount = simParams.blockConfig.blobCount,

//            sendingPeerBand = sendingPeerBandwidth,
            sendingPeerFilter = { true },
            messageCount = testMessageCount,
            nodeCount = simParams.nodeCount,
            nodePeerCount = simParams.peerCount,
            peerBands = simParams.bandwidth,
            latency = simParams.latency,
            gossipParams = Eth2DefaultGossipParams.copy(
                D = 4,
                DLow = 3,
                DHigh = 6,
                floodPublish = floodPublish,
                chokeMessageEnabled = simParams.chokeMessage != None,
                sendingControlEnabled = simParams.chokeMessage == OnNotify
//                heartbeatInterval = 5.seconds
            ),
            peerMessageValidationDelays = simParams.validationDelays,
            randomSeed = simParams.randomSeed,
            simConfigModifier = {
                it.copy(
                    bandwidthTrackerFactory = { totalBandwidth, executor, timeSupplier, name ->
                        QDiscBandwidthTracker(totalBandwidth, executor, FairQDisk(timeSupplier))
                    }
                )
            }
        )

    fun runAndPrint() {
        val results = SimulationRunner<SimParams, RunResult1>(
//            threadCount = 1,
//            printLocalLogging = true,
            runner = { params, logger ->
                run(params, logger)
            })
            .runAll(paramsSet)

        printResults(paramsSet.zip(results).toMap())
    }

    class RunResult1(
        messages: PubsubMessageResult,
        deliveryResult: GossipPubDeliveryResult =
            messages.getGossipPubDeliveryResult().aggregateSlowestByPublishTime()
    ) {
        val deliveryDelays = deliveryResult.deliveryDelays

        val msgCount = messages.getTotalMessageCount()
        val traffic = messages.getTotalTraffic()
        val pubCount = messages.publishMessages.size
        val iWants = messages.iWantRequestCount
        val iWantDeliv = deliveryResult.getDeliveriesByIWant(messages).size
        val iWantPub = messages.publishesByIWant.size
        val dupPub = messages.duplicatePublishes.size
        val roundPub = messages.roundPublishes.size
    }

    private fun printResults(res: Map<SimParams, RunResult1>) {
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
            addMetric("iWantDeliv") { it.iWantDeliv }
            addMetric("iWantPub") { it.iWantPub }
            addMetric("dupPub") { it.dupPub }
            addMetric("roundPub") { it.roundPub }
        }

        log("Results:")
        println(printer.printPretty())
        println()
        println(printer.printTabSeparated())
        println()
        println("Ranged delays:")
        println("======================")
        println(printer
            .createRangedLongStats { it.deliveryDelays }
            .apply {
                minValue = 0
                rangeSize = 50
            }
            .printTabSeparated()
        )

        log("Done.")
    }

    private fun tempResults(res: PubsubMessageResult) {
        val publishIWants = res.publishMessages
            .associateWith {
                res.getIWantsForPubMessage(it)
            }
            .filterValues { it.isNotEmpty() }
//            .onEach {
//                if (it.value.size > 1) {
//                    println("Many IWants for a singe publish: ${it.value}, ${it.key}")
//                }
//            }
//            .flatMap { it.value }

        val missedIWants =
            res.iWantMessages.toSet() - publishIWants.flatMap { it.value }.toSet()


        val connectionMessages =
            res.getConnectionMessages(res.allPeersById[822]!!, res.allPeersById[41]!!)
        val peerMessages = res.getPeerMessages(res.allPeersById[41]!!)

        data class MessagePublishKey(
            val messageId: SimMessageId,
            val fromPeer: SimPeer,
            val toPeer: SimPeer
        )

        val duplicatePublish = res.duplicatePublishes
        val roundPublish = res.roundPublishes

        println("Duplicate publishes: ${duplicatePublish.size}")
    }

    fun run(params: SimParams, logger: SimulationLogger): RunResult1 {
        val scenario = createBlobScenario(params, logger)

        scenario.simulation.clearAllMessages()

        logger("Sending test messages...")
        scenario.test(params.decoupling, testMessageCount)
        val messageResult = scenario.simulation.messageCollector.gatherResult()

//        tempResults(messageResult)

        return RunResult1(messageResult)
    }

    companion object {
        val bandwidthDistributions = listOf(
            bandwidthDistribution(
                100.mbitsPerSecond to 100
            ),
            bandwidthDistribution(
                10.mbitsPerSecond to 10,
                100.mbitsPerSecond to 80,
                190.mbitsPerSecond to 10,
            ),
            bandwidthDistribution(
                10.mbitsPerSecond to 20,
                100.mbitsPerSecond to 60,
                190.mbitsPerSecond to 20,
            ),
            bandwidthDistribution(
                10.mbitsPerSecond to 33,
                100.mbitsPerSecond to 33,
                190.mbitsPerSecond to 33,
            ),
        )
    }
}
