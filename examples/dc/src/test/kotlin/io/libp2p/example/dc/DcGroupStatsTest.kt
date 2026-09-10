package io.libp2p.example.dc

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Per-group breakdown of a run: the same report as always, but with byte counts, mesh sizes and
 * delivery ratios also split by [DcNodeGroup.name] rather than only averaged over the whole network.
 *
 * The individual figures are exercised elsewhere (block delivery in [DcBlockScenarioTest], byte
 * counting in [DcAttestationStatsTest]); what is worth checking here is that the per-group split
 * reconciles exactly with the whole-network totals it is carved out of, since groups partition the
 * population and every node ends up counted in exactly one of them.
 */
class DcGroupStatsTest {

    /** 4 nodes, each drawing a fresh independent random attester -- distinct from `RANDOM_NODE`. */
    private fun ffgMessages(subnetCount: Int = 2) = listOf(
        DcSlotMessageConfig(
            type = DcSlotMessageType.FFG_ATTESTATION,
            sizeBytes = 240,
            publisherSelection = DcPublisherSelection.RANDOM_NODES,
            messagesPerSlot = 4,
            topics = DcSlotMessageTopics.Subnets(subnetCount)
        )
    )

    /** `pools` holds the bulk of the validators, `home` the tail — same shape as [DcBlockScenarioTest]. */
    private fun namedGroups() = DcNetworkBuilder.world(randomSeed = 1, subnetCount = 2)
        .defaults {
            spreadOverRegions()
            bandwidth = Bandwidths.RESIDENTIAL
            peers = 5
            randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = 1, of = 2)
        }
        .addGroup(count = 2) {
            name = "pools"
            validators = 100
        }
        .addGroup(count = 8) {
            name = "home"
            validators = 1
        }
        .build()

    @Test
    fun `a run with no named groups reports no group breakdown`() {
        val network = DcNetworkBuilder.world(randomSeed = 1, subnetCount = 2)
            .addGroup(count = 12) {
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 1
                peers = 5
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = 1, of = 2)
            }
            .build()
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcAttestationConfig(waveCount = 1, messages = ffgMessages(), randomSeed = 7)

        val report = DcAttestationScenario.run(network, graph, config)

        assertThat(network.groupNames()).isEmpty()
        assertThat(report.groups.groups).isEmpty()
        assertThat(report.toString()).doesNotContain("per group")
    }

    @Test
    fun `population counts per group reconcile with the network`() {
        val network = namedGroups()
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcAttestationConfig(waveCount = 1, settle = 12.seconds, messages = ffgMessages(), randomSeed = 7)

        val report = DcAttestationScenario.run(network, graph, config)

        assertThat(report.groups.groupNames).containsExactlyInAnyOrder("pools", "home")
        val pools = requireNotNull(report.groups["pools"])
        val home = requireNotNull(report.groups["home"])
        assertThat(pools.nodeCount).isEqualTo(2)
        assertThat(pools.validatorCount).isEqualTo(200)
        assertThat(home.nodeCount).isEqualTo(8)
        assertThat(home.validatorCount).isEqualTo(8)
        assertThat(pools.nodeCount + home.nodeCount).isEqualTo(network.nodeCount)
        assertThat(pools.validatorCount + home.validatorCount).isEqualTo(network.validatorCount)
        assertThat(pools.links).containsExactly(Bandwidths.RESIDENTIAL.toString())
    }

    @Test
    fun `per-group udp and gossip byte totals add up to the whole-run totals`() {
        val network = namedGroups()
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcAttestationConfig(waveCount = 2, settle = 12.seconds, messages = ffgMessages(), randomSeed = 7)

        val report = DcAttestationScenario.run(network, graph, config)
        val groups = report.groups.groups.values

        assertThat(groups.sumOf { it.traffic.bytesSent }).isEqualTo(report.traffic.overall.bytesSent)
        assertThat(groups.sumOf { it.traffic.bytesReceived }).isEqualTo(report.traffic.overall.bytesReceived)
        assertThat(groups.sumOf { it.gossipBytesSent }).isEqualTo(report.gossipBytesSent)
        assertThat(groups.sumOf { it.gossipBytesReceived }).isEqualTo(report.gossipBytesReceived)
        assertThat(groups.sumOf { it.gossipPublishBytesSent }).isEqualTo(report.gossipPublishBytesSent)
        assertThat(groups.sumOf { it.gossipPublishBytesReceived }).isEqualTo(report.gossipPublishBytesReceived)
    }

    @Test
    fun `a message restricted to one group is published only there but received by everyone`() {
        val network = namedGroups()
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcAttestationConfig(
            waveCount = 2,
            settle = 12.seconds,
            blocks = DcBlockConfig(sizeBytes = 32 * 1024, proposerGroups = setOf("pools")),
            messages = ffgMessages(),
            randomSeed = 7
        )

        val report = DcAttestationScenario.run(network, graph, config)
        val pools = requireNotNull(report.groups["pools"])
        val home = requireNotNull(report.groups["home"])
        val poolsBlocks = requireNotNull(pools.messagesReceived[DcSlotMessageType.BLOCK])
        val homeBlocks = requireNotNull(home.messagesReceived[DcSlotMessageType.BLOCK])
        val blocks = requireNotNull(report.messages[DcSlotMessageType.BLOCK])

        assertThat(poolsBlocks.publishedCount + homeBlocks.publishedCount)
            .describedAs("every block came from pools, the only allowed proposer group")
            .isEqualTo(blocks.overall.publishedCount)
        assertThat(homeBlocks.publishedCount)
            .describedAs("home never proposes under proposerGroups = [pools]")
            .isZero()

        // The block topic is global, so every group's nodes still expect and receive it.
        assertThat(poolsBlocks.deliveryRatio).isEqualTo(1.0)
        assertThat(homeBlocks.deliveryRatio).isEqualTo(1.0)
        assertThat(poolsBlocks.expectedDeliveries + homeBlocks.expectedDeliveries)
            .isEqualTo(blocks.overall.expectedDeliveries)
        assertThat(poolsBlocks.actualDeliveries + homeBlocks.actualDeliveries)
            .isEqualTo(blocks.overall.actualDeliveries)
    }

    @Test
    fun `every configured message type appears in every group, FFG attestations included`() {
        val network = namedGroups()
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcAttestationConfig(
            waveCount = 2,
            settle = 12.seconds,
            blocks = DcBlockConfig(sizeBytes = 32 * 1024),
            messages = ffgMessages(),
            randomSeed = 7
        )

        val report = DcAttestationScenario.run(network, graph, config)

        report.groups.groups.values.forEach { group ->
            val types: Set<DcSlotMessageType> = group.messagesReceived.keys
            assertThat(types)
                .containsExactlyInAnyOrder(DcSlotMessageType.FFG_ATTESTATION, DcSlotMessageType.BLOCK)
        }
        val poolsFfg = report.groups["pools"]!!.messagesReceived.getValue(DcSlotMessageType.FFG_ATTESTATION)
        val homeFfg = report.groups["home"]!!.messagesReceived.getValue(DcSlotMessageType.FFG_ATTESTATION)
        assertThat(poolsFfg.expectedDeliveries + homeFfg.expectedDeliveries)
            .isEqualTo(report.overall.expectedDeliveries)
        assertThat(poolsFfg.actualDeliveries + homeFfg.actualDeliveries)
            .isEqualTo(report.overall.actualDeliveries)
    }

    @Test
    fun `mesh sizes are reported per group`() {
        val network = namedGroups()
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcAttestationConfig(waveCount = 1, settle = 12.seconds, messages = ffgMessages(), randomSeed = 7)

        val report = DcAttestationScenario.run(network, graph, config)

        val pools = requireNotNull(report.groups["pools"]?.mesh)
        val home = requireNotNull(report.groups["home"]?.mesh)
        assertThat(pools.nodeCount).isEqualTo(2)
        assertThat(home.nodeCount).isEqualTo(8)
    }

    @Test
    fun `an unnamed remainder is gathered under the unnamed bucket rather than dropped`() {
        val network = DcNetworkBuilder.world(randomSeed = 1, subnetCount = 2)
            .defaults {
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                peers = 4
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = 1, of = 2)
            }
            .addGroup(count = 2) {
                name = "pools"
                validators = 4
            }
            .addGroup(count = 6) { validators = 1 }
            .build()
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcAttestationConfig(waveCount = 1, settle = 12.seconds, messages = ffgMessages(), randomSeed = 7)

        val report = DcAttestationScenario.run(network, graph, config)

        assertThat(report.groups.groupNames).containsExactlyInAnyOrder("pools", DcGroupStats.UNNAMED)
        assertThat(report.groups[DcGroupStats.UNNAMED]!!.nodeCount).isEqualTo(6)
        assertThat(
            report.groups.groups.values.sumOf { it.nodeCount }
        ).isEqualTo(network.nodeCount)
    }

    @Test
    fun `toString prints a per-group section when groups were named`() {
        val network = namedGroups()
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcAttestationConfig(waveCount = 1, settle = 12.seconds, messages = ffgMessages(), randomSeed = 7)

        val report = DcAttestationScenario.run(network, graph, config)

        assertThat(report.toString())
            .contains("--- per group ---")
            .contains("group 'pools'")
            .contains("group 'home'")
    }

    @Test
    fun `each named group gets its own slot traffic profile, sized to that group alone`() {
        val network = namedGroups()
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcAttestationConfig(
            waveCount = 2,
            waveInterval = 12.seconds,
            settle = 12.seconds,
            blocks = DcBlockConfig(sizeBytes = 8 * 1024, publishOffset = 2.seconds, proposerGroups = setOf("pools")),
            messages = ffgMessages(),
            randomSeed = 7
        )

        val report = DcAttestationScenario.run(network, graph, config)

        val poolsProfile = requireNotNull(report.groups["pools"]?.slotTraffic)
        val homeProfile = requireNotNull(report.groups["home"]?.slotTraffic)
        assertThat(poolsProfile.nodeCount).isEqualTo(2)
        assertThat(homeProfile.nodeCount).isEqualTo(8)
        assertThat(poolsProfile.bucketCount).isEqualTo(report.slotTraffic!!.bucketCount)

        // The block topic is global, so both groups -- proposer and receiver-only alike -- see it.
        assertThat(poolsProfile.uniqueMessageBytesPerNode.getValue(DcSlotMessageType.BLOCK).sum())
            .describedAs("pools nodes receive every block their peer proposed too")
            .isGreaterThan(0L)
        assertThat(homeProfile.uniqueMessageBytesPerNode.getValue(DcSlotMessageType.BLOCK).sum())
            .describedAs("home never proposes here, but still receives blocks over gossip")
            .isGreaterThan(0L)
    }

    @Test
    fun `a run with no named groups has no per-group slot traffic to report`() {
        val network = DcNetworkBuilder.world(randomSeed = 1, subnetCount = 2)
            .addGroup(count = 12) {
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 1
                peers = 5
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = 1, of = 2)
            }
            .build()
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcAttestationConfig(waveCount = 1, settle = 12.seconds, messages = ffgMessages(), randomSeed = 7)

        val report = DcAttestationScenario.run(network, graph, config)

        assertThat(report.groups.groups).isEmpty()
        assertThat(report.slotTraffic)
            .describedAs("the whole-network profile still exists even with no groups named")
            .isNotNull()
    }

    @Test
    fun `toString prints each group's own slot traffic table underneath its other figures`() {
        val network = namedGroups()
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcAttestationConfig(waveCount = 1, settle = 12.seconds, messages = ffgMessages(), randomSeed = 7)

        val report = DcAttestationScenario.run(network, graph, config)

        val poolsText = requireNotNull(report.groups["pools"]).toString()
        assertThat(poolsText).contains("slot traffic profile")
        val homeText = requireNotNull(report.groups["home"]).toString()
        assertThat(homeText).contains("slot traffic profile")
    }
}
