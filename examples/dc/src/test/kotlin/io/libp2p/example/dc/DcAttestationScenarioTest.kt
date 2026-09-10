package io.libp2p.example.dc

import io.libp2p.pubsub.gossip.GossipParams
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end attestation dissemination runs on the QUIC simulator.
 *
 * These run in virtual time, but the packet-level simulation is real work: keep node counts modest
 * unless you are deliberately running a large study.
 */
class DcAttestationScenarioTest {

    private fun population(
        nodeCount: Int,
        subnetCount: Int,
        subnetsPerNode: Int,
        peers: Int,
        validatorsPerNode: Int = 1
    ) =
        DcNetworkBuilder.world(randomSeed = 1)
            .addGroup(count = nodeCount) {
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                validators = validatorsPerNode
                this.peers = peers
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = subnetsPerNode, of = subnetCount)
            }
            .build()

    /** FFG attestations drawn as [attestersPerWave] distinct nodes per wave, over [subnetCount] subnets. */
    private fun ffgMessages(attestersPerWave: Int, subnetCount: Int) = listOf(
        DcSlotMessageConfig(
            type = DcSlotMessageType.FFG_ATTESTATION,
            sizeBytes = 240,
            publisherSelection = DcPublisherSelection.RANDOM_NODES,
            messagesPerSlot = attestersPerWave,
            topics = DcSlotMessageTopics.Subnets(subnetCount)
        )
    )

    @Test
    fun `attestation waves reach every subscriber and report latency percentiles`() {
        val network = population(nodeCount = 40, subnetCount = 4, subnetsPerNode = 2, peers = 10)
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val attestersPerWave = 8
        val config = DcAttestationConfig(
            waveCount = 2,
            warmup = 30.seconds,
            waveInterval = 12.seconds,
            settle = 12.seconds,
            messages = ffgMessages(attestersPerWave, subnetCount = 4),
            randomSeed = 7
        )

        val report = DcAttestationScenario.run(network, graph, config)
        println(report)

        assertThat(report.messages).containsKey(DcSlotMessageType.FFG_ATTESTATION)
        assertThat(report.messages.getValue(DcSlotMessageType.FFG_ATTESTATION).overall)
            .isEqualTo(report.overall)
        assertThat(report.overall.publishedCount).isEqualTo(config.waveCount * attestersPerWave)
        assertThat(report.overall.deliveryRatio)
            .describedAs("delivery ratio; percentiles are meaningless if attestations went missing")
            .isEqualTo(1.0)
        assertThat(report.overall.p50).isNotNull()
        assertThat(report.overall.p50!!).isLessThanOrEqualTo(report.overall.p95!!)
        assertThat(report.overall.p95!!).isLessThanOrEqualTo(report.overall.p99!!)
        assertThat(report.overall.p99!!).isLessThanOrEqualTo(config.settle)
        assertThat(report.perWave.keys).containsExactly(0, 1)

        // Publish bytes are attributed to a wave by reading the wave index back out of the payload.
        // Requiring the per-wave figures to add up to the aggregate proves every publish message
        // was recognised: an unparsed payload would be silently dropped from the breakdown.
        assertThat(report.gossipPublishBytesReceivedByWave.keys).containsExactlyInAnyOrder(0, 1)
        assertThat(report.gossipPublishBytesReceivedByWave.values.sum())
            .isEqualTo(report.gossipPublishBytesReceived)
        assertThat(report.gossipPublishBytesSentByWave.values.sum())
            .isEqualTo(report.gossipPublishBytesSent)
    }

    @Test
    fun `warmupWaves excludes the leading waves from the headline figures`() {
        val network = population(nodeCount = 40, subnetCount = 4, subnetsPerNode = 2, peers = 10)
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val base = DcAttestationConfig(
            waveCount = 4,
            warmupWaves = 0,
            messages = ffgMessages(attestersPerWave = 8, subnetCount = 4),
            randomSeed = 7
        )
        val all = DcAttestationScenario.run(network, graph, base)
        val measured = DcAttestationScenario.run(network, graph, base.copy(warmupWaves = 2))

        // Every wave is still reported; only the aggregate narrows.
        assertThat(measured.perWave.keys).containsExactlyInAnyOrderElementsOf(all.perWave.keys)
        assertThat(measured.measuredWaves).containsExactly(2, 3)

        // The headline now covers exactly the deliveries of waves 2-3.
        val tailDeliveries = (2..3).sumOf { all.perWave.getValue(it).actualDeliveries }
        assertThat(measured.overall.actualDeliveries).isEqualTo(tailDeliveries)
        assertThat(measured.overall.actualDeliveries).isLessThan(all.overall.actualDeliveries)
        assertThat(measured.overall.deliveryRatio).isEqualTo(1.0)

        // Duplication has to narrow with it: counting all-wave messages against measured-wave
        // deliveries would inflate it by roughly the ratio of excluded waves.
        assertThat(measured.duplicationFactor)
            .describedAs("duplication over waves 2-3")
            .isCloseTo(all.duplicationFactor, org.assertj.core.data.Offset.offset(0.5))
        assertThat(measured.measuredPublishBytesReceived)
            .isLessThan(all.gossipPublishBytesReceived)
    }

    @Test
    fun `warmupWaves must leave a wave to measure`() {
        assertThatThrownBy {
            DcAttestationConfig(
                waveCount = 3,
                warmupWaves = 3,
                messages = ffgMessages(attestersPerWave = 1, subnetCount = 1)
            )
        }.hasMessageContaining("must leave at least one measured wave")
    }

    @Test
    fun `the same seed produces the same latencies`() {
        val network = population(nodeCount = 24, subnetCount = 4, subnetsPerNode = 2, peers = 8)
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 3)
        val config = DcAttestationConfig(
            waveCount = 1,
            warmup = 30.seconds,
            settle = 12.seconds,
            messages = ffgMessages(attestersPerWave = 4, subnetCount = 4),
            randomSeed = 11
        )

        val first = DcAttestationScenario.run(network, graph, config)
        val second = DcAttestationScenario.run(network, graph, config)

        assertThat(second.overall.p50).isEqualTo(first.overall.p50)
        assertThat(second.overall.p99).isEqualTo(first.overall.p99)
        assertThat(second.overall.actualDeliveries).isEqualTo(first.overall.actualDeliveries)
    }

    @Test
    fun `RANDOM_NODES picks the requested number of distinct publishers per slot`() {
        val network = population(nodeCount = 40, subnetCount = 8, subnetsPerNode = 2, peers = 10)
        val schedule = DcSlotMessageSchedule.create(
            network = network,
            waves = DcSlotMessageWaves(count = 3, first = 30.seconds, interval = 12.seconds),
            config = DcSlotMessageConfig(
                type = DcSlotMessageType.FFG_ATTESTATION,
                sizeBytes = 240,
                publisherSelection = DcPublisherSelection.RANDOM_NODES,
                messagesPerSlot = 10,
                topics = DcSlotMessageTopics.Subnets(8)
            ),
            randomSeed = 4
        )

        assertThat(schedule.messages).hasSize(30)
        schedule.messages.groupBy { it.slotIndex }.forEach { (slot, messages) ->
            assertThat(messages.map { it.publisherNodeId })
                .describedAs("publishers in slot %s", slot)
                .hasSize(10)
                .doesNotHaveDuplicates()
        }
        // a publisher always attests on a subnet it actually subscribes to
        schedule.messages.forEach { message ->
            assertThat(network.node(message.publisherNodeId).subnetIdsFor(DcSlotMessageType.FFG_ATTESTATION))
                .contains(requireNotNull(message.subnetId))
        }
        assertThat(schedule.messagesOf(schedule.messages.first().publisherNodeId)).isNotEmpty()
    }

    @Test
    fun `RANDOM_VALIDATORS draws the requested publishers per slot from the validator set`() {
        // 10 nodes x 4 validators = 40 validator slots, so 25 per slot is well over the node count.
        val network = population(
            nodeCount = 10,
            subnetCount = 4,
            subnetsPerNode = 1,
            peers = 4,
            validatorsPerNode = 4
        )
        val schedule = DcSlotMessageSchedule.create(
            network = network,
            waves = DcSlotMessageWaves(count = 3, first = 30.seconds, interval = 12.seconds),
            config = DcSlotMessageConfig(
                type = DcSlotMessageType.FFG_ATTESTATION,
                sizeBytes = 240,
                publisherSelection = DcPublisherSelection.RANDOM_VALIDATORS,
                messagesPerSlot = 25,
                topics = DcSlotMessageTopics.Subnets(4)
            ),
            randomSeed = 4
        )

        assertThat(schedule.messages).hasSize(75)
        schedule.messages.groupBy { it.slotIndex }.forEach { (slot, messages) ->
            assertThat(messages)
                .describedAs(
                    "publishers in slot %s; drawing over validators rather than nodes is " +
                        "what lets a slot exceed the 10 nodes",
                    slot
                )
                .hasSize(25)
        }
        schedule.messages.forEach { message ->
            assertThat(network.node(message.publisherNodeId).subnetIdsFor(DcSlotMessageType.FFG_ATTESTATION))
                .contains(requireNotNull(message.subnetId))
        }
    }

    @Test
    fun `RANDOM_VALIDATORS rejects asking for more publishers than there are validators`() {
        val network = population(
            nodeCount = 10,
            subnetCount = 4,
            subnetsPerNode = 1,
            peers = 4,
            validatorsPerNode = 4
        )

        assertThatThrownBy {
            DcSlotMessageSchedule.create(
                network = network,
                slotTimes = listOf(30.seconds),
                config = DcSlotMessageConfig(
                    type = DcSlotMessageType.FFG_ATTESTATION,
                    sizeBytes = 240,
                    publisherSelection = DcPublisherSelection.RANDOM_VALIDATORS,
                    messagesPerSlot = 41,
                    topics = DcSlotMessageTopics.Subnets(4)
                )
            )
        }.hasMessageContaining("exceeds the 40 eligible validators")
    }

    @Test
    fun `uploadBandwidth makes the access link asymmetric in the topology`() {
        val down = Bandwidths.mbitPerSecond(50)
        val up = Bandwidths.mbitPerSecond(25)
        val network = DcNetworkBuilder.world(randomSeed = 1)
            .addGroup(count = 4) {
                spreadOverRegions()
                bandwidth = down
                uploadBandwidth = up
                validators = 1
                peers = 2
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = 1, of = 2)
            }
            .build()

        network.nodes.forEach { node ->
            assertThat(node.bandwidthBytesPerSecond).isEqualTo(down.bytesPerSecond)
            assertThat(node.uploadBandwidthBytesPerSecond).isEqualTo(up.bytesPerSecond)
            assertThat(node.hasAsymmetricLink).isTrue()
        }

        // The two directions are separate links, so check the rate landed on the right one:
        // host -> router carries the upload rate, router -> host the download rate. Getting these
        // the wrong way round would still look asymmetric while throttling the wrong direction.
        val hostIds = network.nodes.map { it.id }.toSet()
        val uplinks = network.topology.links.filter { it.from in hostIds }
        val downlinks = network.topology.links.filter { it.to in hostIds }
        assertThat(uplinks).hasSize(network.nodes.size)
        assertThat(downlinks).hasSize(network.nodes.size)
        assertThat(uplinks.map { it.bandwidthBytesPerSecond }.distinct())
            .containsExactly(up.bytesPerSecond)
        assertThat(downlinks.map { it.bandwidthBytesPerSecond }.distinct())
            .containsExactly(down.bytesPerSecond)
    }

    @Test
    fun `omitting uploadBandwidth keeps the link symmetric`() {
        val network = population(nodeCount = 4, subnetCount = 2, subnetsPerNode = 1, peers = 2)

        network.nodes.forEach { node ->
            assertThat(node.uploadBandwidthBytesPerSecond).isEqualTo(node.bandwidthBytesPerSecond)
            assertThat(node.hasAsymmetricLink).isFalse()
        }
    }

    @Test
    fun `sweep D values build valid params with a D plus-minus-one mesh band`() {
        // Guards the D x subnetCount sweep: GossipParams validates DOut < DLow and DOut <= D/2,
        // and DOut is re-derived from whatever DLow is set explicitly, so a low D could otherwise
        // only blow up once the sweep reached it.
        listOf(6, 5, 4, 3).forEach { d ->
            val params = GossipParams.builder()
                .D(d)
                .DLow(d - 1)
                .DHigh(d + 1)
                .DLazy(0)
                .gossipFactor(0.0)
                .gossipSize(0)
                .build()

            assertThat(params.D).describedAs("D").isEqualTo(d)
            assertThat(params.DLow).describedAs("DLow for D=%s", d).isEqualTo(d - 1)
            assertThat(params.DHigh).describedAs("DHigh for D=%s", d).isEqualTo(d + 1)
            assertThat(params.DOut).describedAs("DOut for D=%s", d).isLessThan(params.DLow)
            assertThat(params.DOut).describedAs("DOut for D=%s", d).isLessThanOrEqualTo(d / 2)
            assertThat(params.DLazy).describedAs("DLazy stays 0 for D=%s", d).isEqualTo(0)
        }
    }

    @Test
    fun `leaving the lazy gossip params unset turns IHAVE on at its defaults`() {
        // The sweep's lazyGossip = true branch works by *not* calling DLazy/gossipFactor/gossipSize,
        // relying on the builder to fill them in. If that ever stopped holding, the IHAVE sweep
        // would silently measure the mesh-only configuration again.
        listOf(6, 5, 4, 3).forEach { d ->
            val lazy = GossipParams.builder().D(d).DLow(d - 1).DHigh(d + 1).build()

            assertThat(lazy.DLazy).describedAs("DLazy defaults to D for D=%s", d).isEqualTo(d)
            assertThat(lazy.gossipFactor).describedAs("gossipFactor for D=%s", d).isEqualTo(0.25)
            assertThat(lazy.gossipSize).describedAs("gossipSize for D=%s", d).isEqualTo(3)
        }
    }

    @Test
    fun `RANDOM_NODES rejects asking for more publishers than there are eligible nodes`() {
        val network = population(nodeCount = 10, subnetCount = 4, subnetsPerNode = 1, peers = 4)

        assertThatThrownBy {
            DcSlotMessageSchedule.create(
                network = network,
                slotTimes = listOf(30.seconds),
                config = DcSlotMessageConfig(
                    type = DcSlotMessageType.FFG_ATTESTATION,
                    sizeBytes = 240,
                    publisherSelection = DcPublisherSelection.RANDOM_NODES,
                    messagesPerSlot = 50,
                    topics = DcSlotMessageTopics.Subnets(4)
                )
            )
        }.hasMessageContaining("exceeds the 10 eligible nodes")
    }
}
