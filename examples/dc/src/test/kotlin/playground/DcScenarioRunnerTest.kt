package playground

import io.libp2p.example.dc.Bandwidths
import io.libp2p.example.dc.DcNetworkBuilder
import io.libp2p.example.dc.DcPublisherSelection
import io.libp2p.example.dc.DcRunConfig
import io.libp2p.example.dc.DcRunReport
import io.libp2p.example.dc.DcScenario
import io.libp2p.example.dc.DcSlotMessageConfig
import io.libp2p.example.dc.DcSlotMessageTopics
import io.libp2p.example.dc.DcSlotMessageType
import io.libp2p.example.dc.peerGraph
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.builders.GossipParamsBuilder
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.ContinentRegion
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Scenario runners. One `@Test` per scenario file; the file supplies the parameters, the defaults in
 * [io.libp2p.example.dc.DcRunConfig] supply the rest.
 *
 * Tagged `simulation` and excluded from the normal `test` task, since these are studies rather than
 * tests — a 1000-node run is minutes to hours, not seconds.
 *
 * ```
 * ./gradlew :examples:dc:simulation --tests "*DcScenarioRunnerTest.attestation 1000 residential*"
 * ```
 *
 * To vary a run, copy the YAML rather than editing it in place: each file is then a durable record
 * of how a particular result was produced.
 */
@Tag("simulation")
class DcScenarioRunnerTest {

    @Test
    fun `small network`() {
        val network = DcNetworkBuilder
            .world(
                randomSeed = 1,
                subnetCounts = mapOf(
                    DcSlotMessageType.PAYLOAD_CHUNK to 64,
                    DcSlotMessageType.BLOB_COLUMN to 128,
                    DcSlotMessageType.FFG_ATTESTATION to 64
                )
            )
            .defaults {
                // Spread payload chunks across their own 64 meshes.
                allMessageSubnets(DcSlotMessageType.PAYLOAD_CHUNK)
            }
            .addGroup(count = 10) {
                // validator pools
                name = "validator-pools"
                regionWeights = mapOf(
                    ContinentRegion.EUROPE to 0.4,
                    ContinentRegion.US_EAST to 0.4,
                    ContinentRegion.US_WEST to 0.2,
                )
                bandwidth = Bandwidths.DATACENTER
                validators = 100
                peers = 60

                allMessageSubnets(DcSlotMessageType.BLOB_COLUMN)
                allMessageSubnets(DcSlotMessageType.FFG_ATTESTATION)
            }
            .addGroup(count = 90) {
                // business
                name = "home"
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 10
                peers = 30

                // Model the current validator custody requirement: eight of 128 DA columns per node.
                randomMessageSubnets(DcSlotMessageType.BLOB_COLUMN, count = 8)
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = 1)
            }
            .build()

        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 1)
        Assertions.assertThat(graph.subnetDeficiencies()).isEmpty()

        val gossipParams = GossipParams.builder()
            .disableGossip()
            .build()

        val runConfig = DcRunConfig(
            slotCount = 1,
            settle = 30.seconds,
            messages = listOf(
                DcSlotMessageConfig(
                    type = DcSlotMessageType.BLOCK,
                    sizeBytes = 8 * 1024,
                    publishOffset = 0.seconds,
                    publisherGroups = setOf("validator-pools"),
                    publisherSelection = DcPublisherSelection.VALIDATOR_WEIGHTED,
                    topics = DcSlotMessageTopics.Global
                ),
                DcSlotMessageConfig(
                    type = DcSlotMessageType.PAYLOAD_CHUNK,
                    sizeBytes = 128 * 1024 / 64,
                    publishOffset = 1.seconds,
                    publisherGroups = setOf("validator-pools"),
                    publisherSelection = DcPublisherSelection.VALIDATOR_WEIGHTED,
                    messagesPerSlot = 64,
                    topics = DcSlotMessageTopics.Subnets(64)
                ),
                DcSlotMessageConfig(
                    type = DcSlotMessageType.BLOB_COLUMN,
                    // Scenario assumption: one 8 KiB sidecar per DA column.
                    sizeBytes = 4 * 1024,
                    publishOffset = 1.seconds,
                    publisherGroups = setOf("validator-pools"),
                    publisherSelection = DcPublisherSelection.VALIDATOR_WEIGHTED,
                    messagesPerSlot = 128,
                    topics = DcSlotMessageTopics.Subnets(128)
                )
            ) + (0 until 12)
                .map { waveIdx ->
                    DcSlotMessageConfig(
                        type = DcSlotMessageType.FFG_ATTESTATION,
                        sizeBytes = 240,
                        publishOffset = waveIdx.seconds,
                        publisherSelection = DcPublisherSelection.ALL_VALIDATORS,
                        topics = DcSlotMessageTopics.Subnets(64)
                    )
                },
            gossipParams = gossipParams,
            randomSeed = 1
        )

        val report = DcScenario.run(
            network = network,
            graph = graph,
            config = runConfig
        )
        println(report)
    }

    @Test
    fun `large network`() {
        val validatorCount = System.getProperty("dc.validators")?.toInt() ?: 1_000_000
        val slotDuration = 12.seconds
        val ffgSlots = 4
        val ffgWavesPerSlot = 12
        val ffgSubnetsTotal = 64
        val ffgResidentialSubnets = 1
        val blockSize = 8 * 1024
        val blockPayloadSize = 1024 * 1024
        val numberOfBlobs = 21
        val blobsSize = 128 * 1024 * 2 * numberOfBlobs
        val blobColumnSize = blobsSize / 128
        // Models aggregation: N times fewer attestations, each N times larger, so the bytes
        // published per wave are unchanged and only the message count drops. 1 = no aggregation.
        val ffgCompression = System.getProperty("dc.ffgCompression")?.toInt() ?: 1
        // Extra slots exist to be thrown away: by default every slot but the last is warm-up, so the
        // headline figures describe a network that has already carried a slot's worth of traffic.
        val slotCount = System.getProperty("dc.slots")?.toInt() ?: 1
        val warmupSlots = System.getProperty("dc.warmupSlots")?.toInt() ?: (slotCount - 1)
        // Both off by default, matching how this scenario was last run by hand. Switching them
        // from the command line is what lets the four combinations be compared without edits
        // between runs -- the charts only line up if nothing else moved.
        val gossipEnabled = System.getProperty("dc.gossip")?.toBoolean() ?: false
        val ffgEnabled = System.getProperty("dc.ffg")?.toBoolean() ?: false

        val network = DcNetworkBuilder
            .world(
                subnetCounts = mapOf(
                    DcSlotMessageType.PAYLOAD_CHUNK to 64,
                    DcSlotMessageType.BLOB_COLUMN to 128,
                    DcSlotMessageType.FFG_ATTESTATION to ffgSubnetsTotal
                )
            )
            .defaults {
                // Spread payload chunks across their own 64 meshes.
                allMessageSubnets(DcSlotMessageType.PAYLOAD_CHUNK)
            }
            .addGroup(count = 200) {
                name = "validator-pools"
                regionWeights = mapOf(
                    ContinentRegion.EUROPE to 0.4,
                    ContinentRegion.US_EAST to 0.4,
                    ContinentRegion.US_WEST to 0.2,
                )
                bandwidth = Bandwidths.FAST_DATACENTER
                validators = validatorCount / 1000
                peers = 200

                allMessageSubnets(DcSlotMessageType.BLOB_COLUMN)
                allMessageSubnets(DcSlotMessageType.FFG_ATTESTATION)
            }
            .addGroup(count = 800) {
                // business
                name = "home"
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                validators = validatorCount / 1000
                peers = 100

                // Model the current validator custody requirement: eight of 128 DA columns per node.
                randomMessageSubnets(DcSlotMessageType.BLOB_COLUMN, count = 8)
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = ffgResidentialSubnets)
            }
            .build()

        val ffgAttestationsPerWave =
            network.validatorCount / ffgSlots / ffgWavesPerSlot / ffgCompression

        val graph = network
            .peerGraph(
                minPeersPerSubnet = 8,
            )
        Assertions.assertThat(graph.subnetDeficiencies()).isEmpty()

        val gossipParams = GossipParams.builder()
            .also { if (!gossipEnabled) it.disableGossip() }
            .build()

        val runConfig = DcRunConfig(
            slotCount = slotCount,
            warmupSlots = warmupSlots,
            messages = listOf<DcSlotMessageConfig>(
                DcSlotMessageConfig(
                    type = DcSlotMessageType.BLOCK,
                    sizeBytes = blockSize,
                    publishOffset = 0.seconds,
                    publisherGroups = setOf("validator-pools"),
                    publisherSelection = DcPublisherSelection.VALIDATOR_WEIGHTED,
                    topics = DcSlotMessageTopics.Global
                ),
                DcSlotMessageConfig(
                    type = DcSlotMessageType.PAYLOAD_CHUNK,
                    sizeBytes = blockPayloadSize / 64,
                    publishOffset = 1.seconds,
                    publisherGroups = setOf("validator-pools"),
                    publisherSelection = DcPublisherSelection.VALIDATOR_WEIGHTED,
                    messagesPerSlot = 64,
                    topics = DcSlotMessageTopics.Subnets(64)
                ),
                DcSlotMessageConfig(
                    type = DcSlotMessageType.BLOB_COLUMN,
                    // Scenario assumption: one 8 KiB sidecar per DA column.
                    sizeBytes = blobColumnSize,
                    publishOffset = 1.seconds,
                    publisherGroups = setOf("validator-pools"),
                    publisherSelection = DcPublisherSelection.VALIDATOR_WEIGHTED,
                    messagesPerSlot = 128,
                    topics = DcSlotMessageTopics.Subnets(128)
                )
            ) + if (!ffgEnabled) {
                emptyList()
            } else {
                (0 until ffgWavesPerSlot)
                    .map { waveIdx ->
                        DcSlotMessageConfig(
                            type = DcSlotMessageType.FFG_ATTESTATION,
                            sizeBytes = 240 * ffgCompression,
                            publishOffset = (slotDuration / ffgWavesPerSlot) * waveIdx,
                            publisherSelection = DcPublisherSelection.RANDOM_VALIDATORS,
                            messagesPerSlot = ffgAttestationsPerWave,
                            topics = DcSlotMessageTopics.Subnets(64)
                        )
                    }
            },
            gossipParams = gossipParams,
        )

        val report = DcScenario.run(
            network = network,
            graph = graph,
            config = runConfig
        )
        println(report)
    }

    @Test
    fun `attestation 1000 residential`() {
        val network = DcNetworkBuilder
            .world(
                randomSeed = 1,
                subnetCount = 64
            )
            .defaults {
                allMessageSubnets(DcSlotMessageType.PAYLOAD_CHUNK, of = 64)
                allMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, of = 64)
                // Model the current validator custody requirement: eight of 128 DA columns per node.
                messageSubnetsByIndex(DcSlotMessageType.BLOB_COLUMN) { index ->
                    (0 until 8).mapTo(mutableSetOf()) { offset ->
                        (index * 8 + offset) % 128
                    }
                }
            }
            .addGroup(count = 6) {
                // validator pools
                name = "validator-pools"
                regionWeights = mapOf(
                    ContinentRegion.EUROPE to 0.4,
                    ContinentRegion.US_EAST to 0.4,
                    ContinentRegion.US_WEST to 0.2,
                )
                bandwidth = Bandwidths.DATACENTER
                validators = 10000
                peers = 200
                allMessageSubnets(DcSlotMessageType.FFG_ATTESTATION)
            }
            .addGroup(count = 200) {
                // business
                name = "business"
                regionWeights = mapOf(
                    ContinentRegion.EUROPE to 0.4,
                    ContinentRegion.US_EAST to 0.4,
                    ContinentRegion.US_WEST to 0.2,
                )
                bandwidth = Bandwidths.DATACENTER
                validators = 200
                peers = 100
                allMessageSubnets(DcSlotMessageType.FFG_ATTESTATION)
            }
//            .addGroup(count = 300) {
//                // home stakers
//                spreadOverRegions()
//                bandwidth = Bandwidths.RESIDENTIAL
//                validators = 10
//                peers = 40
//                randomSubnets(10)
//            }
//            .addGroup(count = 700) {
//                spreadOverRegions()
//                bandwidth = Bandwidths.RESIDENTIAL
//                validators = 0
//                peers = 20
//                randomSubnets(2)
//            }
            .build()

        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 1)
        Assertions.assertThat(graph.subnetDeficiencies()).isEmpty()

        val gossipParams = GossipParams.builder()
            // Mesh-only: disables the lazy IHAVE/IWANT gossip mechanism, leaving plain mesh push
            // (GRAFT/PRUNE) as the only way messages travel. gossipSize = 0 means no message ids are
            // exposed for lazy gossip, so IHAVE (and therefore IWANT) never fire.
            .DLazy(0)
            .gossipFactor(0.0)
            .gossipSize(0)
            .build()

        val attestationConfig = DcRunConfig(
            slotCount = 1,
            // ~223MB reaches each node, and a 50 Mbit/s residential link carries 6.25MB/s, so the
            // slot needs ~36s of link time alone. The default 12s settle would cut off around two
            // thirds of it and report the shortfall as undelivered; 150s leaves room for the
            // transfer plus queueing so the latency figures mean something.
            settle = 150.seconds,
            messages = listOf(
                DcSlotMessageConfig(
                    type = DcSlotMessageType.BLOCK,
                    sizeBytes = 8 * 1024,
                    publishOffset = 0.seconds,
                    publisherGroups = setOf("validator-pools"),
                    publisherSelection = DcPublisherSelection.VALIDATOR_WEIGHTED,
                    topics = DcSlotMessageTopics.Global
                ),
                DcSlotMessageConfig(
                    type = DcSlotMessageType.PAYLOAD_CHUNK,
                    sizeBytes = 512 * 1024 / 64,
                    publishOffset = 1.seconds,
                    publisherGroups = setOf("validator-pools"),
                    publisherSelection = DcPublisherSelection.VALIDATOR_WEIGHTED,
                    messagesPerSlot = 64,
                    topics = DcSlotMessageTopics.Subnets(64)
                ),
                DcSlotMessageConfig(
                    type = DcSlotMessageType.BLOB_COLUMN,
                    // Scenario assumption: one 8 KiB sidecar per DA column.
                    sizeBytes = 8 * 1024,
                    publishOffset = 1.seconds,
                    publisherGroups = setOf("validator-pools"),
                    publisherSelection = DcPublisherSelection.VALIDATOR_WEIGHTED,
                    messagesPerSlot = 128,
                    topics = DcSlotMessageTopics.Subnets(128)
                ),
                DcSlotMessageConfig(
                    type = DcSlotMessageType.FFG_ATTESTATION,
                    sizeBytes = 240,
                    publishOffset = 0.seconds,
                    publisherSelection = DcPublisherSelection.ALL_VALIDATORS,
                    topics = DcSlotMessageTopics.Subnets(64)
                )
            ),
            gossipParams = gossipParams,
            randomSeed = 1
        )
        val report = DcScenario.run(
            network = network,
            graph = graph,
            config = attestationConfig
        )
        println(report)
    }

    /**
     * One rolling-attestation run: 1024 residential nodes, 1024 validators each, one subnet apiece.
     *
     * [subnetCount] changes how the same total number of attestations is spread: with 1024 nodes,
     * a subnet holds 1024/[subnetCount] nodes and carries 1/[subnetCount] of the traffic, so
     * *fewer* subnets means bigger committees and more bytes per node.
     */
    private fun runRolling(d: Int, subnetCount: Int, lazyGossip: Boolean = false): DcRunReport {
        val network = DcNetworkBuilder
            .world(
                randomSeed = 1,
                subnetCount = subnetCount
            )
            .addGroup(count = 1024) {
                // validator pools
                regionWeights = mapOf(
                    ContinentRegion.EUROPE to 0.4,
                    ContinentRegion.US_EAST to 0.4,
                    ContinentRegion.US_WEST to 0.2,
                )
                // RESIDENTIAL is 50 Mbit/s down, 25 up. Upload is the direction that matters for
                // gossip, since a node forwards each message to every mesh peer but only receives
                // it once per peer that already has it.
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 1024
                peers = 30
                // Round-robin rather than randomSubnets(1), which assigns independently and so
                // gives multinomial subnet sizes: at 64 subnets the smallest drew 7 nodes, whose
                // members cannot reach minPeersPerSubnet = 8, and the graph check failed. Even
                // assignment also keeps committee size identical at every sweep point, so a node's
                // load does not depend on which subnet it happened to land in.
                messageSubnetsByIndex(DcSlotMessageType.FFG_ATTESTATION) { index -> setOf(index % subnetCount) }
            }
            .build()

        val graph = network.peerGraph(minPeersPerSubnet = 8, randomSeed = 1)
        Assertions.assertThat(graph.subnetDeficiencies()).isEmpty()

        val gossipParams = GossipParams.builder()
            .D(d)
            // Pin the mesh to D +- 1 instead of the derived defaults (DLow = D*2/3, DHigh = D*2).
            // The heartbeat only grafts below DLow and prunes above DHigh, so the default band
            // leaves the mesh free to drift up to 2*D on inbound GRAFTs — measured at 8.25 for
            // D = 6, which is where the duplication above D came from. A +-1 band keeps mesh size,
            // and therefore duplication, close to D itself.
            .DLow(d - 1)
            .DHigh(d + 1)
            .also { builder ->
                if (!lazyGossip) {
                    // Mesh-only: disables the lazy IHAVE/IWANT gossip mechanism, leaving plain mesh
                    // push (GRAFT/PRUNE) as the only way messages travel. gossipSize = 0 means no
                    // message ids are exposed for lazy gossip, so IHAVE (and therefore IWANT)
                    // never fire.
                    builder.DLazy(0).gossipFactor(0.0).gossipSize(0)
                }
                // Otherwise leave DLazy/gossipFactor/gossipSize unset so the defaults apply:
                // DLazy = D, gossipFactor = 0.25, gossipSize = 3.
            }
            .build()

        // A 32nd of the validator set per slot: one slot's worth, from 32 slots per epoch.
        // Deliberately independent of subnetCount — the 32 here is slots, not subnets — so the
        // sweep publishes the same 262144 attestations at every point and only their spread over
        // subnets changes.
        val attestersPerSlot = 1024 * 1024 / 32
        val attestationConfig = DcRunConfig(
            slotCount = 8,
            // RANDOM_VALIDATORS, not ALL_VALIDATORS: the latter ignores attestersPerSlot and has
            // all 1,048,576 validators attest in every slot, 32x a slot's worth.
            messages = listOf(
                DcSlotMessageConfig(
                    type = DcSlotMessageType.FFG_ATTESTATION,
                    sizeBytes = 240,
                    publisherSelection = DcPublisherSelection.RANDOM_VALIDATORS,
                    messagesPerSlot = attestersPerSlot,
                    topics = DcSlotMessageTopics.Subnets(subnetCount)
                )
            ),
            slotInterval = 1.seconds,
            settle = 30.seconds,
            // Slots 0-3 are the transport ramping up, not the protocol: QUIC congestion windows
            // start small and the meshes are still settling, which showed up as a p99 two to three
            // times the steady-state value and a max up to four times it. They stay in the per-slot
            // breakdown, they just do not skew the headline numbers.
            warmupSlots = 4,
            gossipParams = gossipParams,
            randomSeed = 1
        )

        return DcScenario.run(
            network = network,
            graph = graph,
            config = attestationConfig
        )
    }

    @Test
    fun `rolling attestation`() {
        println(runRolling(d = 6, subnetCount = 32))
    }

    /**
     * D x subnetCount sweep over the rolling scenario. D drives mesh degree and therefore
     * duplication; subnetCount drives how concentrated the traffic is. Both move bytes per node, so
     * the interesting question is where latency starts to suffer as either is reduced.
     */
    @Test
    fun `rolling attestation D x subnet sweep`() {
        runSweep(lazyGossip = false)
    }

    /**
     * The same sweep with gossipsub's lazy IHAVE/IWANT mechanism left at its defaults
     * (DLazy = D, gossipFactor = 0.25, gossipSize = 3) rather than switched off.
     *
     * IHAVE announces the ids of everything seen over the last `gossipSize` heartbeats, so its cost
     * scales with message rate rather than with mesh size: at one slot per second and 20-byte ids,
     * a single IHAVE can carry thousands of ids. The control column is what to watch.
     */
    @Test
    fun `rolling attestation D x subnet sweep with default IHAVE`() {
        runSweep(lazyGossip = true)
    }

    private fun runSweep(lazyGossip: Boolean) {
        val summary = mutableListOf<String>()
        listOf(6, 5, 4, 3).forEach { d ->
            listOf(16, 32, 64).forEach { subnets ->
                println("======== D=$d subnets=$subnets lazyGossip=$lazyGossip ========")
                // A point that cannot even build a valid graph should not discard the other
                // eleven results, but it must still be visible in the summary rather than
                // silently missing.
                summary += try {
                    val report = runRolling(d = d, subnetCount = subnets, lazyGossip = lazyGossip)
                    println(report)
                    val nodes = report.traffic.overall.nodeCount
                    SWEEP_ROW.format(
                        d,
                        subnets,
                        report.mesh?.meanSize ?: 0.0,
                        report.duplicationFactor,
                        report.overall.deliveryRatio * 100,
                        report.overall.p50?.inWholeMilliseconds ?: -1,
                        report.overall.p95?.inWholeMilliseconds ?: -1,
                        report.overall.p99?.inWholeMilliseconds ?: -1,
                        report.overall.max?.inWholeMilliseconds ?: -1,
                        // Per node per slot, so the figure does not move with slotCount or
                        // warmupSlots and stays comparable across runs.
                        report.publishBytesReceivedPerNodePerSlot / 1e6,
                        // Control is not slot-attributed (IHAVE/IWANT carry no slot index), so this
                        // is the whole-run total per node, warm-up included.
                        report.gossipControlBytesReceived.toDouble() / nodes / 1e6
                    )
                } catch (e: Throwable) {
                    println("FAILED D=$d subnets=$subnets: $e")
                    e.printStackTrace()
                    "%2d  %7d  FAILED: %s".format(d, subnets, e.toString().take(120))
                }
                // Each point holds millions of deliveries while running; drop them before the next.
                System.gc()
            }
        }
        println("\n======== SWEEP SUMMARY (lazyGossip=$lazyGossip) ========")
        println(SWEEP_HEADER)
        summary.forEach(::println)
    }

    companion object {
        private const val SWEEP_HEADER =
            " D  subnets  meshMean  dup    deliv%   p50    p95    p99    max   MB/node/slot  ctrlMB/node"
        private const val SWEEP_ROW =
            "%2d  %7d  %8.2f  %5.2fx %6.2f  %5d  %5d  %5d  %6d  %11.2f  %10.2f"

        fun GossipParamsBuilder.disableGossip() = also {
            this
                .DLazy(0)
                .gossipFactor(0.0)
                .gossipSize(0)
        }
    }
}
