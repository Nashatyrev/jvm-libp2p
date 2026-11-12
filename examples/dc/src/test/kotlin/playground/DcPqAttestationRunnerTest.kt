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
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.ContinentRegion
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Post-quantum attestations on the `large network` population: every vote carries a leanXMSS
 * signature instead of a BLS one, and an aggregate is a leanVM proof instead of a bitfield.
 *
 * The proving itself is not simulated. Aggregators are assumed to stop collecting at a cutoff and
 * publish a fixed time later, so an aggregate is a message of the measured proof size issued at
 * `cutoff + measured proving time` -- both taken from the leanVM benchmarks. What the run then
 * answers is the network half: how much of a subnet's votes reach the pool nodes (the aggregators)
 * before the cutoff, and whether the aggregates reach everyone before the slot ends.
 *
 * ```
 * ./gradlew :examples:dc:simulation --tests "*DcPqAttestationRunnerTest*" -Ddc.pq.aggregateAtMs=6500
 * ```
 *
 * Switches (all `-Ddc.pq.*`):
 *  - `subnets` (16), `aggregatesPerSubnet` (4; 1 models an ideal first-seen rule that lets only one
 *    aggregate per subnet travel)
 *  - `sigBytes` (2404; 1204 = bottom layer only, the top layer cached per validator)
 *  - `attestAtMs` (3000), `aggregateAtMs` (6500), `aggregateKiB` (260)
 *  - `finalAtMs` (unset = the subnet aggregates are the end product; set = one merged proof of
 *    `finalKiB` (330) is published then by a pool node)
 *  - `background` (true: block, 1 MiB payload and 21 blobs as in `large network`)
 *  - `vpsSubnets` (2), `homeSubnets` (1), `weakHomeSubnets` (= homeSubnets; the 50/25 tier alone), `slotAttesters` (validators / 32)
 */
@Tag("simulation")
class DcPqAttestationRunnerTest {

    private fun prop(name: String): String? = System.getProperty("dc.pq.$name")

    @Test
    fun `pq attestations`() {
        val subnets = prop("subnets")?.toInt() ?: 16
        val aggregatesPerSubnet = prop("aggregatesPerSubnet")?.toInt() ?: 4
        val sigBytes = prop("sigBytes")?.toInt() ?: 2404
        val attestAt = (prop("attestAtMs")?.toLong() ?: 3000).milliseconds
        val aggregateAt = (prop("aggregateAtMs")?.toLong() ?: 6500).milliseconds
        val aggregateBytes = (prop("aggregateKiB")?.toInt() ?: 260) * 1024
        val finalAt = prop("finalAtMs")?.toLong()?.milliseconds
        val finalBytes = (prop("finalKiB")?.toInt() ?: 330) * 1024
        val background = prop("background") != "false"
        val vpsSubnets = prop("vpsSubnets")?.toInt() ?: 2
        val homeSubnets = prop("homeSubnets")?.toInt() ?: 1
        // The 50/25 tier alone; defaults to what the other two home tiers do.
        val weakHomeSubnets = prop("weakHomeSubnets")?.toInt() ?: homeSubnets

        val validatorCount = System.getProperty("dc.validators")?.toInt() ?: 1_000_000
        val slotAttesters = prop("slotAttesters")?.toInt() ?: (validatorCount / 32)
        // Today's 240-byte attestation with its 96-byte BLS signature swapped for a leanXMSS one.
        val voteBytes = 240 - 96 + sigBytes
        val slotCount = System.getProperty("dc.slots")?.toInt() ?: 2
        val warmupSlots = System.getProperty("dc.warmupSlots")?.toInt() ?: (slotCount - 1)
        val iWantPolicy = IWantPolicy.valueOf((System.getProperty("dc.iwant") ?: "disciplined").uppercase())

        println(
            "PQ attestations: $slotAttesters votes/slot of $voteBytes B on $subnets subnets at $attestAt " +
                "(${slotAttesters / subnets} votes, ${slotAttesters / subnets * voteBytes / 1024} KiB per subnet); " +
                "${subnets * aggregatesPerSubnet} aggregates of ${aggregateBytes / 1024} KiB at $aggregateAt; " +
                "final proof: ${finalAt?.let { "${finalBytes / 1024} KiB at $it" } ?: "none"}; background=$background"
        )

        val gossipParams = GossipParams.builder()
            .D(8).DLow(6).DHigh(12).DLazy(6).heartbeatInterval(700.millis)
            .build()
            .copy(iWantPolicy = iWantPolicy)

        val nodeCount = 1000
        val validatorsPerNode = validatorCount / nodeCount
        val network = DcNetworkBuilder
            .world(
                subnetCounts = mapOf(
                    DcSlotMessageType.PAYLOAD_CHUNK to 64,
                    DcSlotMessageType.BLOB_COLUMN to 128,
                    DcSlotMessageType.FFG_ATTESTATION to subnets
                )
            )
            .defaults {
                allMessageSubnets(DcSlotMessageType.PAYLOAD_CHUNK)
                this.gossipParams = gossipParams
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
                name = "vps"
                spreadOverRegions()
                bandwidth = Bandwidths.VPS
                validators = validatorsPerNode
                peers = 100
                randomMessageSubnets(DcSlotMessageType.BLOB_COLUMN, count = 16)
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = vpsSubnets)
            }
            .addGroup(count = 100) {
                name = "upper-home"
                spreadOverRegions()
                bandwidth = DcLink(mbitPerSecond(200), mbitPerSecond(100))
                validators = validatorsPerNode
                peers = 100
                randomMessageSubnets(DcSlotMessageType.BLOB_COLUMN, count = 8)
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = homeSubnets)
            }
            .addGroup(count = 100) {
                name = "mid-home"
                spreadOverRegions()
                bandwidth = DcLink(mbitPerSecond(100), mbitPerSecond(50))
                validators = validatorsPerNode
                peers = 100
                randomMessageSubnets(DcSlotMessageType.BLOB_COLUMN, count = 8)
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = homeSubnets)
            }
            .addGroup(count = 100) {
                name = "home"
                spreadOverRegions()
                bandwidth = DcLink(mbitPerSecond(50), mbitPerSecond(25))
                validators = validatorsPerNode
                peers = 100
                randomMessageSubnets(DcSlotMessageType.BLOB_COLUMN, count = 8)
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = weakHomeSubnets)
            }
            .build()

        val graph = network.peerGraph(minPeersPerSubnet = 8)
        Assertions.assertThat(graph.subnetDeficiencies()).isEmpty()

        val numberOfBlobs = 21
        val backgroundMessages = if (!background) {
            emptyList()
        } else {
            listOf(
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
                    sizeBytes = 1024 * 1024 / 64,
                    publishOffset = 1.seconds,
                    publisherGroups = setOf("validator-pools"),
                    publisherSelection = DcPublisherSelection.VALIDATOR_WEIGHTED,
                    messagesPerSlot = 64,
                    topics = DcSlotMessageTopics.Subnets(64)
                ),
                DcSlotMessageConfig(
                    type = DcSlotMessageType.BLOB_COLUMN,
                    sizeBytes = 128 * 1024 * 2 * numberOfBlobs / 128,
                    publishOffset = 1.seconds,
                    publisherGroups = setOf("validator-pools"),
                    publisherSelection = DcPublisherSelection.VALIDATOR_WEIGHTED,
                    messagesPerSlot = 128,
                    topics = DcSlotMessageTopics.Subnets(128)
                )
            )
        }

        val pqMessages = listOfNotNull(
            DcSlotMessageConfig(
                type = DcSlotMessageType.FFG_ATTESTATION,
                sizeBytes = voteBytes,
                publishOffset = attestAt,
                publisherSelection = DcPublisherSelection.RANDOM_VALIDATORS,
                messagesPerSlot = slotAttesters,
                topics = DcSlotMessageTopics.Subnets(subnets)
            ),
            // The aggregators are pool nodes: the only group subscribed to every subnet, and the
            // only one whose hardware the proving-time offset is measured for.
            DcSlotMessageConfig(
                type = DcSlotMessageType.AGGREGATES,
                sizeBytes = aggregateBytes,
                publishOffset = aggregateAt,
                publisherGroups = setOf("validator-pools"),
                publisherSelection = DcPublisherSelection.RANDOM_VALIDATORS,
                messagesPerSlot = subnets * aggregatesPerSubnet,
                topics = DcSlotMessageTopics.Global
            ),
            finalAt?.let {
                DcSlotMessageConfig(
                    type = DcSlotMessageType.AGGREGATES,
                    sizeBytes = finalBytes,
                    publishOffset = it,
                    publisherGroups = setOf("validator-pools"),
                    publisherSelection = DcPublisherSelection.RANDOM_VALIDATORS,
                    messagesPerSlot = 1,
                    topics = DcSlotMessageTopics.Global
                )
            }
        )

        val runConfig = DcRunConfig(
            slotCount = slotCount,
            warmupSlots = warmupSlots,
            messages = backgroundMessages + pqMessages,
            settle = (System.getProperty("dc.settleSeconds")?.toInt() ?: 12).seconds
        )

        val report = DcScenario.run(
            network = network,
            graph = graph,
            config = DcScenarioRunnerTest.withStreamLanes(DcScenarioRunnerTest.withQuicStats(runConfig))
        )
        println(report)
    }
}
