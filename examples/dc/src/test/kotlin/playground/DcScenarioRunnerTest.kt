package playground

import io.libp2p.etc.types.millis
import io.libp2p.example.dc.Bandwidths
import io.libp2p.example.dc.Bandwidths.mbitPerSecond
import io.libp2p.example.dc.DcLink
import io.libp2p.example.dc.DcNetworkBuilder
import io.libp2p.example.dc.DcPublisherSelection
import io.libp2p.example.dc.DcRunConfig
import io.libp2p.example.dc.DcScenario
import io.libp2p.example.dc.DcSlotMessageConfig
import io.libp2p.example.dc.DcSlotMessageTopics
import io.libp2p.example.dc.DcSlotMessageType
import io.libp2p.example.dc.peerGraph
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.IWantPolicy
import io.libp2p.pubsub.gossip.builders.GossipParamsBuilder
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.ContinentRegion
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.collections.flatten
import kotlin.math.roundToInt
import kotlin.time.Duration
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
                gossipParams = GossipParams.builder().disableGossip().build()
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
        val gossipEnabled = true
        val ffgEnabled = true
        val ethGossipParams = true
        // Inert unless set: the 50/25 attester floor. Raise both to erase the constrained tier
        // and measure what it costs every other tier.
        val homeLink = DcLink(
            mbitPerSecond(System.getProperty("dc.homeDownMbps")?.toLong() ?: 50),
            mbitPerSecond(System.getProperty("dc.homeUpMbps")?.toLong() ?: 25)
        )
        // Residential group sizes. Moving nodes between the three tiers keeps the network at 1000
        // nodes and every node's validators and subscriptions unchanged -- only the share of the
        // population sitting at the attester floor moves.
        val upperHomeCount = System.getProperty("dc.upperHomeCount")?.toInt() ?: 100
        val midHomeCount = System.getProperty("dc.midHomeCount")?.toInt() ?: 100
        val homeCount = System.getProperty("dc.homeCount")?.toInt() ?: 100
        // -Ddc.homeValidators=false: the attester-floor tier runs no validators, so it publishes no
        // votes or aggregates and only relays. Its share is spread over everyone else, so the
        // network keeps 1M validators and every wave keeps its size.
        val homeValidators = System.getProperty("dc.homeValidators") != "false"

        // Mesh degree is a local choice each node makes, so groups may run different ones and still
        // interoperate. Built from one factory so a group's params differ from the run's default in
        // the degrees alone, and both follow the gossip switches above.
        fun meshParams(d: Int, dLow: Int, dHigh: Int, dLazy: Int) = GossipParams.builder()
            .also {
                if (ethGossipParams) {
                    it.D(d).DLow(dLow).DHigh(dHigh).DLazy(dLazy).heartbeatInterval(700.millis)
                }
            }
            // Applied last: with gossip off, DLazy must stay 0 whatever the mesh params say.
            .also { if (!gossipEnabled) it.disableGossip() }
            .build()

        // Every group runs the same eth ladder. What varies is how an IHAVE for an unknown message is
        // followed up -- see IWantPolicy -- because the original "ask every announcer" is what fills a
        // slow link: at D=4/DHigh=4 the 50/25 tier saw 18 copies of each payload chunk against a mesh
        // of 4, and never asking took that to 3.7 and its inbound traffic from 54.6 to 29.0 MB.
        //
        //   -Ddc.iwant=all_announcers|disciplined|none   policy under test (default disciplined)
        //   -Ddc.iwantScope=home|all                     who runs it: the 50/25 tier, or everyone
        //
        // With scope=home the other groups keep asking every announcer, so they are the control.
        val iWantPolicy = IWantPolicy.valueOf((System.getProperty("dc.iwant") ?: "disciplined").uppercase())
        val iWantEverywhere = System.getProperty("dc.iwantScope") == "all"
        val ethLadder = meshParams(d = 8, dLow = 6, dHigh = 12, dLazy = 6)
        val defaultGossipParams = if (iWantEverywhere) ethLadder.copy(iWantPolicy = iWantPolicy) else ethLadder
        val homeGossipParams = ethLadder.copy(iWantPolicy = iWantPolicy)

        val validatorCount = 1_000_000
        val nodeCount = 100 + 600 + upperHomeCount + midHomeCount + homeCount
        val validatorsPerNode = validatorCount / (if (homeValidators) nodeCount else nodeCount - homeCount)
        val slotDuration = 12.seconds
        val ffgAttestationSize = 240
        // Slots an epoch spreads its attestations over: each validator attests once per epoch, so
        // raising this divides both the votes published per wave and the aggregate's bitfield --
        // aggregateSlotsPerEpoch follows it, and the bitfield covers one slot's share of a subnet.
        val ffgSlots = System.getProperty("dc.ffgSlots")?.toInt() ?: 4
        val ffgWavesPerSlot = 12
        val ffgSubnetsCount = 64
        // Attestation subnets each residential node (upper-home, mid-home, home) subscribes to.
        // Doubling it doubles the vote traffic those tiers carry without touching anything else.
        val ffgResidentialSubnets = System.getProperty("dc.homeFfgSubnets")?.toInt() ?: 1
        val blockSize = 8 * 1024
        val blockPayloadSize = 1024 * 1024
        val numberOfBlobs = 21
        val blobsSize = 128 * 1024 * 2 * numberOfBlobs
        val blobColumnSize = blobsSize / 128
        val aggregateSlotsPerEpoch = ffgSlots // can be e.g. 1 if we want to aggregate during the whole epoch
        val aggregateBitvectorSize = validatorCount / ffgSubnetsCount / aggregateSlotsPerEpoch / 8
        val aggregateSize = aggregateBitvectorSize + ffgAttestationSize
        val aggregateWaves = listOf(
            List(2) { 6.seconds },
            List(2) { 8.seconds },
            List(4) { 9.seconds },
            List(4) { 10.seconds },
            List(4) { 11.seconds },
        ).flatten()

        // Models aggregation: N times fewer attestations, each N times larger, so the bytes
        // published per wave are unchanged and only the message count drops. 1 = no aggregation.
        val ffgCompression = System.getProperty("dc.ffgCompression")?.toInt() ?: 1
        // -Ddc.snappyStream=true: Snappy over the whole gossip stream (64 KiB per-stream history)
        // instead of per message, applied virtually by shrinking what votes and aggregates put on
        // the wire. Ratios are the mainnet-recording figures from ethresearch-snappy-stream.md:
        // attestations 42%, aggregates 63% of today's bytes -- the repeated AttestationData turns
        // into a back-reference. Everything else is left at its size: blocks and columns barely
        // compress (93% / 96%). Topic strings, which the stream would also shrink, stay full size.
        val snappyStream = System.getProperty("dc.snappyStream") == "true"
        val ffgWireRatio = if (snappyStream) System.getProperty("dc.ffgSnappyRatio")?.toDouble() ?: 0.42 else 1.0
        val aggregateWireRatio = if (snappyStream) System.getProperty("dc.aggSnappyRatio")?.toDouble() ?: 0.63 else 1.0
        val ffgWireSize = (ffgAttestationSize * ffgCompression * ffgWireRatio).roundToInt()
        val aggregateWireSize = (aggregateSize * aggregateWireRatio).roundToInt()
        println("FFG vote wire size: $ffgWireSize B, aggregate wire size: $aggregateWireSize B (snappyStream=$snappyStream)")
        // Extra slots exist to be thrown away: by default every slot but the last is warm-up, so the
        // headline figures describe a network that has already carried a slot's worth of traffic.
        // Two slots by default, because a measured slot with a predecessor is a different network
        // from a cold one: the same run measured ffg p99 at 2.12s behind a warm-up slot and 1.61s
        // without one, and blob p50 at 492ms against 787ms.
        val slotCount = System.getProperty("dc.slots")?.toInt() ?: 2
        val warmupSlots = System.getProperty("dc.warmupSlots")?.toInt() ?: (slotCount - 1)
        // The warm-up slot publishes only its last four seconds. A full one costs as much wall clock
        // as the measured slot -- 37m39s against 24m31s for this run -- and most of that cost buys
        // nothing: measured attestations, aggregates, duplicate share and link utilisation all came
        // back within a few percent of the full-warm-up figures. What it does cost is the bulk-data
        // tail the measured slot inherits, so payload-chunk p50 reads ~25% high and copies per chunk
        // ~30% high. Use -Ddc.warmupFrom=0 when those are the answer, or 6 for a middle ground
        // (2 minutes dearer, and closer on blob p99 and the first aggregate wave).
        val warmupSlotStart = if (warmupSlots == 0) {
            Duration.ZERO
        } else {
            (System.getProperty("dc.warmupFrom")?.toInt() ?: 8).seconds
        }

        val network = DcNetworkBuilder
            .world(
                subnetCounts = mapOf(
                    DcSlotMessageType.PAYLOAD_CHUNK to 64,
                    DcSlotMessageType.BLOB_COLUMN to 128,
                    DcSlotMessageType.FFG_ATTESTATION to ffgSubnetsCount
                )
            )
            .defaults {
                // Spread payload chunks across their own 64 meshes.
                allMessageSubnets(DcSlotMessageType.PAYLOAD_CHUNK)
                gossipParams = defaultGossipParams
            }
            .addGroup(count = 100) {
                name = "validator-pools"
                regionWeights = mapOf(
                    ContinentRegion.EUROPE to 0.4,
                    ContinentRegion.US_EAST to 0.4,
                    ContinentRegion.US_WEST to 0.2,
                )
                bandwidth = Bandwidths.FAST_DATACENTER
                validators = validatorsPerNode
                peers = 200

                allMessageSubnets(DcSlotMessageType.BLOB_COLUMN)
                allMessageSubnets(DcSlotMessageType.FFG_ATTESTATION)
            }
            .addGroup(count = 600) {
                // business
                name = "vps"
                spreadOverRegions()
                bandwidth = Bandwidths.VPS
                validators = validatorsPerNode
                peers = 100

                // Model the current validator custody requirement: eight of 128 DA columns per node.
                randomMessageSubnets(DcSlotMessageType.BLOB_COLUMN, count = 16)
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = 4)
            }
            .addGroup(count = upperHomeCount) {
                // business
                name = "upper-home"
                spreadOverRegions()
                bandwidth = DcLink(mbitPerSecond(200), mbitPerSecond(100))
                validators = validatorsPerNode
                peers = 100

                // Model the current validator custody requirement: eight of 128 DA columns per node.
                randomMessageSubnets(DcSlotMessageType.BLOB_COLUMN, count = 8)
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = ffgResidentialSubnets)
            }
            .addGroup(count = midHomeCount) {
                // business
                name = "mid-home"
                spreadOverRegions()
                bandwidth = DcLink(mbitPerSecond(100), mbitPerSecond(50))
                validators = validatorsPerNode
                peers = 100

                // Model the current validator custody requirement: eight of 128 DA columns per node.
                randomMessageSubnets(DcSlotMessageType.BLOB_COLUMN, count = 8)
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = ffgResidentialSubnets)
            }
            .addGroup(count = homeCount) {
                // business
                name = "home"
                spreadOverRegions()
                bandwidth = homeLink
                validators = if (homeValidators) validatorsPerNode else 0
                peers = 100
                gossipParams = homeGossipParams

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

        val ffgWavesPerSlotIfEnabled = if (ffgEnabled) ffgWavesPerSlot else 0
        val aggregateWavesIfEnabled = if (ffgEnabled) aggregateWaves else emptyList()
        val runConfig = DcRunConfig(
            slotCount = slotCount,
            warmupSlots = warmupSlots,
            messages =
            listOf(
                listOf(
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
                ),
                (0 until ffgWavesPerSlotIfEnabled)
                    .map { waveIdx ->
                        DcSlotMessageConfig(
                            type = DcSlotMessageType.FFG_ATTESTATION,
                            sizeBytes = ffgWireSize,
                            publishOffset = (slotDuration / ffgWavesPerSlot) * waveIdx,
                            publisherSelection = DcPublisherSelection.RANDOM_VALIDATORS,
                            messagesPerSlot = ffgAttestationsPerWave,
                            topics = DcSlotMessageTopics.Subnets(64)
                        )
                    },
                // Several entries of aggregateWaves share an offset, but a run may hold only
                // one config per (type, offset) -- two publications at the same instant are
                // indistinguishable anyway. Fold them into one config carrying their combined
                // message count, so the totals are what the list asks for.
                aggregateWavesIfEnabled
                    .groupingBy { it }
                    .eachCount()
                    .map { (waveOffset, wavesAtOffset) ->
                        DcSlotMessageConfig(
                            type = DcSlotMessageType.AGGREGATES,
                            sizeBytes = aggregateWireSize,
                            publishOffset = waveOffset,
                            publisherSelection = DcPublisherSelection.RANDOM_VALIDATORS,
                            messagesPerSlot = ffgSubnetsCount * wavesAtOffset,
                            topics = DcSlotMessageTopics.Global
                        )
                    }
            ).flatten(),
            warmupSlotStart = warmupSlotStart,
        )

        val report = DcScenario.run(
            network = network,
            graph = graph,
            config = runConfig
        )
        println(report)
    }

    @Test
    fun `median network`() {
        val gossipEnabled = true
        val ffgEnabled = true
        val ethGossipParams = true
        // 40/20 Mbps to compensate smaller number of IHAVEs
//        val homeLink = DcLink(mbitPerSecond(40), mbitPerSecond(20))
        val homeLink = DcLink(mbitPerSecond(50), mbitPerSecond(50))

        // Gossip params belong to the node, so they go on the groups below rather than on the run.
        val nodeGossipParams = GossipParams.builder()
            .also {
                if (ethGossipParams) {
                    it.D(8).DLow(6).DHigh(12).DLazy(6).heartbeatInterval(700.millis)
                }
            }
            // Applied last: with gossip off, DLazy must stay 0 whatever the mesh params say.
            .also { if (!gossipEnabled) it.disableGossip() }
            .build()

        val validatorCount = System.getProperty("dc.validators")?.toInt() ?: 1_000_000
        val slotDuration = 12.seconds
        val ffgAttestationSize = 240
        val ffgSlots = 4
        val ffgWavesPerSlot = 12
        val ffgSubnetsCount = 64
        val ffgResidentialSubnets = 1
        val blockSize = 8 * 1024
        val blockPayloadSize = 1024 * 1024
        val numberOfBlobs = 21
        val blobsSize = 128 * 1024 * 2 * numberOfBlobs
        val blobColumnSize = blobsSize / 128
        val aggregateSlotsPerEpoch = ffgSlots // can be e.g. 1 if we want to aggregate during the whole epoch
        val aggregateBitvectorSize = validatorCount / ffgSubnetsCount / aggregateSlotsPerEpoch / 8
        val aggregateSize = aggregateBitvectorSize + ffgAttestationSize
        val aggregateWaves = listOf(
            List(2) { 6.seconds },
            List(2) { 8.seconds },
            List(4) { 9.seconds },
            List(4) { 10.seconds },
            List(4) { 11.seconds },
        ).flatten()

        // Models aggregation: N times fewer attestations, each N times larger, so the bytes
        // published per wave are unchanged and only the message count drops. 1 = no aggregation.
        val ffgCompression = 10
        val slotCount = 1
        val warmupSlots = 0

        val network = DcNetworkBuilder
            .world(
                subnetCounts = mapOf(
                    DcSlotMessageType.PAYLOAD_CHUNK to 64,
                    DcSlotMessageType.BLOB_COLUMN to 128,
                    DcSlotMessageType.FFG_ATTESTATION to ffgSubnetsCount
                )
            )
            .defaults {
                // Spread payload chunks across their own 64 meshes.
                allMessageSubnets(DcSlotMessageType.PAYLOAD_CHUNK)
                gossipParams = nodeGossipParams
            }
            .addGroup(count = 100) {
                name = "validator-pools"
                regionWeights = mapOf(
                    ContinentRegion.EUROPE to 0.4,
                    ContinentRegion.US_EAST to 0.4,
                    ContinentRegion.US_WEST to 0.2,
                )
                bandwidth = Bandwidths.FAST_DATACENTER
                validators = validatorCount / 1000
                peers = 80

                allMessageSubnets(DcSlotMessageType.BLOB_COLUMN)
                allMessageSubnets(DcSlotMessageType.FFG_ATTESTATION)
            }
//            .addGroup(count = 85) {
//                name = "upper-home"
//                spreadOverRegions()
//                bandwidth = Bandwidths.VPS
//                validators = validatorCount / 100
//                peers = 80
//
//                randomMessageSubnets(DcSlotMessageType.BLOB_COLUMN, 16)
//                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, 4)
//            }
            .addGroup(count = 900) {
                // business
                name = "home"
                spreadOverRegions()
                bandwidth = homeLink
                // Per node, so the divisor tracks the group size: 1000 nodes keeps the network
                // total at validatorCount rather than 10x it.
                validators = validatorCount / 1000
                peers = 80

                // Model the current validator custody requirement: eight of 128 DA columns per node.
                randomMessageSubnets(DcSlotMessageType.BLOB_COLUMN, count = 8)
                // Round-robin rather than randomMessageSubnets(1): independent assignment gives
                // multinomial subnet sizes, and with no pools covering every subnet the smallest
                // ones fall below minPeersPerSubnet = 8. Even assignment keeps one subnet per node
                // while guaranteeing every subnet the same membership.
                messageSubnetsByIndex(DcSlotMessageType.FFG_ATTESTATION) { index ->
                    setOf(index % ffgSubnetsCount)
                }
            }
            .build()

        val ffgAttestationsPerWave =
            network.validatorCount / ffgSlots / ffgWavesPerSlot / ffgCompression

        val graph = network
            .peerGraph(
                minPeersPerSubnet = 8,
            )
        Assertions.assertThat(graph.subnetDeficiencies()).isEmpty()

        val ffgWavesPerSlotIfEnabled = if (ffgEnabled) ffgWavesPerSlot else 0
        val aggregateWavesIfEnabled = if (ffgEnabled) aggregateWaves else emptyList()
        val runConfig = DcRunConfig(
            slotCount = slotCount,
            warmupSlots = warmupSlots,
            messages =
            listOf(
                listOf(
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
                ),
                (0 until ffgWavesPerSlotIfEnabled)
                    .map { waveIdx ->
                        DcSlotMessageConfig(
                            type = DcSlotMessageType.FFG_ATTESTATION,
                            sizeBytes = ffgAttestationSize * ffgCompression,
                            publishOffset = (slotDuration / ffgWavesPerSlot) * waveIdx,
                            publisherSelection = DcPublisherSelection.RANDOM_VALIDATORS,
                            messagesPerSlot = ffgAttestationsPerWave,
                            topics = DcSlotMessageTopics.Subnets(64)
                        )
                    },
                // Several entries of aggregateWaves share an offset, but a run may hold only
                // one config per (type, offset) -- two publications at the same instant are
                // indistinguishable anyway. Fold them into one config carrying their combined
                // message count, so the totals are what the list asks for.
                aggregateWavesIfEnabled
                    .groupingBy { it }
                    .eachCount()
                    .map { (waveOffset, wavesAtOffset) ->
                        DcSlotMessageConfig(
                            type = DcSlotMessageType.AGGREGATES,
                            sizeBytes = aggregateSize,
                            publishOffset = waveOffset,
                            publisherSelection = DcPublisherSelection.RANDOM_VALIDATORS,
                            messagesPerSlot = ffgSubnetsCount * wavesAtOffset,
                            topics = DcSlotMessageTopics.Global
                        )
                    }
            ).flatten(),
        )

        val report = DcScenario.run(
            network = network,
            graph = graph,
            config = runConfig
        )
        println(report)
    }
    companion object {

        fun GossipParamsBuilder.disableGossip() = also {
            this
                .DLazy(0)
                .gossipFactor(0.0)
                .gossipSize(0)
        }
    }
}
