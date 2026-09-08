package io.libp2p.quicsim.runner

import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipRpcFrameStats
import io.libp2p.pubsub.gossip.NEVER_FLOOD_PUBLISH
import io.libp2p.quicsim.program.ErasureCodedGossipNodeProgram
import io.libp2p.quicsim.program.NodeProgram
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.program.PartialErasureCodedGossipNodeProgram
import io.libp2p.quicsim.program.SampleGossipNodeProgram
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.WORLD_DESCRIPTOR_1
import io.libp2p.quicsim.scenario.RegionalNetworkTopologyBuilder
import io.libp2p.quicsim.scenario.RecordingQuicScenarioEventSink
import io.libp2p.quicsim.scenario.QuicScenarioEvent
import io.libp2p.quicsim.scenario.QuicScenarioEventSource
import io.libp2p.quicsim.scenario.QuicScenarioEventSink
import io.libp2p.quicsim.scenario.addRandomScenarioHosts
import io.libp2p.quicsim.sim.SimNodeId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class RegionalGossipTopologyTest {
    @Test
    @Timeout(180)
    fun `erasure coded symbols recover after 64 of 128 symbols on one topic`() {
        val events = runErasureCodedGossip(
            topologySeed = topologySeed,
            overlaySeed = overlaySeed,
            gossipSeedBase = gossipSeedBase
        ).events
        val publishedSymbols = events.filterIsInstance<QuicScenarioEvent.GossipMessagePublished>()
        val initialPublications = publishedSymbols.filter { it.nodeId == PUBLISHER_NODE_ID }
        val recoveryPublications = publishedSymbols.filter { it.nodeId != PUBLISHER_NODE_ID }
        val recoveries = events.filterIsInstance<QuicScenarioEvent.GossipSymbolsRecovered>()
        val expectedRecipients = (0 until NODE_COUNT).filter { it != PUBLISHER_NODE_ID }.toSet()

        assertEquals(ERASURE_SYMBOL_COUNT, initialPublications.size)
        assertEquals((0 until ERASURE_SYMBOL_COUNT).toSet(), initialPublications.map { it.messageIndex }.toSet())
        assertEquals(setOf(INITIAL_PUBLISH_DELAY), initialPublications.map { it.at }.toSet())
        assertTrue(
            initialPublications.map { it.messageIndex } != (0 until ERASURE_SYMBOL_COUNT).toList(),
            "The initial publisher must randomize symbol publication order"
        )
        assertEquals(expectedRecipients, recoveries.map { it.nodeId }.toSet())
        assertTrue(recoveries.all { it.receivedSymbolCount >= ERASURE_RECOVERY_THRESHOLD })
        assertTrue(recoveries.all { it.republishedSymbolCount <= ERASURE_SYMBOL_COUNT - ERASURE_RECOVERY_THRESHOLD })
        assertEquals(recoveries.sumOf { it.republishedSymbolCount }, recoveryPublications.size)

        val recoveryDelays = recoveries.map { it.at - INITIAL_PUBLISH_DELAY }
        println(
            "Erasure-coded gossip recovery: symbols=$ERASURE_SYMBOL_COUNT threshold=$ERASURE_RECOVERY_THRESHOLD " +
                "symbolSizeKiB=${ERASURE_SYMBOL_SIZE_BYTES / 1024} " +
                "p95=${percentile(recoveryDelays, 0.95).inWholeMilliseconds}ms"
        )
    }

    @Test
    @Timeout(1_200)
    fun `report erasure coded recovery over waves and seeds`() {
        assumeTrue(
            java.lang.Boolean.getBoolean("quicsim.erasureGossip.report"),
            "Set -Dquicsim.erasureGossip.report=true to run this slow report"
        )

        val seeds = List(ERASURE_REPORT_SEED_COUNT) { ERASURE_REPORT_SEED_START + it }
        val republishRecoveredSymbols = !java.lang.Boolean.getBoolean("quicsim.erasureGossip.noRepublish")
        val topicCounts = System.getProperty("quicsim.erasureGossip.topicCounts", "1")
            .split(',')
            .map { it.trim().toInt() }
        val runs = topicCounts.flatMap { topicCount -> seeds.flatMap { seed ->
            val result = runErasureCodedGossip(
                topologySeed = seed,
                overlaySeed = seed + 10_000,
                gossipSeedBase = seed.toLong() + 20_000L,
                waveCount = ERASURE_REPORT_WAVE_COUNT,
                publishInterval = ERASURE_REPORT_PUBLISH_INTERVAL,
                republishRecoveredSymbols = republishRecoveredSymbols,
                topicCount = topicCount,
                maxRunDuration = INITIAL_PUBLISH_DELAY +
                    ERASURE_REPORT_PUBLISH_INTERVAL * (ERASURE_REPORT_WAVE_COUNT - 1) +
                    ERASURE_REPORT_COMPLETION_GRACE
            )
            result.recoveriesByWave().map { (waveIndex, recoveries) ->
                assertEquals(NODE_COUNT - 1, recoveries.size, "seed=$seed wave=$waveIndex")
                val publishedAt = INITIAL_PUBLISH_DELAY + ERASURE_REPORT_PUBLISH_INTERVAL * waveIndex
                val p95 = percentile(recoveries.map { it.at - publishedAt }, 0.95)
                println(
                    "ERASURE_GOSSIP_RUN seed=$seed topics=$topicCount wave=$waveIndex " +
                        "republishRecoveredSymbols=$republishRecoveredSymbols p95Ms=${p95.inWholeMilliseconds}"
                )
                ErasureRecoveryRun(seed, topicCount, waveIndex, p95.inWholeMilliseconds.toDouble())
            }
        } }

        println("ERASURE_GOSSIP_SUMMARY republishRecoveredSymbols=$republishRecoveredSymbols topics wave runs minMs p50Ms meanMs maxMs stddevMs")
        runs.groupBy { it.topicCount to it.waveIndex }
            .toSortedMap(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
            .forEach { (key, waveRuns) ->
            val (topicCount, waveIndex) = key
            val p95Values = waveRuns.map { it.p95Ms }
            println(
                "ERASURE_GOSSIP_SUMMARY republishRecoveredSymbols=$republishRecoveredSymbols topics=$topicCount wave=$waveIndex runs=${waveRuns.size} " +
                    "minMs=${p95Values.minOrNull()!!.formatMs()} " +
                    "p50Ms=${percentile(p95Values, 0.50).formatMs()} " +
                    "meanMs=${p95Values.average().formatMs()} " +
                    "maxMs=${p95Values.maxOrNull()!!.formatMs()} " +
                    "stddevMs=${stddev(p95Values).formatMs()}"
            )
        }
    }

    @Test
    @Timeout(86_400)
    fun `report large network chunk and erasure dissemination`() {
        assumeTrue(
            java.lang.Boolean.getBoolean("quicsim.largeGossip.report"),
            "Set -Dquicsim.largeGossip.report=true to run this large-scale report"
        )
        check(!(LARGE_REPORT_EC_ONLY && LARGE_REPORT_CHUNK_ONLY)) {
            "quicsim.largeGossip.ecOnly and quicsim.largeGossip.chunkOnly are mutually exclusive"
        }
        System.getProperty(LARGE_GOSSIP_PROGRESS_FILE_PROPERTY)?.let {
            Files.deleteIfExists(Path.of(it))
        }

        val seeds = List(LARGE_REPORT_SEED_COUNT) { LARGE_REPORT_SEED_START + it }
        // Settle window after the final wave. Larger logical messages need a
        // proportionally longer tail, otherwise the run trips the simulation
        // limit with the last few nodes still recovering. Overridable via
        // -Dquicsim.largeGossip.settleSeconds.
        val defaultSettleWindow =
            if (LARGE_REPORT_EC_ONLY && LARGE_REPORT_NO_REPUBLISH) 1.seconds else LARGE_REPORT_SETTLE_WINDOW
        val settleWindow = LARGE_REPORT_SETTLE_SECONDS?.seconds ?: defaultSettleWindow
        val completeAfter = INITIAL_PUBLISH_DELAY +
            LARGE_REPORT_PUBLISH_INTERVAL * (LARGE_REPORT_WAVE_COUNT - 1) +
            settleWindow
        println(
            "LARGE_GOSSIP_PROGRESS phase=configuration nodes=$NODE_COUNT peersPerNode=$PEERS_PER_NODE " +
                "meshD=$MESH_D meshDLow=$MESH_D_LOW meshDHigh=$MESH_D_HIGH meshDOut=$MESH_D_OUT " +
                "waves=$LARGE_REPORT_WAVE_COUNT seeds=${seeds.size} logicalSizeKiB=$LARGE_REPORT_SIZE_KIB"
        )

        if (!LARGE_REPORT_EC_ONLY) {
            val regularRuns = seeds.map { seed ->
                println("LARGE_GOSSIP_PROGRESS scenario=regular64 phase=start seed=$seed")
                val result = runRegionalGossip(
                    messageSizeBytes = LARGE_REPORT_SIZE_KIB * 1024 / REGULAR_CHUNKS_PER_WAVE,
                    topologySeed = seed,
                    overlaySeed = seed + 10_000,
                    gossipSeedBase = seed.toLong() + 20_000L,
                    messagesPerPublisher = LARGE_REPORT_WAVE_COUNT * REGULAR_CHUNKS_PER_WAVE,
                    messagesPerWave = REGULAR_CHUNKS_PER_WAVE,
                    chunkTopicCount = LARGE_REPORT_CHUNK_TOPIC_COUNT,
                    batchPublish = true,
                    publishInterval = LARGE_REPORT_PUBLISH_INTERVAL,
                    maxRunDuration = completeAfter,
                    completeAfter = completeAfter,
                    requireCompleteDissemination = false,
                    forcePublisherSupernode = true,
                    iDontWantMinMessageSizeThreshold = 0,
                    useZeroGossipScore = true,
                    progressLabel = "regular64-seed$seed"
                )
                val lastWave = result.messageResults.last()
                check(lastWave.receipts >= LARGE_P95_RECIPIENT_COUNT) {
                    "regular64 seed=$seed only completed ${lastWave.receipts}/$NODE_COUNT recipients by $completeAfter"
                }
                val p95 = lastWave.p95!!
                println(
                    "LARGE_GOSSIP_PROGRESS scenario=regular64 phase=finished seed=$seed " +
                        "completedRecipients=${lastWave.receipts} p95Ms=${p95.inWholeMilliseconds}"
                )
                p95.inWholeMilliseconds.toDouble()
            }
            printLargeGossipSummary("regular64", regularRuns)
        }

        if (LARGE_REPORT_CHUNK_ONLY) {
            println("LARGE_GOSSIP_PROGRESS phase=skippedEc reason=chunkOnly")
            return
        }

        val ecSymbolSizeBytes = LARGE_REPORT_SIZE_KIB * 1024 / ERASURE_RECOVERY_THRESHOLD
        val ecRuns = seeds.map { seed ->
            println("LARGE_GOSSIP_PROGRESS scenario=ec128 phase=start seed=$seed")
            val seedStartedAtNanos = System.nanoTime()
            val result = runErasureCodedGossip(
                topologySeed = seed,
                overlaySeed = seed + 10_000,
                gossipSeedBase = seed.toLong() + 20_000L,
                waveCount = LARGE_REPORT_WAVE_COUNT,
                publishInterval = LARGE_REPORT_PUBLISH_INTERVAL,
                topicCount = ERASURE_SYMBOL_COUNT,
                symbolSizeBytes = ecSymbolSizeBytes,
                maxRunDuration = completeAfter,
                completeAfter = completeAfter,
                republishRecoveredSymbols = !LARGE_REPORT_NO_REPUBLISH,
                useZeroGossipScore = true,
                progressLabel = "ec128-seed$seed"
            )
            val publishedAt = INITIAL_PUBLISH_DELAY +
                LARGE_REPORT_PUBLISH_INTERVAL * (LARGE_REPORT_WAVE_COUNT - 1)
            val recoveries = result.recoveriesByWave()[LARGE_REPORT_WAVE_COUNT - 1].orEmpty()
            check(recoveries.size >= LARGE_P95_RECIPIENT_COUNT) {
                "ec128 seed=$seed only recovered ${recoveries.size}/$NODE_COUNT recipients by $completeAfter"
            }
            val p95 = percentile(recoveries.map { it.at - publishedAt }, 0.95)
            emitLargeGossipProgress(
                "LARGE_GOSSIP_PROGRESS scenario=ec128 phase=finished seed=$seed " +
                    "recoveredRecipients=${recoveries.size} p95Ms=${p95.inWholeMilliseconds} " +
                    "realDurationMs=${((System.nanoTime() - seedStartedAtNanos).toDouble() / 1_000_000).formatMs()}"
            )
            p95.inWholeMilliseconds.toDouble()
        }
        printLargeGossipSummary("ec128", ecRuns)
    }

    @Test
    @Timeout(1_200)
    fun `report partial erasure coded recovery over waves and seeds`() {
        assumeTrue(
            java.lang.Boolean.getBoolean("quicsim.partialErasureGossip.report"),
            "Set -Dquicsim.partialErasureGossip.report=true to run this slow report"
        )

        val seeds = List(PARTIAL_ERASURE_REPORT_SEED_COUNT) { PARTIAL_ERASURE_REPORT_SEED_START + it }
        val runs = seeds.flatMap { seed ->
            val result = runPartialErasureCodedGossip(
                topologySeed = seed,
                overlaySeed = seed + 10_000,
                gossipSeedBase = seed.toLong() + 20_000L,
                waveCount = PARTIAL_ERASURE_REPORT_WAVE_COUNT,
                publishInterval = PARTIAL_ERASURE_REPORT_PUBLISH_INTERVAL,
                maxRunDuration = INITIAL_PUBLISH_DELAY +
                    PARTIAL_ERASURE_REPORT_PUBLISH_INTERVAL * (PARTIAL_ERASURE_REPORT_WAVE_COUNT - 1) +
                    PARTIAL_ERASURE_REPORT_COMPLETION_GRACE
            )
            result.recoveriesByWave().map { (waveIndex, recoveries) ->
                assertEquals(NODE_COUNT - 1, recoveries.size, "seed=$seed wave=$waveIndex")
                val publishedAt = INITIAL_PUBLISH_DELAY + PARTIAL_ERASURE_REPORT_PUBLISH_INTERVAL * waveIndex
                val p95 = percentile(recoveries.map { it.at - publishedAt }, 0.95)
                println(
                    "PARTIAL_ERASURE_GOSSIP_RUN seed=$seed wave=$waveIndex " +
                        "p95Ms=${p95.inWholeMilliseconds}"
                )
                PartialErasureRecoveryRun(seed, waveIndex, p95.inWholeMilliseconds.toDouble())
            }
        }

        println("PARTIAL_ERASURE_GOSSIP_SUMMARY wave runs minMs p50Ms meanMs maxMs stddevMs")
        runs.groupBy { it.waveIndex }.toSortedMap().forEach { (waveIndex, waveRuns) ->
            val p95Values = waveRuns.map { it.p95Ms }
            println(
                "PARTIAL_ERASURE_GOSSIP_SUMMARY wave=$waveIndex runs=${waveRuns.size} " +
                    "minMs=${p95Values.minOrNull()!!.formatMs()} " +
                    "p50Ms=${percentile(p95Values, 0.50).formatMs()} " +
                    "meanMs=${p95Values.average().formatMs()} " +
                    "maxMs=${p95Values.maxOrNull()!!.formatMs()} " +
                    "stddevMs=${stddev(p95Values).formatMs()}"
            )
        }
    }

    @Test
    @Timeout(300)
    fun `report final erasure wave reception statistics`() {
        assumeTrue(
            java.lang.Boolean.getBoolean("quicsim.erasureGossip.lastWaveStatsReport"),
            "Set -Dquicsim.erasureGossip.lastWaveStatsReport=true to run this report"
        )

        val result = runErasureCodedGossip(
            topologySeed = ERASURE_REPORT_SEED_START,
            overlaySeed = ERASURE_REPORT_SEED_START + 10_000,
            gossipSeedBase = ERASURE_REPORT_SEED_START.toLong() + 20_000L,
            waveCount = ERASURE_REPORT_WAVE_COUNT,
            publishInterval = ERASURE_REPORT_PUBLISH_INTERVAL,
            maxRunDuration = INITIAL_PUBLISH_DELAY +
                ERASURE_REPORT_PUBLISH_INTERVAL * (ERASURE_REPORT_WAVE_COUNT - 1) +
                ERASURE_REPORT_COMPLETION_GRACE
        )
        val lastWaveStats = result.nodePrograms
            .drop(1)
            .map { it.receptionStats(ERASURE_REPORT_WAVE_COUNT - 1) }

        println(
            "ERASURE_GOSSIP_LAST_WAVE_STATS " +
                "seed=$ERASURE_REPORT_SEED_START wave=${ERASURE_REPORT_WAVE_COUNT - 1} " +
                "nodes=${lastWaveStats.size} " +
                "meanDifferentMessages=${lastWaveStats.map { it.differentMessages }.average().formatMs()} " +
                "meanDuplicateMessages=${lastWaveStats.map { it.duplicateMessages }.average().formatMs()} " +
                "meanDuplicateMessagesBeforeRecovery=" +
                    lastWaveStats.map { it.duplicateMessagesBeforeRecovery }.average().formatMs()
        )
        printDisseminationTrack(
            "ERASURE_GOSSIP_RECOVERY_TRACK",
            result.recoveriesByWave().getValue(ERASURE_REPORT_WAVE_COUNT - 1)
                .map { it.at - (INITIAL_PUBLISH_DELAY + ERASURE_REPORT_PUBLISH_INTERVAL * (ERASURE_REPORT_WAVE_COUNT - 1)) }
        )
        printErasureUniqueChunkProgressTrack(
            "ERASURE_GOSSIP_UNIQUE_PROGRESS",
            result.nodePrograms.drop(1).map { it.receptionProgress(ERASURE_REPORT_WAVE_COUNT - 1) },
            INITIAL_PUBLISH_DELAY + ERASURE_REPORT_PUBLISH_INTERVAL * (ERASURE_REPORT_WAVE_COUNT - 1)
        )
    }

    @Test
    @Timeout(900)
    fun `report final regular chunk wave reception statistics`() {
        assumeTrue(
            java.lang.Boolean.getBoolean("quicsim.regionalGossip.lastWaveStatsReport"),
            "Set -Dquicsim.regionalGossip.lastWaveStatsReport=true to run this report"
        )

        val result = runRegionalGossip(
            messageSizeBytes = REGULAR_CHUNK_SIZE_BYTES,
            topologySeed = ERASURE_REPORT_SEED_START,
            overlaySeed = ERASURE_REPORT_SEED_START + 10_000,
            gossipSeedBase = ERASURE_REPORT_SEED_START.toLong() + 20_000L,
            messagesPerPublisher = REGULAR_WAVE_COUNT * REGULAR_CHUNKS_PER_WAVE,
            messagesPerWave = REGULAR_CHUNKS_PER_WAVE,
            chunkTopicCount = 1,
            batchPublish = true,
            publishInterval = REGULAR_PUBLISH_INTERVAL,
            maxRunDuration = 12.minutes,
            forcePublisherSupernode = true,
            iDontWantMinMessageSizeThreshold = 0
        )
        val lastWaveStats = result.nodePrograms
            .drop(1)
            .map { it.messageReceptionStats(REGULAR_WAVE_COUNT - 1) }

        println(
            "REGULAR_CHUNK_LAST_WAVE_STATS " +
                "seed=$ERASURE_REPORT_SEED_START wave=${REGULAR_WAVE_COUNT - 1} " +
                "nodes=${lastWaveStats.size} " +
                "p95Ms=${result.messageResults.last().p95!!.inWholeMilliseconds} " +
                "meanDifferentMessages=${lastWaveStats.map { it.differentMessages }.average().formatMs()} " +
                "meanDuplicateMessages=${lastWaveStats.map { it.duplicateMessages }.average().formatMs()} " +
                "meanDuplicateMessagesBeforeRecovery=" +
                    lastWaveStats.map { it.duplicateMessagesBeforeWaveCompletion }.average().formatMs()
        )
        printDisseminationTrack(
            "REGULAR_CHUNK_RECOVERY_TRACK",
            result.completionTimes(REGULAR_WAVE_COUNT - 1, REGULAR_CHUNKS_PER_WAVE)
        )
        printRegularUniqueChunkProgressTrack(
            "REGULAR_CHUNK_UNIQUE_PROGRESS",
            result.nodePrograms.drop(1).map { it.messageReceptionProgress(REGULAR_WAVE_COUNT - 1) },
            INITIAL_PUBLISH_DELAY + REGULAR_PUBLISH_INTERVAL * (REGULAR_WAVE_COUNT - 1)
        )
    }

    @Test
    @Timeout(900)
    fun `report final regular chunk wave without IDONTWANT`() {
        assumeTrue(
            java.lang.Boolean.getBoolean("quicsim.regionalGossip.noIDontWantReport"),
            "Set -Dquicsim.regionalGossip.noIDontWantReport=true to run this report"
        )

        val result = runRegionalGossip(
            messageSizeBytes = REGULAR_CHUNK_SIZE_BYTES,
            topologySeed = ERASURE_REPORT_SEED_START,
            overlaySeed = ERASURE_REPORT_SEED_START + 10_000,
            gossipSeedBase = ERASURE_REPORT_SEED_START.toLong() + 20_000L,
            messagesPerPublisher = REGULAR_WAVE_COUNT * REGULAR_CHUNKS_PER_WAVE,
            messagesPerWave = REGULAR_CHUNKS_PER_WAVE,
            chunkTopicCount = 1,
            batchPublish = true,
            publishInterval = REGULAR_PUBLISH_INTERVAL,
            maxRunDuration = 12.minutes,
            forcePublisherSupernode = true,
            iDontWantMinMessageSizeThreshold = Int.MAX_VALUE
        )
        val lastWave = result.messageResults.last()
        val lastWaveStats = result.nodePrograms
            .drop(1)
            .map { it.messageReceptionStats(REGULAR_WAVE_COUNT - 1) }
        println(
            "REGULAR_CHUNK_NO_IDONTWANT_LAST_WAVE " +
                "seed=$ERASURE_REPORT_SEED_START wave=${REGULAR_WAVE_COUNT - 1} " +
                "receipts=${lastWave.receipts} missing=${lastWave.missing} " +
                "p95Ms=${lastWave.p95!!.inWholeMilliseconds} " +
                "meanDifferentMessages=${lastWaveStats.map { it.differentMessages }.average().formatMs()} " +
                "meanDuplicateMessages=${lastWaveStats.map { it.duplicateMessages }.average().formatMs()} " +
                "meanDuplicateMessagesBeforeRecovery=" +
                    lastWaveStats.map { it.duplicateMessagesBeforeWaveCompletion }.average().formatMs()
        )
    }

    @Test
    @Timeout(120)
    fun `one node disseminates a 512KiB gossip message to every other regional node`() {
        val result = runRegionalGossip(
            messageSizeBytes = MESSAGE_SIZE_BYTES,
            topologySeed = topologySeed,
            overlaySeed = overlaySeed,
            gossipSeedBase = gossipSeedBase
        )

        println(
            "Regional gossip 512KiB dissemination: " +
                "receipts=${result.receipts} p95=${result.p95.inWholeMilliseconds}ms"
        )
    }

    @Test
    @Timeout(900)
    fun `report regional gossip p95 dispersion by message size and seed`() {
        assumeTrue(
            java.lang.Boolean.getBoolean("quicsim.regionalGossip.dispersionReport"),
            "Set -Dquicsim.regionalGossip.dispersionReport=true to run this slow report"
        )

        val messageSizesKiB = listOf(128, 256, 512, 1024)
        val seeds = List(10) { index -> 50_000 + index }
        val results = messageSizesKiB.flatMap { sizeKiB ->
            seeds.map { seed ->
                val result = runRegionalGossip(
                    messageSizeBytes = sizeKiB * 1024,
                    topologySeed = seed,
                    overlaySeed = seed + 10_000,
                    gossipSeedBase = seed.toLong() + 20_000L
                )
                println(
                    "REGIONAL_GOSSIP_RUN " +
                        "sizeKiB=$sizeKiB seed=$seed receipts=${result.receipts} " +
                        "p95Ms=${result.p95.inWholeMilliseconds}"
                )
                DispersionRun(sizeKiB, seed, result.p95.inWholeMilliseconds.toDouble())
            }
        }

        println("REGIONAL_GOSSIP_SUMMARY sizeKiB runs minMs p50Ms meanMs p90Ms maxMs stddevMs")
        results.groupBy { it.sizeKiB }.toSortedMap().forEach { (sizeKiB, runs) ->
            val p95Values = runs.map { it.p95Ms }
            println(
                "REGIONAL_GOSSIP_SUMMARY " +
                    "sizeKiB=$sizeKiB runs=${runs.size} " +
                    "minMs=${p95Values.minOrNull()!!.formatMs()} " +
                    "p50Ms=${percentile(p95Values, 0.50).formatMs()} " +
                    "meanMs=${p95Values.average().formatMs()} " +
                    "p90Ms=${percentile(p95Values, 0.90).formatMs()} " +
                    "maxMs=${p95Values.maxOrNull()!!.formatMs()} " +
                    "stddevMs=${stddev(p95Values).formatMs()}"
            )
        }
    }

    @Test
    @Timeout(900)
    fun `report regional gossip p95 warmup over repeated messages`() {
        assumeTrue(
            java.lang.Boolean.getBoolean("quicsim.regionalGossip.warmupReport"),
            "Set -Dquicsim.regionalGossip.warmupReport=true to run this slow report"
        )

        val seeds = List(WARMUP_SEED_COUNT) { index -> WARMUP_SEED_START + index }
        val results = WARMUP_MESSAGE_SIZES_KIB.flatMap { sizeKiB ->
            val chunkSizeBytes = sizeKiB * 1024 / WARMUP_CHUNKS_PER_MESSAGE
            require(chunkSizeBytes * WARMUP_CHUNKS_PER_MESSAGE == sizeKiB * 1024) {
                "sizeKiB=$sizeKiB cannot be divided into $WARMUP_CHUNKS_PER_MESSAGE equal chunks"
            }
            seeds.flatMap { seed ->
                GossipRpcFrameStats.reset()
                val result = runRegionalGossip(
                    messageSizeBytes = chunkSizeBytes,
                    topologySeed = seed,
                    overlaySeed = seed + 10_000,
                    gossipSeedBase = seed.toLong() + 20_000L,
                    messagesPerPublisher = WARMUP_MESSAGE_COUNT * WARMUP_CHUNKS_PER_MESSAGE,
                    messagesPerWave = WARMUP_CHUNKS_PER_MESSAGE,
                    separateTopicPerMessageChunk = WARMUP_CHUNKS_USE_SEPARATE_TOPICS,
                    publishInterval = WARMUP_PUBLISH_INTERVAL,
                    maxRunDuration = WARMUP_MAX_RUN_DURATION,
                    completeAfter = WARMUP_COMPLETE_AFTER,
                    requireCompleteDissemination = false
                )
                val rpcFrameStats = GossipRpcFrameStats.snapshot()
                println(
                        "REGIONAL_GOSSIP_WARMUP_DIAGNOSTICS " +
                        "sizeKiB=$sizeKiB peersPerNode=$PEERS_PER_NODE meshD=$MESH_D meshDLow=$MESH_D_LOW chunks=$WARMUP_CHUNKS_PER_MESSAGE chunkTopics=$WARMUP_CHUNK_TOPIC_COUNT batchPublish=$BATCH_PUBLISH chunkSizeBytes=$chunkSizeBytes " +
                        "iDontWantMinSize=$I_DONT_WANT_MIN_MESSAGE_SIZE_THRESHOLD publisherSupernode=$PUBLISHER_IS_SUPERNODE seed=$seed " +
                        "gossipRpcFrames=${rpcFrameStats.rpcFrames} gossipRpcBytes=${rpcFrameStats.totalSerializedBytes} " +
                        "connectEvents=${result.routerDiagnostics.sumOf { it.connectEvents }} " +
                    "disconnectEvents=${result.routerDiagnostics.sumOf { it.disconnectEvents }} " +
                    "meshEvents=${result.routerDiagnostics.sumOf { it.meshEvents }} " +
                    "pruneEvents=${result.routerDiagnostics.sumOf { it.pruneEvents }} " +
                    "disconnectBuckets=${disconnectBuckets(result.routerDiagnostics)}"
                )
                result.printSlowRecipients(WARMUP_DETAIL_WAVE_INDEX)
                result.messageResults.map { messageResult ->
                    println(
                        "REGIONAL_GOSSIP_WARMUP_RUN " +
                            "sizeKiB=$sizeKiB chunks=$WARMUP_CHUNKS_PER_MESSAGE chunkTopics=$WARMUP_CHUNK_TOPIC_COUNT chunkSizeBytes=$chunkSizeBytes " +
                            "iDontWantMinSize=$I_DONT_WANT_MIN_MESSAGE_SIZE_THRESHOLD seed=$seed " +
                            "waveIndex=${messageResult.messageIndex} receipts=${messageResult.receipts} " +
                            "missing=${messageResult.missing} p95Ms=${messageResult.p95?.inWholeMilliseconds ?: "NA"}"
                    )
                    WarmupRun(
                        sizeKiB = sizeKiB,
                        seed = seed,
                        messageIndex = messageResult.messageIndex,
                        receipts = messageResult.receipts,
                        missing = messageResult.missing,
                        p95Ms = messageResult.p95?.inWholeMilliseconds?.toDouble()
                    )
                }
            }
        }

        println("REGIONAL_GOSSIP_WARMUP_SUMMARY sizeKiB chunks waveIndex runs fullRuns minReceipts meanReceipts p95Ms p95MinMs p95MaxMs p95MeanMs p95StddevMs")
        results.groupBy { it.sizeKiB to it.messageIndex }
            .toSortedMap(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
            .forEach { entry ->
            val (sizeKiB, messageIndex) = entry.key
            val runs = entry.value
            val p95Values = runs.mapNotNull { it.p95Ms }
            println(
                "REGIONAL_GOSSIP_WARMUP_SUMMARY " +
                    "sizeKiB=$sizeKiB chunks=$WARMUP_CHUNKS_PER_MESSAGE waveIndex=$messageIndex runs=${runs.size} " +
                    "fullRuns=${runs.count { it.missing == 0 }} " +
                    "minReceipts=${runs.minOf { it.receipts }} " +
                    "meanReceipts=${runs.map { it.receipts.toDouble() }.average().formatMs()} " +
                    "p95Ms=${p95Values.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.50).formatMs() } ?: "NA"} " +
                    "p95MinMs=${p95Values.minOrNull()?.formatMs() ?: "NA"} " +
                    "p95MaxMs=${p95Values.maxOrNull()?.formatMs() ?: "NA"} " +
                    "p95MeanMs=${p95Values.takeIf { it.isNotEmpty() }?.average()?.formatMs() ?: "NA"} " +
                    "p95StddevMs=${p95Values.takeIf { it.isNotEmpty() }?.let { stddev(it).formatMs() } ?: "NA"}"
            )
        }
    }

    private fun runErasureCodedGossip(
        topologySeed: Int,
        overlaySeed: Int,
        gossipSeedBase: Long,
        waveCount: Int = 1,
        publishInterval: Duration = Duration.ZERO,
        republishRecoveredSymbols: Boolean = true,
        topicCount: Int = 1,
        symbolSizeBytes: Int = ERASURE_SYMBOL_SIZE_BYTES,
        maxRunDuration: Duration = MAX_RUN_DURATION,
        completeAfter: Duration? = null,
        useZeroGossipScore: Boolean = false,
        progressLabel: String? = null
    ): ErasureCodedGossipResult {
        val runStartedAtNanos = System.nanoTime()
        val waveThroughputRecorder = progressLabel
            ?.takeIf { java.lang.Boolean.getBoolean("quicsim.reportProgress") }
            ?.let {
                WaveThroughputRecorder(
                    nodeCount = NODE_COUNT,
                    waveCount = waveCount,
                    initialPublishDelay = INITIAL_PUBLISH_DELAY,
                    publishInterval = publishInterval
                )
            }
        val eventSink = recordingEventSink(
            progressLabel = progressLabel,
            waveCount = waveCount,
            itemsPerNodePerWave = 1,
            reportsRecovery = true,
            publisherItemsPerWave = ERASURE_SYMBOL_COUNT,
            runStartedAtNanos = runStartedAtNanos,
            waveThroughputRecorder = waveThroughputRecorder
        )
        val nodePrograms = mutableListOf<ErasureCodedGossipNodeProgram>()
        val topology = RegionalNetworkTopologyBuilder(WORLD_DESCRIPTOR_1)
            .addRandomScenarioHosts(
                hostCount = NODE_COUNT,
                seed = topologySeed,
                hostId = IPManager.Default::getIP,
                forcedSupernodeIndexes = setOf(PUBLISHER_NODE_ID)
            )
            .build()
        val connections = randomOutboundConnections(NODE_COUNT, PEERS_PER_NODE, overlaySeed)
        val previousLogging = System.getProperty(SAMPLE_GOSSIP_LOG_PROPERTY)
        System.setProperty(SAMPLE_GOSSIP_LOG_PROPERTY, "false")

        try {
            SimulatedRunner(
                nodeFactory = object : NodeProgramFactory {
                    override fun createNode(id: SimNodeId): NodeProgram =
                        ErasureCodedGossipNodeProgram(
                            simNodeId = id,
                            connectToNodeIds = connections.getValue(id),
                            params = gossipParams(
                                messageSizeBytes = symbolSizeBytes,
                                iDontWantMinMessageSizeThreshold = 0
                            ),
                            randomSeed = gossipSeedBase + id,
                            publisherNodeId = PUBLISHER_NODE_ID,
                            symbolCount = ERASURE_SYMBOL_COUNT,
                            recoveryThreshold = ERASURE_RECOVERY_THRESHOLD,
                            symbolSizeBytes = symbolSizeBytes,
                            waveCount = waveCount,
                            topicCount = topicCount,
                            initialPublishDelay = INITIAL_PUBLISH_DELAY,
                            publishInterval = publishInterval,
                            republishRecoveredSymbols = republishRecoveredSymbols,
                            completeAfter = completeAfter,
                            useZeroGossipScore = useZeroGossipScore,
                            eventSink = eventSink
                        ).also { nodePrograms += it }
                },
                udpNetwork = topology.toUdpSimNetwork(),
                maxSimulatedRunDuration = maxRunDuration,
                latencyWindowParallelism = LATENCY_WINDOW_PARALLELISM,
                datagramPacketTraceRecorder = waveThroughputRecorder ?: DatagramPacketTraceRecorder.Noop
            ).run()
        } finally {
            if (previousLogging == null) {
                System.clearProperty(SAMPLE_GOSSIP_LOG_PROPERTY)
            } else {
                System.setProperty(SAMPLE_GOSSIP_LOG_PROPERTY, previousLogging)
            }
        }

        waveThroughputRecorder
            ?.summarizeWave(waveCount - 1, completeAfter ?: maxRunDuration)
            ?.let { throughput ->
                emitLargeGossipProgress(
                    "LARGE_GOSSIP_PROGRESS scenario=$progressLabel phase=post-recovery-throughput " +
                        "wave=${waveCount - 1} avgPreRecoveryInboundMbitPerSecPerNode=${formatRate(throughput.averagePreRecoveryInboundMbitPerSec)} " +
                        "avgPreRecoveryOutboundMbitPerSecPerNode=${formatRate(throughput.averagePreRecoveryOutboundMbitPerSec)} " +
                        "avgPostRecoveryInboundMbitPerSecPerNode=${formatRate(throughput.averagePostRecoveryInboundMbitPerSec)} " +
                        "avgPostRecoveryOutboundMbitPerSecPerNode=${formatRate(throughput.averagePostRecoveryOutboundMbitPerSec)} " +
                        "avgPreRecoveryInboundMiBPerNode=${formatVolume(throughput.averagePreRecoveryInboundBytes)} " +
                        "avgPreRecoveryOutboundMiBPerNode=${formatVolume(throughput.averagePreRecoveryOutboundBytes)} " +
                        "avgPostRecoveryInboundMiBPerNode=${formatVolume(throughput.averagePostRecoveryInboundBytes)} " +
                        "avgPostRecoveryOutboundMiBPerNode=${formatVolume(throughput.averagePostRecoveryOutboundBytes)}"
                )
            }

        assertTrue(nodePrograms.all { it.completeFuture.isDone })
        return ErasureCodedGossipResult(
            events = (eventSink as QuicScenarioEventSource).events(),
            nodePrograms = nodePrograms
        )
    }

    private fun runPartialErasureCodedGossip(
        topologySeed: Int,
        overlaySeed: Int,
        gossipSeedBase: Long,
        waveCount: Int = 1,
        publishInterval: Duration = Duration.ZERO,
        maxRunDuration: Duration = MAX_RUN_DURATION
    ): PartialErasureCodedGossipResult {
        val eventSink = RecordingQuicScenarioEventSink()
        val nodePrograms = mutableListOf<PartialErasureCodedGossipNodeProgram>()
        val topology = RegionalNetworkTopologyBuilder(WORLD_DESCRIPTOR_1)
            .addRandomScenarioHosts(
                hostCount = NODE_COUNT,
                seed = topologySeed,
                hostId = IPManager.Default::getIP,
                forcedSupernodeIndexes = setOf(PUBLISHER_NODE_ID)
            )
            .build()
        val connections = randomOutboundConnections(NODE_COUNT, PEERS_PER_NODE, overlaySeed)
        val previousLogging = System.getProperty(SAMPLE_GOSSIP_LOG_PROPERTY)
        System.setProperty(SAMPLE_GOSSIP_LOG_PROPERTY, "false")

        try {
            SimulatedRunner(
                nodeFactory = object : NodeProgramFactory {
                    override fun createNode(id: SimNodeId): NodeProgram =
                        PartialErasureCodedGossipNodeProgram(
                            simNodeId = id,
                            connectToNodeIds = connections.getValue(id),
                            params = gossipParams(messageSizeBytes = ERASURE_SYMBOL_SIZE_BYTES),
                            randomSeed = gossipSeedBase + id,
                            publisherNodeId = PUBLISHER_NODE_ID,
                            symbolCount = ERASURE_SYMBOL_COUNT,
                            recoveryThreshold = ERASURE_RECOVERY_THRESHOLD,
                            symbolSizeBytes = ERASURE_SYMBOL_SIZE_BYTES,
                            waveCount = waveCount,
                            initialPublishDelay = INITIAL_PUBLISH_DELAY,
                            publishInterval = publishInterval,
                            eventSink = eventSink
                        ).also { nodePrograms += it }
                },
                udpNetwork = topology.toUdpSimNetwork(),
                maxSimulatedRunDuration = maxRunDuration,
                latencyWindowParallelism = LATENCY_WINDOW_PARALLELISM
            ).run()
        } finally {
            if (previousLogging == null) {
                System.clearProperty(SAMPLE_GOSSIP_LOG_PROPERTY)
            } else {
                System.setProperty(SAMPLE_GOSSIP_LOG_PROPERTY, previousLogging)
            }
        }

        assertTrue(nodePrograms.all { it.completeFuture.isDone })
        return PartialErasureCodedGossipResult(
            events = (eventSink as QuicScenarioEventSource).events(),
            nodePrograms = nodePrograms
        )
    }

    private fun runRegionalGossip(
        messageSizeBytes: Int,
        topologySeed: Int,
        overlaySeed: Int,
        gossipSeedBase: Long,
        messagesPerPublisher: Int = 1,
        messagesPerWave: Int = 1,
        separateTopicPerMessageChunk: Boolean = false,
        chunkTopicCount: Int = WARMUP_CHUNK_TOPIC_COUNT,
        batchPublish: Boolean = BATCH_PUBLISH,
        publishInterval: Duration = Duration.ZERO,
        maxRunDuration: Duration = MAX_RUN_DURATION,
        completeAfter: Duration? = null,
        requireCompleteDissemination: Boolean = true,
        forcePublisherSupernode: Boolean = PUBLISHER_IS_SUPERNODE,
        iDontWantMinMessageSizeThreshold: Int = I_DONT_WANT_MIN_MESSAGE_SIZE_THRESHOLD,
        useZeroGossipScore: Boolean = false,
        progressLabel: String? = null
    ): RegionalGossipResult {
        require(!(forcePublisherSupernode && PUBLISHER_IS_EXCLUDED_FROM_SUPERNODES)) {
            "The publisher cannot be both a forced and excluded supernode"
        }
        val eventSink = recordingEventSink(progressLabel, messagesPerPublisher / messagesPerWave, messagesPerWave)
        val nodePrograms = mutableListOf<SampleGossipNodeProgram>()
        val connectToNodeIds = randomOutboundConnections(
            nodeCount = NODE_COUNT,
            peersPerNode = PEERS_PER_NODE,
            seed = overlaySeed
        )
        val topology = RegionalNetworkTopologyBuilder(scaledLatencies(WORLD_DESCRIPTOR_1, REGIONAL_LATENCY_MULTIPLIER))
            .addRandomScenarioHosts(
                hostCount = NODE_COUNT,
                seed = topologySeed,
                hostId = IPManager.Default::getIP,
                forcedSupernodeIndexes = if (forcePublisherSupernode) setOf(PUBLISHER_NODE_ID) else emptySet(),
                excludedSupernodeIndexes =
                    if (PUBLISHER_IS_EXCLUDED_FROM_SUPERNODES) setOf(PUBLISHER_NODE_ID) else emptySet()
            )
            .build()
        val previousLogging = System.getProperty(SAMPLE_GOSSIP_LOG_PROPERTY)
        System.setProperty(SAMPLE_GOSSIP_LOG_PROPERTY, "false")

        try {
            val runner = SimulatedRunner(
                nodeFactory = object : NodeProgramFactory {
                    override fun createNode(id: SimNodeId): NodeProgram =
                        SampleGossipNodeProgram(
                            simNodeId = id,
                            connectToNodeIds = connectToNodeIds.getValue(id),
                            publishersCount = PUBLISHER_COUNT,
                            params = gossipParams(messageSizeBytes, iDontWantMinMessageSizeThreshold),
                            randomSeed = gossipSeedBase + id,
                            messageSizeBytes = messageSizeBytes,
                            messagesPerPublisher = messagesPerPublisher,
                            messagesPerWave = messagesPerWave,
                            separateTopicPerMessageChunk = separateTopicPerMessageChunk,
                            chunkTopicCount = chunkTopicCount,
                            batchPublish = batchPublish,
                            initialPublishDelay = INITIAL_PUBLISH_DELAY,
                            publishInterval = publishInterval,
                            completeAfter = completeAfter,
                            useZeroGossipScore = useZeroGossipScore,
                            eventSink = eventSink
                        ).also { nodePrograms += it }
                },
                udpNetwork = topology.toUdpSimNetwork(),
                maxSimulatedRunDuration = maxRunDuration,
                latencyWindowParallelism = LATENCY_WINDOW_PARALLELISM
            )

            try {
                runner.run()
            } catch (t: Throwable) {
                println(nodePrograms.withIndex().joinToString("\n") { (index, program) ->
                    "node-$index ${program.debugState()}"
                })
                throw t
            }
        } finally {
            if (previousLogging == null) {
                System.clearProperty(SAMPLE_GOSSIP_LOG_PROPERTY)
            } else {
                System.setProperty(SAMPLE_GOSSIP_LOG_PROPERTY, previousLogging)
            }
        }

        assertTrue(
            nodePrograms.all { it.completeFuture.isDone },
            "Expected every sample gossip node program to complete"
        )

        val events = (eventSink as QuicScenarioEventSource).events()
        val routerDiagnostics = nodePrograms.map { it.routerDiagnostics() }
        val publications = messagePublications(events)
        val receipts = messageReceipts(events)
            .filter { it.publishingNodeId == PUBLISHER_NODE_ID }
        val expectedRecipients = (0 until NODE_COUNT)
            .filter { it != PUBLISHER_NODE_ID }
            .toSet()

        require(messagesPerPublisher % messagesPerWave == 0) {
            "messagesPerPublisher must be divisible by messagesPerWave"
        }
        val expectedMessageIndexes = (0 until messagesPerPublisher).toSet()
        assertEquals(
            expectedMessageIndexes,
            publications.map { it.messageIndex }.toSet()
        )
        assertEquals(
            List(messagesPerPublisher) { PUBLISHER_NODE_ID },
            publications.sortedBy { it.messageIndex }.map { it.publishingNodeId }
        )

        expectedMessageIndexes.forEach { messageIndex ->
            val messageReceipts = receipts.filter { it.messageIndex == messageIndex }
            val receivedRecipients = messageReceipts.map { it.receivingNodeId }.toSet()
            if (requireCompleteDissemination) {
                assertEquals(expectedRecipients, receivedRecipients)
                assertEquals(NODE_COUNT - 1, messageReceipts.size)
            }

        }

        val messageResults = expectedMessageIndexes
            .chunked(messagesPerWave)
            .mapIndexed { waveIndex, messageIndexes ->
                val messageIndexSet = messageIndexes.toSet()
                val wavePublishedAt = publications
                    .filter { it.messageIndex in messageIndexSet }
                    .minOf { it.publishedAt }
                val completedRecipients = receipts
                    .filter { it.messageIndex in messageIndexSet }
                    .groupBy { it.receivingNodeId }
                    .mapNotNull { (recipient, recipientReceipts) ->
                        val receivedChunkIndexes = recipientReceipts.map { it.messageIndex }.toSet()
                        if (receivedChunkIndexes == messageIndexSet) {
                            recipient to recipientReceipts.maxOf { it.receivedAt }
                        } else {
                            null
                        }
                    }
                    .toMap()
            RegionalGossipMessageResult(
                    messageIndex = waveIndex,
                    receipts = completedRecipients.size,
                    missing = expectedRecipients.size - completedRecipients.keys.size,
                    p95 = completedRecipients.values
                        .map { it - wavePublishedAt }
                        .takeIf { it.isNotEmpty() }
                        ?.let { percentile(it, 0.95) }
            )
            }
        return RegionalGossipResult(messageResults, routerDiagnostics, publications, receipts, nodePrograms)
    }

    private fun gossipParams(
        messageSizeBytes: Int,
        iDontWantMinMessageSizeThreshold: Int = I_DONT_WANT_MIN_MESSAGE_SIZE_THRESHOLD
    ): GossipParams =
        GossipParams(
            D = MESH_D,
            DLow = MESH_D_LOW,
            DHigh = MESH_D_HIGH,
            DOut = MESH_D_OUT,
            DLazy = 0,
            gossipFactor = 0.0,
            heartbeatInterval = 700.milliseconds.toJavaDuration(),
            gossipHistoryLength = 5,
            // Do not expose message IDs for lazy gossip: IHAVE and therefore IWANT are disabled.
            gossipSize = 0,
            floodPublishMaxMessageSizeThreshold = NEVER_FLOOD_PUBLISH,
            maxGossipMessageSize = messageSizeBytes * 2,
            iDontWantMinMessageSizeThreshold = iDontWantMinMessageSizeThreshold
        )

    private fun recordingEventSink(
        progressLabel: String?,
        waveCount: Int,
        itemsPerNodePerWave: Int,
        reportsRecovery: Boolean = false,
        publisherItemsPerWave: Int = itemsPerNodePerWave,
        runStartedAtNanos: Long? = null,
        waveThroughputRecorder: WaveThroughputRecorder? = null
    ): QuicScenarioEventSink =
        if (progressLabel != null && java.lang.Boolean.getBoolean("quicsim.reportProgress")) {
            ProgressRecordingEventSink(
                label = progressLabel,
                waveCount = waveCount,
                itemsPerNodePerWave = itemsPerNodePerWave,
                expectedRecipients = NODE_COUNT - PUBLISHER_COUNT,
                reportsRecovery = reportsRecovery,
                publisherItemsPerWave = publisherItemsPerWave,
                runStartedAtNanos = runStartedAtNanos,
                waveThroughputRecorder = waveThroughputRecorder
            )
        } else {
            RecordingQuicScenarioEventSink()
        }

    private class ProgressRecordingEventSink(
        private val label: String,
        private val waveCount: Int,
        private val itemsPerNodePerWave: Int,
        private val expectedRecipients: Int,
        private val reportsRecovery: Boolean,
        private val publisherItemsPerWave: Int,
        private val runStartedAtNanos: Long?,
        private val waveThroughputRecorder: WaveThroughputRecorder?
    ) : QuicScenarioEventSink, QuicScenarioEventSource {
        private val delegate = RecordingQuicScenarioEventSink()
        private val observedByWave = ConcurrentHashMap<Int, AtomicInteger>()
        private val publisherAtByWave = ConcurrentHashMap<Int, Duration>()
        private val publisherWallNanosByWave = ConcurrentHashMap<Int, Long>()
        private val completionWallNanosByWave = ConcurrentHashMap<Int, Long>()
        private val recoveryTimesByWave = ConcurrentHashMap<Int, ConcurrentLinkedQueue<Duration>>()
        private val p95ReportedWaves = ConcurrentHashMap.newKeySet<Int>()
        private val expectedPerWave = expectedRecipients * itemsPerNodePerWave
        private val progressStep = (expectedPerWave / 4).coerceAtLeast(1)

        override fun record(event: QuicScenarioEvent) {
            delegate.record(event)
            if (event is QuicScenarioEvent.GossipMessagePublished && event.nodeId == PUBLISHER_NODE_ID) {
                val waveIndex = event.messageIndex / publisherItemsPerWave
                if (waveIndex in 0 until waveCount) {
                    publisherAtByWave.putIfAbsent(waveIndex, event.at)
                    val publisherWallNanos = System.nanoTime()
                    if (publisherWallNanosByWave.putIfAbsent(waveIndex, publisherWallNanos) == null) {
                        val completedWaveThroughput =
                            if (waveIndex == 0) null else waveThroughputRecorder?.summarizeWave(waveIndex - 1, event.at)
                        when (waveIndex) {
                            0 -> runStartedAtNanos?.let { startedAtNanos ->
                                emitLargeGossipProgress(
                                    "LARGE_GOSSIP_PROGRESS scenario=$label phase=seed-ready " +
                                        "realDurationMs=${"%.1f".format(java.util.Locale.US, (publisherWallNanos - startedAtNanos).toDouble() / 1_000_000)}"
                                )
                            }
                            else -> completionWallNanosByWave[waveIndex - 1]?.let { completedAtNanos ->
                                emitLargeGossipProgress(
                                    "LARGE_GOSSIP_PROGRESS scenario=$label phase=inter-wave-advance " +
                                        "wave=${waveIndex - 1} realDurationMs=" +
                                        "${"%.1f".format(java.util.Locale.US, (publisherWallNanos - completedAtNanos).toDouble() / 1_000_000)} " +
                                        "avgPreRecoveryInboundMbitPerSecPerNode=" +
                                        "${completedWaveThroughput?.averagePreRecoveryInboundMbitPerSec?.let(::formatRate) ?: "NA"} " +
                                        "avgPreRecoveryOutboundMbitPerSecPerNode=" +
                                        "${completedWaveThroughput?.averagePreRecoveryOutboundMbitPerSec?.let(::formatRate) ?: "NA"} " +
                                        "avgPostRecoveryInboundMbitPerSecPerNode=" +
                                        "${completedWaveThroughput?.averagePostRecoveryInboundMbitPerSec?.let(::formatRate) ?: "NA"} " +
                                        "avgPostRecoveryOutboundMbitPerSecPerNode=" +
                                        "${completedWaveThroughput?.averagePostRecoveryOutboundMbitPerSec?.let(::formatRate) ?: "NA"} " +
                                        "avgPreRecoveryInboundMiBPerNode=" +
                                        "${completedWaveThroughput?.averagePreRecoveryInboundBytes?.let(::formatVolume) ?: "NA"} " +
                                        "avgPreRecoveryOutboundMiBPerNode=" +
                                        "${completedWaveThroughput?.averagePreRecoveryOutboundBytes?.let(::formatVolume) ?: "NA"} " +
                                        "avgPostRecoveryInboundMiBPerNode=" +
                                        "${completedWaveThroughput?.averagePostRecoveryInboundBytes?.let(::formatVolume) ?: "NA"} " +
                                        "avgPostRecoveryOutboundMiBPerNode=" +
                                        "${completedWaveThroughput?.averagePostRecoveryOutboundBytes?.let(::formatVolume) ?: "NA"}"
                                )
                            }
                        }
                    }
                }
            }
            val waveIndex = when (event) {
                is QuicScenarioEvent.GossipSymbolsRecovered -> event.waveIndex.takeIf { reportsRecovery }
                is QuicScenarioEvent.GossipMessageReceived ->
                    (event.messageIndex / itemsPerNodePerWave).takeIf { !reportsRecovery }
                else -> null
            } ?: return
            if (waveIndex !in 0 until waveCount) return

            val observed = observedByWave.computeIfAbsent(waveIndex) { AtomicInteger() }.incrementAndGet()
            if (reportsRecovery) {
                recoveryTimesByWave.computeIfAbsent(waveIndex) { ConcurrentLinkedQueue() }.add(event.at)
                waveThroughputRecorder?.recordRecovery(waveIndex, event.nodeId, event.at)
                if (observed == expectedRecipients && p95ReportedWaves.add(waveIndex)) {
                    val publishedAt = publisherAtByWave[waveIndex]
                    if (publishedAt != null) {
                        val recoveryDelays = recoveryTimesByWave.getValue(waveIndex)
                            .map { it - publishedAt }
                            .sorted()
                        val p95 = recoveryDelays[((recoveryDelays.size - 1) * 0.95).toInt()]
                        val realDurationMs = publisherWallNanosByWave[waveIndex]?.let {
                            (System.nanoTime() - it).toDouble() / 1_000_000
                        }
                        completionWallNanosByWave[waveIndex] = System.nanoTime()
                        emitLargeGossipProgress(
                            "LARGE_GOSSIP_PROGRESS scenario=$label phase=wave-p95 wave=$waveIndex " +
                                "recovered=$observed p95Ms=${p95.inWholeMilliseconds} " +
                                "realDurationMs=${realDurationMs?.let { "%.1f".format(java.util.Locale.US, it) } ?: "NA"}"
                        )
                    }
                }
            }
            if (observed % progressStep == 0 || observed == expectedPerWave) {
                println(
                    "LARGE_GOSSIP_PROGRESS scenario=$label phase=wave-progress wave=$waveIndex " +
                        "observed=$observed expected=$expectedPerWave atMs=${event.at.inWholeMilliseconds}"
                )
            }
        }

        override fun events(): List<QuicScenarioEvent> = delegate.events()
    }

    /**
     * Aggregates UDP payload bytes for the current dissemination wave without retaining individual packets.
     * The reported rate is the mean across all simulated application nodes, over initial publish to final recovery.
     */
    private class WaveThroughputRecorder(
        private val nodeCount: Int,
        private val waveCount: Int,
        private val initialPublishDelay: Duration,
        private val publishInterval: Duration
    ) : DatagramPacketTraceRecorder {
        private val countersByWave = ConcurrentHashMap<Int, WaveTrafficCounter>()

        fun recordRecovery(waveIndex: Int, nodeId: Int, recoveredAt: Duration) {
            counter(waveIndex).recordRecovery(nodeId, recoveredAt)
        }

        fun summarizeWave(waveIndex: Int, endAt: Duration): IndividualNodeWaveThroughput? {
            if (waveIndex !in 0 until waveCount) return null
            return countersByWave[waveIndex]?.summarize(
                publishedAt = initialPublishDelay + publishInterval * waveIndex,
                endAt = endAt,
                publisherNodeId = PUBLISHER_NODE_ID
            )
        }

        override fun record(event: DatagramPacketTraceEvent) {
            waveIndexAt(event.at)?.let { waveIndex -> counter(waveIndex).record(event) }
        }

        private fun counter(waveIndex: Int): WaveTrafficCounter =
            countersByWave.computeIfAbsent(waveIndex) { WaveTrafficCounter(nodeCount) }

        private fun waveIndexAt(at: Duration): Int? {
            val elapsed = at - initialPublishDelay
            if (elapsed < Duration.ZERO) return null
            if (publishInterval == Duration.ZERO) return 0
            val waveIndex = (elapsed.inWholeNanoseconds / publishInterval.inWholeNanoseconds).toInt()
            return waveIndex.takeIf { it in 0 until waveCount }
        }

        private class WaveTrafficCounter(nodeCount: Int) {
            private val perNode = Array(nodeCount) { NodeTrafficCounter() }

            fun record(event: DatagramPacketTraceEvent) {
                perNode.getOrNull(event.nodeId)?.record(event)
            }

            fun recordRecovery(nodeId: Int, recoveredAt: Duration) {
                perNode.getOrNull(nodeId)?.recordRecovery(recoveredAt)
            }

            fun summarize(
                publishedAt: Duration,
                endAt: Duration,
                publisherNodeId: Int
            ): IndividualNodeWaveThroughput {
                val recoveredNodes = perNode.withIndex()
                    .filter { it.index != publisherNodeId && it.value.recoveredAt != null }
                require(recoveredNodes.isNotEmpty()) { "No nodes recovered in this wave" }
                return IndividualNodeWaveThroughput(
                    averagePreRecoveryInboundMbitPerSec = recoveredNodes.map {
                        it.value.inboundRate(publishedAt, it.value.recoveredAt!!)
                    }.average(),
                    averagePreRecoveryOutboundMbitPerSec = recoveredNodes.map {
                        it.value.outboundRate(publishedAt, it.value.recoveredAt!!)
                    }.average(),
                    averagePostRecoveryInboundMbitPerSec = recoveredNodes.map {
                        it.value.inboundRate(it.value.recoveredAt!!, endAt)
                    }.average(),
                    averagePostRecoveryOutboundMbitPerSec = recoveredNodes.map {
                        it.value.outboundRate(it.value.recoveredAt!!, endAt)
                    }.average(),
                    averagePreRecoveryInboundBytes = recoveredNodes.map {
                        it.value.inboundBeforeRecoveryBytes().toDouble()
                    }.average(),
                    averagePreRecoveryOutboundBytes = recoveredNodes.map {
                        it.value.outboundBeforeRecoveryBytes().toDouble()
                    }.average(),
                    averagePostRecoveryInboundBytes = recoveredNodes.map {
                        it.value.inboundAfterRecoveryBytes().toDouble()
                    }.average(),
                    averagePostRecoveryOutboundBytes = recoveredNodes.map {
                        it.value.outboundAfterRecoveryBytes().toDouble()
                    }.average()
                )
            }
        }

        private class NodeTrafficCounter {
            private val inboundBeforeRecovery = AtomicLong()
            private val outboundBeforeRecovery = AtomicLong()
            private val inboundAfterRecovery = AtomicLong()
            private val outboundAfterRecovery = AtomicLong()
            @Volatile var recoveredAt: Duration? = null
                private set

            fun record(event: DatagramPacketTraceEvent) {
                val beforeRecovery = recoveredAt?.let { event.at <= it } ?: true
                val counter = when (event.direction) {
                    DatagramPacketTraceEvent.Direction.INBOUND ->
                        if (beforeRecovery) inboundBeforeRecovery else inboundAfterRecovery
                    DatagramPacketTraceEvent.Direction.OUTBOUND ->
                        if (beforeRecovery) outboundBeforeRecovery else outboundAfterRecovery
                }
                counter.addAndGet(event.bytes.toLong())
            }

            fun recordRecovery(recoveredAt: Duration) {
                if (this.recoveredAt == null) this.recoveredAt = recoveredAt
            }

            fun inboundRate(start: Duration, end: Duration): Double =
                rate(if (start == recoveredAt) inboundAfterRecovery.get() else inboundBeforeRecovery.get(), start, end)

            fun outboundRate(start: Duration, end: Duration): Double =
                rate(if (start == recoveredAt) outboundAfterRecovery.get() else outboundBeforeRecovery.get(), start, end)

            fun inboundBeforeRecoveryBytes(): Long = inboundBeforeRecovery.get()
            fun outboundBeforeRecoveryBytes(): Long = outboundBeforeRecovery.get()
            fun inboundAfterRecoveryBytes(): Long = inboundAfterRecovery.get()
            fun outboundAfterRecoveryBytes(): Long = outboundAfterRecovery.get()

            private fun rate(bytes: Long, start: Duration, end: Duration): Double {
                val durationSeconds = (end - start).inWholeNanoseconds / 1_000_000_000.0
                require(durationSeconds > 0) { "Traffic window has a non-positive simulated duration" }
                return bytes.toDouble() * Byte.SIZE_BITS / durationSeconds / 1_000_000
            }
        }
    }

    private data class IndividualNodeWaveThroughput(
        val averagePreRecoveryInboundMbitPerSec: Double,
        val averagePreRecoveryOutboundMbitPerSec: Double,
        val averagePostRecoveryInboundMbitPerSec: Double,
        val averagePostRecoveryOutboundMbitPerSec: Double,
        val averagePreRecoveryInboundBytes: Double,
        val averagePreRecoveryOutboundBytes: Double,
        val averagePostRecoveryInboundBytes: Double,
        val averagePostRecoveryOutboundBytes: Double
    )

    private fun printLargeGossipSummary(scenario: String, p95Values: List<Double>) {
        println(
            "LARGE_GOSSIP_SUMMARY scenario=$scenario runs=${p95Values.size} " +
                "minMs=${p95Values.minOrNull()!!.formatMs()} " +
                "p50Ms=${percentile(p95Values, 0.50).formatMs()} " +
                "meanMs=${p95Values.average().formatMs()} " +
                "maxMs=${p95Values.maxOrNull()!!.formatMs()} " +
                "stddevMs=${stddev(p95Values).formatMs()}"
        )
    }

    private fun <R> scaledLatencies(
        descriptor: RegionalNetworkDescriptor<R>,
        multiplier: Int
    ): RegionalNetworkDescriptor<R> {
        require(multiplier > 0) { "Latency multiplier must be positive, got $multiplier" }
        return descriptor.copy(
            routerLatency = { from, to -> descriptor.routerLatency(from, to) * multiplier },
            accessLatency = { region -> descriptor.accessLatency(region) * multiplier }
        )
    }

    private fun randomOutboundConnections(
        nodeCount: Int,
        peersPerNode: Int,
        seed: Int
    ): Map<SimNodeId, List<SimNodeId>> {
        require(peersPerNode in 0 until nodeCount) {
            "peersPerNode must be in [0, $nodeCount), got $peersPerNode"
        }

        val random = Random(seed)
        return (0 until nodeCount).associateWith { nodeId ->
            (0 until nodeCount)
                .filter { it != nodeId }
                .shuffled(random)
                .take(peersPerNode)
        }
    }

    private fun percentile(values: List<Duration>, percentile: Double): Duration {
        require(values.isNotEmpty()) { "values must not be empty" }
        require(percentile in 0.0..1.0) { "percentile must be in [0, 1]" }
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * percentile).toInt()
        return sorted[index]
    }

    private fun percentile(values: List<Double>, percentile: Double): Double {
        require(values.isNotEmpty()) { "values must not be empty" }
        require(percentile in 0.0..1.0) { "percentile must be in [0, 1]" }
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * percentile).toInt()
        return sorted[index]
    }

    private fun stddev(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean).pow(2) } / values.size)
    }

    private fun disconnectBuckets(diagnostics: List<SampleGossipNodeProgram.RouterDiagnostics>): String {
        val publishTimes = List(WARMUP_MESSAGE_COUNT) { index ->
            INITIAL_PUBLISH_DELAY + WARMUP_PUBLISH_INTERVAL * index
        }
        val buckets = IntArray(WARMUP_MESSAGE_COUNT + 1)
        diagnostics.asSequence()
            .flatMap { it.disconnectEventTimes.asSequence() }
            .forEach { disconnectedAt ->
                val bucket = publishTimes.indexOfFirst { disconnectedAt < it }
                    .takeUnless { it == -1 }
                    ?: WARMUP_MESSAGE_COUNT
                buckets[bucket]++
            }

        val labels = listOf("before0") +
            (1 until WARMUP_MESSAGE_COUNT).map { index -> "${index - 1}-${index}" } +
            listOf("after${WARMUP_MESSAGE_COUNT - 1}")
        return labels.zip(buckets.toList()).joinToString(",") { (label, count) -> "$label:$count" }
    }

    /** Prints the simulated time at which the 2nd, 3rd, … recipient finishes a wave. */
    private fun printDisseminationTrack(label: String, completionTimes: List<Duration>) {
        completionTimes.sorted().drop(1).forEachIndexed { index, completionAt ->
            println("$label recovered=${index + 2} atMs=${completionAt.inWholeMilliseconds}")
        }
    }

    private fun printErasureUniqueChunkProgressTrack(
        label: String,
        progress: List<ErasureCodedGossipNodeProgram.ReceptionProgress>,
        publishedAt: Duration
    ) = printUniqueChunkProgressTrack(
        label,
        progress.map { it.uniqueReceptionTimeBySymbol },
        progress.map { it.duplicateReceptionTimes },
        publishedAt
    )

    private fun printRegularUniqueChunkProgressTrack(
        label: String,
        progress: List<SampleGossipNodeProgram.GossipWaveReceptionProgress>,
        publishedAt: Duration
    ) = printUniqueChunkProgressTrack(
        label,
        progress.map { it.uniqueReceptionTimeByMessageIndex },
        progress.map { it.duplicateReceptionTimes },
        publishedAt
    )

    /**
     * For each distinct-chunk threshold, reports the point when that many chunks have reached at
     * least one recipient anywhere in the network, and all duplicate deliveries observed up to then.
     */
    private fun printUniqueChunkProgressTrack(
        label: String,
        uniqueReceptionTimesByNode: List<Map<Int, Duration>>,
        duplicateReceptionTimesByNode: List<List<Duration>>,
        publishedAt: Duration
    ) {
        (1..10).forEach { uniqueChunks ->
            val firstReceptionAtByChunk = uniqueReceptionTimesByNode
                .flatMap { it.keys }
                .toSet()
                .mapNotNull { chunkIndex ->
                    uniqueReceptionTimesByNode.mapNotNull { it[chunkIndex] }
                        .minOrNull()
                }
                .sorted()
            require(firstReceptionAtByChunk.size >= uniqueChunks) {
                "Only ${firstReceptionAtByChunk.size} chunks reached a recipient; cannot report $uniqueChunks"
            }
            val globalDisseminationAt = firstReceptionAtByChunk[uniqueChunks - 1]
            val duplicateDeliveries = duplicateReceptionTimesByNode.sumOf { duplicateTimes ->
                duplicateTimes.count { it <= globalDisseminationAt }
            }
            println(
                "$label globallyDisseminatedChunks=$uniqueChunks atMs=${(globalDisseminationAt - publishedAt).inWholeMilliseconds} " +
                    "totalDuplicateDeliveries=$duplicateDeliveries"
            )
        }
    }

    private fun Double.formatMs(): String =
        "%.1f".format(java.util.Locale.US, this)

    private fun messageReceipts(events: List<QuicScenarioEvent>): List<MessageReceipt> =
        events.filterIsInstance<QuicScenarioEvent.GossipMessageReceived>()
            .map {
                MessageReceipt(
                    receivedAt = it.at,
                    receivingNodeId = it.nodeId,
                    publishingNodeId = it.publisherNodeId,
                    messageIndex = it.messageIndex
                )
            }
            .sortedWith(compareBy({ it.receivedAt }, { it.receivingNodeId }, { it.publishingNodeId }, { it.messageIndex }))

    private fun messagePublications(events: List<QuicScenarioEvent>): List<MessagePublication> =
        events.filterIsInstance<QuicScenarioEvent.GossipMessagePublished>()
            .map {
                MessagePublication(
                    publishedAt = it.at,
                    publishingNodeId = it.nodeId,
                    messageIndex = it.messageIndex
                )
            }
            .sortedWith(compareBy({ it.publishedAt }, { it.publishingNodeId }, { it.messageIndex }))

    private data class MessageReceipt(
        val receivedAt: Duration,
        val receivingNodeId: SimNodeId,
        val publishingNodeId: SimNodeId,
        val messageIndex: Int
    )

    private data class MessagePublication(
        val publishedAt: Duration,
        val publishingNodeId: SimNodeId,
        val messageIndex: Int
    )

    private data class ErasureCodedGossipResult(
        val events: List<QuicScenarioEvent>,
        val nodePrograms: List<ErasureCodedGossipNodeProgram>
    ) {
        fun recoveriesByWave(): Map<Int, List<QuicScenarioEvent.GossipSymbolsRecovered>> =
            events.filterIsInstance<QuicScenarioEvent.GossipSymbolsRecovered>()
                .groupBy { it.waveIndex }
    }

    private data class PartialErasureCodedGossipResult(
        val events: List<QuicScenarioEvent>,
        val nodePrograms: List<PartialErasureCodedGossipNodeProgram>
    ) {
        fun recoveriesByWave(): Map<Int, List<QuicScenarioEvent.GossipSymbolsRecovered>> =
            events.filterIsInstance<QuicScenarioEvent.GossipSymbolsRecovered>()
                .groupBy { it.waveIndex }
    }

    private data class RegionalGossipResult(
        val messageResults: List<RegionalGossipMessageResult>,
        val routerDiagnostics: List<SampleGossipNodeProgram.RouterDiagnostics>,
        val publications: List<MessagePublication>,
        val receiptEvents: List<MessageReceipt>,
        val nodePrograms: List<SampleGossipNodeProgram>
    ) {
        val receipts: Int get() = messageResults.single().receipts
        val p95: Duration get() = messageResults.single().p95!!

        fun completionTimes(waveIndex: Int, chunksPerWave: Int): List<Duration> {
            val messageIndexes =
                (waveIndex * chunksPerWave until (waveIndex + 1) * chunksPerWave).toSet()
            val publishedAt = publications
                .filter { it.messageIndex in messageIndexes }
                .minOf { it.publishedAt }
            return receiptEvents
                .filter { it.messageIndex in messageIndexes }
                .groupBy { it.receivingNodeId }
                .values
                .mapNotNull { nodeReceipts ->
                    nodeReceipts
                        .map { it.messageIndex }
                        .toSet()
                        .containsAll(messageIndexes)
                        .takeIf { it }
                        ?.let { nodeReceipts.maxOf { it.receivedAt } - publishedAt }
                }
        }

        fun printSlowRecipients(waveIndex: Int) {
            if (waveIndex !in messageResults.indices) return

            val messageIndexes = (waveIndex * WARMUP_CHUNKS_PER_MESSAGE until (waveIndex + 1) * WARMUP_CHUNKS_PER_MESSAGE).toSet()
            val publishedAt = publications
                .filter { it.messageIndex in messageIndexes }
                .minOf { it.publishedAt }
            receiptEvents
                .filter { it.messageIndex in messageIndexes }
                .groupBy { it.receivingNodeId }
                .mapNotNull { (nodeId, nodeReceipts) ->
                    val receivedChunks = nodeReceipts.map { it.messageIndex }.toSet()
                    if (receivedChunks != messageIndexes) return@mapNotNull null
                    val completedAt = nodeReceipts.maxOf { it.receivedAt }
                    val lastChunks = nodeReceipts
                        .filter { it.receivedAt == completedAt }
                        .map { it.messageIndex }
                        .sorted()
                    SlowRecipient(nodeId, completedAt - publishedAt, lastChunks)
                }
                .sortedByDescending { it.completionDelay }
                .take(5)
                .forEach { slowRecipient ->
                    println(
                        "REGIONAL_GOSSIP_WARMUP_SLOW_RECIPIENT " +
                            "waveIndex=$waveIndex node=${slowRecipient.nodeId} " +
                            "completionMs=${slowRecipient.completionDelay.inWholeMilliseconds} " +
                            "lastChunks=${slowRecipient.lastChunks}"
                    )
                }
        }
    }

    private data class SlowRecipient(
        val nodeId: SimNodeId,
        val completionDelay: Duration,
        val lastChunks: List<Int>
    )

    private data class RegionalGossipMessageResult(
        val messageIndex: Int,
        val receipts: Int,
        val missing: Int,
        val p95: Duration?
    )

    private data class DispersionRun(
        val sizeKiB: Int,
        val seed: Int,
        val p95Ms: Double
    )

    private data class ErasureRecoveryRun(
        val seed: Int,
        val topicCount: Int,
        val waveIndex: Int,
        val p95Ms: Double
    )

    private data class PartialErasureRecoveryRun(
        val seed: Int,
        val waveIndex: Int,
        val p95Ms: Double
    )

    private data class WarmupRun(
        val sizeKiB: Int,
        val seed: Int,
        val messageIndex: Int,
        val receipts: Int,
        val missing: Int,
        val p95Ms: Double?
    )

    private companion object {
        val NODE_COUNT = Integer.getInteger("quicsim.regionalGossip.nodeCount", 65)
        val PEERS_PER_NODE = Integer.getInteger("quicsim.regionalGossip.peersPerNode", 10)
        const val PUBLISHER_COUNT = 1
        const val PUBLISHER_NODE_ID = 0
        const val MESSAGE_SIZE_BYTES = 512 * 1024
        const val ERASURE_SYMBOL_COUNT = 128
        const val ERASURE_RECOVERY_THRESHOLD = 64
        val ERASURE_SYMBOL_SIZE_BYTES = Integer.getInteger("quicsim.erasureGossip.symbolSizeBytes", 8 * 1024)
        const val REGULAR_CHUNK_SIZE_BYTES = 8 * 1024
        const val REGULAR_CHUNKS_PER_WAVE = 64
        const val REGULAR_WAVE_COUNT = 20
        val REGULAR_PUBLISH_INTERVAL = 30.seconds
        val ERASURE_REPORT_WAVE_COUNT = Integer.getInteger("quicsim.erasureGossip.waveCount", 20)
        val ERASURE_REPORT_SEED_COUNT = Integer.getInteger("quicsim.erasureGossip.seedCount", 10)
        val ERASURE_REPORT_SEED_START = Integer.getInteger("quicsim.erasureGossip.seedStart", 70_000)
        val ERASURE_REPORT_PUBLISH_INTERVAL =
            Integer.getInteger("quicsim.erasureGossip.waveIntervalSeconds", 30).seconds
        val ERASURE_REPORT_COMPLETION_GRACE = 2.minutes
        val PARTIAL_ERASURE_REPORT_WAVE_COUNT = Integer.getInteger("quicsim.partialErasureGossip.waveCount", 20)
        val PARTIAL_ERASURE_REPORT_SEED_COUNT = Integer.getInteger("quicsim.partialErasureGossip.seedCount", 10)
        val PARTIAL_ERASURE_REPORT_SEED_START = Integer.getInteger("quicsim.partialErasureGossip.seedStart", 70_000)
        val PARTIAL_ERASURE_REPORT_PUBLISH_INTERVAL =
            Integer.getInteger("quicsim.partialErasureGossip.waveIntervalSeconds", 30).seconds
        val PARTIAL_ERASURE_REPORT_COMPLETION_GRACE = 2.minutes
        const val LATENCY_WINDOW_PARALLELISM = 8
        const val SAMPLE_GOSSIP_LOG_PROPERTY = "quicsim.sampleGossip.log"
        const val LARGE_GOSSIP_PROGRESS_FILE_PROPERTY = "quicsim.largeGossip.progressFile"
        private val largeGossipProgressFileLock = Any()

        fun emitLargeGossipProgress(line: String) {
            println(line)
            System.getProperty(LARGE_GOSSIP_PROGRESS_FILE_PROPERTY)?.let { path ->
                synchronized(largeGossipProgressFileLock) {
                    Files.writeString(
                        Path.of(path),
                        "$line\n",
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                    )
                }
            }
        }

        fun formatRate(value: Double): String = "%.3f".format(java.util.Locale.US, value)

        fun formatVolume(value: Double): String =
            "%.3f".format(java.util.Locale.US, value / (1024 * 1024))

        val MESH_D = Integer.getInteger("quicsim.regionalGossip.meshD", 3)
        val MESH_D_LOW = Integer.getInteger("quicsim.regionalGossip.meshDLow", 2)
        val MESH_D_HIGH = Integer.getInteger("quicsim.regionalGossip.meshDHigh", 4)
        val MESH_D_OUT = Integer.getInteger("quicsim.regionalGossip.meshDOut", 1)
        val LARGE_REPORT_WAVE_COUNT = Integer.getInteger("quicsim.largeGossip.waveCount", 10)
        val LARGE_REPORT_SEED_COUNT = Integer.getInteger("quicsim.largeGossip.seedCount", 10)
        val LARGE_REPORT_SEED_START = Integer.getInteger("quicsim.largeGossip.seedStart", 70_000)
        val LARGE_REPORT_EC_ONLY = java.lang.Boolean.getBoolean("quicsim.largeGossip.ecOnly")
        val LARGE_REPORT_CHUNK_ONLY = java.lang.Boolean.getBoolean("quicsim.largeGossip.chunkOnly")
        val LARGE_REPORT_NO_REPUBLISH = java.lang.Boolean.getBoolean("quicsim.largeGossip.noRepublish")
        val LARGE_REPORT_SIZE_KIB = Integer.getInteger("quicsim.largeGossip.sizeKiB", 256)
        val LARGE_REPORT_SETTLE_SECONDS: Int? = Integer.getInteger("quicsim.largeGossip.settleSeconds")

        /**
         * Number of topics ("subnets") the [REGULAR_CHUNKS_PER_WAVE] chunks of a wave are
         * spread across, round-robin. Must divide the chunk count evenly.
         */
        val LARGE_REPORT_CHUNK_TOPIC_COUNT =
            Integer.getInteger("quicsim.largeGossip.chunkTopicCount", 2)
        val LARGE_REPORT_PUBLISH_INTERVAL = 30.seconds
        val LARGE_REPORT_SETTLE_WINDOW = 5.seconds
        val LARGE_P95_RECIPIENT_COUNT = ((NODE_COUNT - PUBLISHER_COUNT) * 0.95).toInt()
        val REGIONAL_LATENCY_MULTIPLIER = Integer.getInteger("quicsim.regionalGossip.latencyMultiplier", 1)
        val INITIAL_PUBLISH_DELAY = 10.seconds
        val MAX_RUN_DURATION = 2.minutes
        val WARMUP_MESSAGE_COUNT = Integer.getInteger("quicsim.regionalGossip.warmupMessageCount", 10)
        val WARMUP_SEED_COUNT = Integer.getInteger("quicsim.regionalGossip.warmupSeedCount", 10)
        val WARMUP_SEED_START = Integer.getInteger("quicsim.regionalGossip.warmupSeedStart", 60_000)
        val WARMUP_DETAIL_WAVE_INDEX = Integer.getInteger("quicsim.regionalGossip.warmupDetailWaveIndex", -1)
        val WARMUP_CHUNKS_PER_MESSAGE =
            Integer.getInteger("quicsim.regionalGossip.warmupChunksPerMessage", 1)
        val WARMUP_CHUNKS_USE_SEPARATE_TOPICS =
            java.lang.Boolean.getBoolean("quicsim.regionalGossip.warmupChunkTopics")
        val WARMUP_CHUNK_TOPIC_COUNT = Integer.getInteger(
            "quicsim.regionalGossip.warmupChunkTopicCount",
            if (WARMUP_CHUNKS_USE_SEPARATE_TOPICS) WARMUP_CHUNKS_PER_MESSAGE else 1
        )
        val BATCH_PUBLISH = java.lang.Boolean.getBoolean("quicsim.regionalGossip.batchPublish")
        val PUBLISHER_IS_SUPERNODE = java.lang.Boolean.getBoolean("quicsim.regionalGossip.publisherSupernode")
        val PUBLISHER_IS_EXCLUDED_FROM_SUPERNODES =
            java.lang.Boolean.getBoolean("quicsim.regionalGossip.excludePublisherSupernode")
        val I_DONT_WANT_MIN_MESSAGE_SIZE_THRESHOLD =
            Integer.getInteger("quicsim.regionalGossip.iDontWantMinMessageSizeThreshold", Int.MAX_VALUE)
        val WARMUP_MESSAGE_SIZES_KIB =
            System.getProperty("quicsim.regionalGossip.warmupMessageSizesKiB", "128")
                .split(',')
                .map { it.trim().toInt() }
        val WARMUP_PUBLISH_INTERVAL =
            Integer.getInteger("quicsim.regionalGossip.warmupIntervalSeconds", 60).seconds
        val WARMUP_COMPLETE_AFTER = 10.minutes
        val WARMUP_MAX_RUN_DURATION = 11.minutes
        val topologySeed = Integer.getInteger("quicsim.regionalGossip.topologySeed", 1)
        val overlaySeed = Integer.getInteger("quicsim.regionalGossip.overlaySeed", 7_123)
        val gossipSeedBase = java.lang.Long.getLong("quicsim.regionalGossip.gossipSeedBase", 19_000L)
    }
}
