package playground

import io.libp2p.etc.types.millis
import io.libp2p.example.dc.Bandwidths
import io.libp2p.example.dc.Bandwidths.mbitPerSecond
import io.libp2p.example.dc.DcLink
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
import kotlin.collections.flatten
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
        // Both off by default, matching how this scenario was last run by hand. Switching them
        // from the command line is what lets the four combinations be compared without edits
        // between runs -- the charts only line up if nothing else moved.
        val gossipEnabled = System.getProperty("dc.gossip")?.toBoolean() ?: false
        val ffgEnabled = System.getProperty("dc.ffg")?.toBoolean() ?: false
        // Mesh and heartbeat values the Ethereum consensus spec sets for mainnet, where libp2p's
        // own defaults are D=6/DLow=4/DHigh=12/DLazy=6 and a 1s heartbeat. Both differences add
        // load: a third more mesh degree means a third more duplicates, and a 700ms heartbeat
        // emits gossip 43% more often. Off by default so earlier runs stay comparable.
        val ethGossipParams = System.getProperty("dc.ethGossip")?.toBoolean() ?: false
        // Residential downlink in Mbit/s, upload at half it, keeping the 2:1 ratio the Ethereum
        // spec uses for an attesting validator. 50 is Bandwidths.RESIDENTIAL, the default here.
        val homeDownMbps = System.getProperty("dc.homeDownMbps")?.toLong() ?: 50L
        // Upload defaults to half the downlink, but is separately settable because gossipsub
        // forwarding is byte-symmetric -- a node forwards unique x (mesh-1) and receives
        // unique x duplication, and those are the same quantity from opposite ends -- so a 2:1
        // link saturates its uplink at half the byte rate the downlink could absorb.
        val homeUpMbps = System.getProperty("dc.homeUpMbps")?.toLong() ?: (homeDownMbps / 2)
        val homeLink = DcLink(mbitPerSecond(homeDownMbps), mbitPerSecond(homeUpMbps))

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
        val ffgCompression = System.getProperty("dc.ffgCompression")?.toInt() ?: 1
        // Extra slots exist to be thrown away: by default every slot but the last is warm-up, so the
        // headline figures describe a network that has already carried a slot's worth of traffic.
        val slotCount = System.getProperty("dc.slots")?.toInt() ?: 1
        val warmupSlots = System.getProperty("dc.warmupSlots")?.toInt() ?: (slotCount - 1)

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
                bandwidth = homeLink
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
            .also {
                if (ethGossipParams) {
                    it.D(8).DLow(6).DHigh(12).DLazy(6).heartbeatInterval(700.millis)
                }
            }
            // Applied last: with gossip off, DLazy must stay 0 whatever the mesh params say.
            .also { if (!gossipEnabled) it.disableGossip() }
            .build()

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
    fun `median network`() {
        val gossipEnabled = true
        val ffgEnabled = false
        val ethGossipParams = true
        // 40/20 Mbps to compensate smaller number of IHAVEs
//        val homeLink = DcLink(mbitPerSecond(40), mbitPerSecond(20))
        val homeLink = DcLink(mbitPerSecond(50), mbitPerSecond(25))

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
            }
            .addGroup(count = 10) {
                name = "validator-pools"
                regionWeights = mapOf(
                    ContinentRegion.EUROPE to 0.4,
                    ContinentRegion.US_EAST to 0.4,
                    ContinentRegion.US_WEST to 0.2,
                )
                bandwidth = Bandwidths.FAST_DATACENTER
                validators = validatorCount / 100
                peers = 80

                allMessageSubnets(DcSlotMessageType.BLOB_COLUMN)
                allMessageSubnets(DcSlotMessageType.FFG_ATTESTATION)
//                randomMessageSubnets(DcSlotMessageType.BLOB_COLUMN, 32)
//                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, 4)
            }
            .addGroup(count = 90) {
                // business
                name = "home"
                spreadOverRegions()
                bandwidth = homeLink
                validators = validatorCount / 100
                peers = 30

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
            .also {
                if (ethGossipParams) {
                    it.D(8).DLow(6).DHigh(12).DLazy(6).heartbeatInterval(700.millis)
                }
            }
            // Applied last: with gossip off, DLazy must stay 0 whatever the mesh params say.
            .also { if (!gossipEnabled) it.disableGossip() }
            .build()

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
            gossipParams = gossipParams,
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
