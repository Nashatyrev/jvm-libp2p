package io.libp2p.example.dc

import io.libp2p.example.dc.DcSlotMessageType.BLOB_COLUMN
import io.libp2p.example.dc.DcSlotMessageType.FFG_ATTESTATION
import io.libp2p.example.dc.DcSlotMessageType.GOLDFISH_ATTESTATION
import io.libp2p.example.dc.DcSlotMessageType.PAYLOAD_CHUNK
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * FFG votes on the lowest-priority lane must not slow down what matters within the slot: payload
 * chunks, blob columns and goldfish votes.
 *
 * Every run carries the same traffic. What varies is when the FFG votes go out:
 * - apart: at [FFG_APART], once everything else has landed. The reference: same subscriptions,
 *   meshes and bytes, no overlap.
 * - together: in the same [BURST] as everything else.
 *
 * and how they travel: on the one stream per peer everything shares, or on a lane of their own below
 * everything else. Interference is together minus apart within one setup, so the extra streams of
 * the lanes setup don't leak into it; and each figure is a mean over [SEEDS], since one run's meshes
 * and publishers move a p99 by more than the effect.
 *
 * Home links are residential (50/25 Mbps), so uplinks are the bottleneck, and the FFG volume is sized
 * to keep them busy for about a second. The shared setup must show more than [TOLERANCE] of
 * interference -- otherwise the scenario has no contention and proves nothing -- and the lanes setup
 * must stay within it.
 *
 * As of 2026-10-23 the second half fails. Lanes keep blobs within the tolerance and cut goldfish-vote
 * interference roughly in half, but at p90 goldfish votes still lose ~75 ms and payload chunks
 * ~125-155 ms. Stream priority orders the streams of one connection, while a node's uplink is shared
 * by all its connections, so FFG votes to one peer still compete with goldfish votes to another.
 * Tagged as a simulation study until that is addressed:
 * `./gradlew :examples:dc:simulation --tests "*DcStreamLanesScenarioTest*" -Ddc.heap=8g -Ddc.directMemory=8g`
 */
@Tag("simulation")
class DcStreamLanesScenarioTest {

    private val ffgLowest = DcStreamLanes.parse("payload-c,blob,ac-vote|ffg-vote")

    @Test
    fun `ffg votes on the lowest lane do not delay payload, blobs and goldfish votes`() {
        // setup -> timing -> one report per seed
        val reports = withBigFlowControlWindows {
            mapOf("shared" to null, "lanes" to ffgLowest).mapValues { (_, lanes) ->
                mapOf(
                    "apart" to SEEDS.map { run(it, FFG_APART, lanes) },
                    "together" to SEEDS.map { run(it, BURST, lanes) }
                )
            }
        }

        reports.values.flatMap { it.values }.flatten().forEach { report ->
            (PROTECTED + FFG_ATTESTATION).forEach { type ->
                assertThat(report.stats(type).deliveryRatio)
                    .describedAs("$type delivery ratio; latencies mean nothing if messages went missing")
                    .isEqualTo(1.0)
            }
        }

        fun interference(setup: String, type: DcSlotMessageType, percentile: DcDeliveryStats.() -> Duration?) =
            reports.getValue(setup).let { it.getValue("together").meanMs(type, percentile) - it.getValue("apart").meanMs(type, percentile) }

        val percentiles = listOf<Pair<String, DcDeliveryStats.() -> Duration?>>("p50" to { p50 }, "p90" to { p90 }, "p99" to { p99 })
        fun row(values: (DcDeliveryStats.() -> Duration?) -> Double, signed: Boolean) =
            percentiles.joinToString("/") { (_, percentile) -> (if (signed) "%+.0f" else "%.0f").format(values(percentile)) }

        println("mean over ${SEEDS.size} seeds, p50/p90/p99 ms")
        println("%-10s %16s %16s %16s %16s %20s %20s".format("", "shared apart", "shared together", "lanes apart", "lanes together", "shared interference", "lanes interference"))
        (PROTECTED + FFG_ATTESTATION).forEach { type ->
            val cells = reports.values.flatMap { it.values }.map { runs -> row({ runs.meanMs(type, it) }, signed = false) } +
                listOf("shared", "lanes").map { setup -> row({ interference(setup, type, it) }, signed = true) }
            println("%-10s %16s %16s %16s %16s %20s %20s".format(type, *cells.toTypedArray()))
        }

        // p99 is printed but not judged: a tail of under a thousand deliveries per run moves by more
        // between reruns of the same seeds than the effect being measured
        val judged = percentiles.take(2)
        PROTECTED.forEach { type ->
            assertThat(interference("shared", type) { p90 })
                .describedAs("$type p90 interference on a shared stream: without it the scenario has no contention to remove")
                .isGreaterThan(TOLERANCE.inWholeMilliseconds.toDouble())
        }
        assertSoftly { softly ->
            PROTECTED.forEach { type ->
                judged.forEach { (name, percentile) ->
                    softly.assertThat(interference("lanes", type, percentile))
                        .describedAs("$type $name interference with FFG votes on the lowest lane, ms")
                        .isLessThanOrEqualTo(TOLERANCE.inWholeMilliseconds.toDouble())
                }
            }
        }
    }

    private fun run(seed: Long, ffgOffset: Duration, lanes: DcStreamLanes?): DcRunReport {
        val network = DcNetworkBuilder
            .world(randomSeed = 1, subnetCounts = mapOf(BLOB_COLUMN to BLOB_SUBNETS, FFG_ATTESTATION to FFG_SUBNETS))
            .addGroup(count = POOLS) {
                name = "pools"
                spreadOverRegions()
                bandwidth = Bandwidths.DATACENTER
                validators = 0
                peers = PEERS
                allMessageSubnets(BLOB_COLUMN)
                allMessageSubnets(FFG_ATTESTATION)
            }
            .addGroup(count = HOMES) {
                name = "home"
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                validators = FFG_VOTES_PER_HOME
                peers = PEERS
                randomMessageSubnets(BLOB_COLUMN, count = BLOB_SUBNETS / 2)
                randomMessageSubnets(FFG_ATTESTATION, count = 1)
            }
            .build()
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = seed)
        val config = DcRunConfig(
            // A first slot of the same traffic, left out of the figures: a cold QUIC window takes
            // seconds to open, and FFG traffic in the same burst would warm it as it competed
            slotCount = 2,
            warmupSlots = 1,
            settle = 8.seconds,
            messages = listOf(
                DcSlotMessageConfig(
                    type = PAYLOAD_CHUNK,
                    sizeBytes = 8 * 1024,
                    publishOffset = BURST,
                    publisherGroups = setOf("pools"),
                    messagesPerSlot = 32
                ),
                DcSlotMessageConfig(
                    type = BLOB_COLUMN,
                    sizeBytes = 8 * 1024,
                    publishOffset = BURST,
                    publisherGroups = setOf("pools"),
                    messagesPerSlot = 32,
                    topics = DcSlotMessageTopics.Subnets(BLOB_SUBNETS)
                ),
                DcSlotMessageConfig(
                    type = GOLDFISH_ATTESTATION,
                    sizeBytes = 240,
                    publishOffset = BURST,
                    publisherGroups = setOf("home"),
                    publisherSelection = DcPublisherSelection.RANDOM_NODES,
                    messagesPerSlot = HOMES
                ),
                DcSlotMessageConfig(
                    type = FFG_ATTESTATION,
                    sizeBytes = FFG_VOTE_SIZE,
                    publishOffset = ffgOffset,
                    publisherSelection = DcPublisherSelection.ALL_VALIDATORS,
                    topics = DcSlotMessageTopics.Subnets(FFG_SUBNETS)
                )
            ),
            streamLanes = lanes,
            randomSeed = seed
        )
        return DcScenario.run(network, graph, config)
    }

    /**
     * quiche starts every stream's receive window at 32 KiB once the initial credit is spent, and
     * grows it only as the stream carries traffic. Lanes split one stream's traffic across several,
     * so each lane's window warms up on its own share and a lane is slower than the one stream it
     * replaced for reasons unrelated to priority. Credit large enough to last the run takes flow
     * control out of the comparison.
     */
    private fun <T> withBigFlowControlWindows(block: () -> T): T {
        val properties = mapOf(
            "libp2p.quic.initialMaxStreamData" to (16L shl 20),
            "libp2p.quic.initialMaxData" to (64L shl 20)
        )
        properties.forEach { (key, value) -> System.setProperty(key, value.toString()) }
        try {
            return block()
        } finally {
            properties.keys.forEach { System.clearProperty(it) }
        }
    }

    private fun DcRunReport.stats(type: DcSlotMessageType) = messages.getValue(type).overall

    private fun List<DcRunReport>.meanMs(type: DcSlotMessageType, percentile: DcDeliveryStats.() -> Duration?) =
        map { it.stats(type).percentile()!!.inWholeMicroseconds / 1000.0 }.average()

    companion object {
        private val PROTECTED = listOf(PAYLOAD_CHUNK, BLOB_COLUMN, GOLDFISH_ATTESTATION)
        private val SEEDS = (1L..8L).toList()
        private val BURST = 1.seconds
        private val FFG_APART = 8.seconds
        private val TOLERANCE = 50.milliseconds
        private const val POOLS = 4
        private const val HOMES = 24
        private const val PEERS = 12
        private const val BLOB_SUBNETS = 8
        private const val FFG_SUBNETS = 4
        private const val FFG_VOTES_PER_HOME = 40
        private const val FFG_VOTE_SIZE = 2400
    }
}
