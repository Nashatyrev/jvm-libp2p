package io.libp2p.example.dc

import com.google.protobuf.ByteString
import io.libp2p.pubsub.gossip.GossipParams
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Block issuance: one block per slot, of a configured size, published a configured offset into the
 * slot.
 *
 * The runs here are end-to-end on the QUIC simulator. They run in virtual time, but the
 * packet-level simulation is real work: keep node counts modest.
 */
class DcBlockScenarioTest {

    private fun population(nodeCount: Int, subnetCount: Int, peers: Int) =
        DcNetworkBuilder.world(randomSeed = 1, subnetCount = subnetCount)
            .addGroup(count = nodeCount) {
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 1
                this.peers = peers
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = 2, of = subnetCount)
            }
            .build()

    /** FFG attestations drawn as [attestersPerSlot] distinct nodes per slot, over [subnetCount] subnets. */
    private fun ffgMessages(attestersPerSlot: Int, subnetCount: Int) = listOf(
        DcSlotMessageConfig(
            type = DcSlotMessageType.FFG_ATTESTATION,
            sizeBytes = 240,
            publisherSelection = DcPublisherSelection.RANDOM_NODES,
            messagesPerSlot = attestersPerSlot,
            topics = DcSlotMessageTopics.Subnets(subnetCount)
        )
    )

    @Test
    fun `every node but the proposer receives each block, at the configured offset`() {
        val network = population(nodeCount = 20, subnetCount = 4, peers = 8)
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcRunConfig(
            slotCount = 2,
            messages = ffgMessages(attestersPerSlot = 4, subnetCount = 4),
            slotInterval = 12.seconds,
            settle = 12.seconds,
            blocks = DcBlockConfig(sizeBytes = 128 * 1024, publishOffset = 2.seconds),
            randomSeed = 7
        )

        val report = DcScenario.run(network, graph, config)
        println(report)

        val blocks = requireNotNull(report.messages[DcSlotMessageType.BLOCK]) {
            "blocks were configured, so a block report is due"
        }
        assertThat(blocks.overall.publishedCount)
            .describedAs("one block per slot")
            .isEqualTo(config.slotCount)
        assertThat(blocks.overall.deliveryRatio)
            .describedAs("block delivery ratio; percentiles are meaningless if blocks went missing")
            .isEqualTo(1.0)
        assertThat(blocks.overall.expectedDeliveries)
            .describedAs("the block topic is global, so every node but the proposer expects it")
            .isEqualTo(config.slotCount * (network.nodeCount - 1))

        // The offset is the point of the knob, so check the proposers really published that far into
        // their slot rather than on its boundary.
        config.slotTimes.forEachIndexed { slot, slotTime ->
            assertThat(blocks.publishTimes[slot])
                .describedAs("publish time of the block of slot %s", slot)
                .containsExactly(slotTime + 2.seconds)
        }

        // ... and the size lands on the wire: each node receives every block at least once, so its
        // inbound publish bytes cannot be less than the blocks alone.
        val blockBytesPerNode = config.slotCount.toLong() * config.blocks!!.sizeBytes
        assertThat(report.gossipPublishBytesReceived / network.nodeCount)
            .describedAs("publish bytes received per node, which includes the blocks")
            .isGreaterThanOrEqualTo(blockBytesPerNode)
    }

    @Test
    fun `each slot message type has separate issuance and statistics`() {
        val network = namedGroups()
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcRunConfig(
            slotCount = 2,
            settle = 12.seconds,
            messages = ffgMessages(attestersPerSlot = 4, subnetCount = 2) + listOf(
                DcSlotMessageConfig(
                    type = DcSlotMessageType.PAYLOAD,
                    sizeBytes = 32 * 1024,
                    publishOffset = 1.seconds,
                    publisherGroups = setOf("pools")
                ),
                DcSlotMessageConfig(
                    type = DcSlotMessageType.PAYLOAD_CHUNK,
                    sizeBytes = 8 * 1024,
                    publishOffset = 2.seconds,
                    publisherGroups = setOf("pools"),
                    messagesPerSlot = 3
                ),
                DcSlotMessageConfig(
                    type = DcSlotMessageType.BLOB_COLUMN,
                    sizeBytes = 4 * 1024,
                    publishOffset = 3.seconds,
                    publisherGroups = setOf("pools"),
                    messagesPerSlot = 2,
                    topics = DcSlotMessageTopics.Subnets(subnetCount = 2)
                )
            ),
            randomSeed = 7
        )

        val report = DcScenario.run(network, graph, config)

        assertThat(report.messages.keys)
            .containsExactly(
                DcSlotMessageType.FFG_ATTESTATION,
                DcSlotMessageType.PAYLOAD,
                DcSlotMessageType.PAYLOAD_CHUNK,
                DcSlotMessageType.BLOB_COLUMN
            )
        val payload = report.messages.getValue(DcSlotMessageType.PAYLOAD)
        assertThat(payload.overall.publishedCount).isEqualTo(2)
        assertThat(payload.overall.deliveryRatio).isEqualTo(1.0)
        assertThat(payload.publishTimes[0]).containsExactly(config.slotTimes[0] + 1.seconds)

        val chunks = report.messages.getValue(DcSlotMessageType.PAYLOAD_CHUNK)
        assertThat(chunks.overall.publishedCount).isEqualTo(6)
        assertThat(chunks.perSlot.values.map { it.publishedCount }).containsOnly(3)
        assertThat(chunks.overall.deliveryRatio).isEqualTo(1.0)
        assertThat(chunks.publishTimes[1]).containsOnly(config.slotTimes[1] + 2.seconds)
        val columns = report.messages.getValue(DcSlotMessageType.BLOB_COLUMN)
        assertThat(columns.overall.publishedCount).isEqualTo(4)
        assertThat(columns.perSlot.values.map { it.publishedCount }).containsOnly(2)
        assertThat(columns.overall.deliveryRatio).isEqualTo(1.0)

        assertThat(
            (payload.publishers.values + chunks.publishers.values + columns.publishers.values)
                .flatten()
                .map { network.node(it).groupName }
        )
            .containsOnly("pools")
    }

    @Test
    fun `every message type namespaces its topics and allows one issuance config per offset`() {
        DcSlotMessageType.values().forEach { type ->
            assertThat(DcSlotMessageTopics.Global.topic(type, null).topic)
                .isEqualTo("/dc/${type.id}")
            assertThat(DcSlotMessageTopics.Subnets(8).topic(type, 3).topic)
                .isEqualTo("/dc/${type.id}/3")
        }
        assertThat(DcSlotMessageType.values().map { it.id }.distinct())
            .hasSize(DcSlotMessageType.values().size)

        // Same type at the same offset is still a mistake: two competing issuance configs.
        assertThatThrownBy {
            DcRunConfig(
                messages = ffgMessages(attestersPerSlot = 1, subnetCount = 1) + listOf(
                    DcSlotMessageConfig(DcSlotMessageType.BLOB_COLUMN, sizeBytes = 1024),
                    DcSlotMessageConfig(DcSlotMessageType.BLOB_COLUMN, sizeBytes = 2048)
                )
            )
        }.hasMessageContaining("message type/publishOffset pairs must be unique")

        // Same type at different offsets is how a type issues several waves within one slot.
        val waveConfig = DcRunConfig(
            slotInterval = 12.seconds,
            messages = (0 until 3).map { wave ->
                DcSlotMessageConfig(
                    type = DcSlotMessageType.FFG_ATTESTATION,
                    sizeBytes = 240,
                    publishOffset = wave.seconds,
                    topics = DcSlotMessageTopics.Subnets(1)
                )
            }
        )
        assertThat(waveConfig.messages.map { it.publishOffset })
            .containsExactly(0.seconds, 1.seconds, 2.seconds)
    }

    @Test
    fun `subnet messages do not fall back to beacon attestation subscriptions`() {
        val network = population(nodeCount = 16, subnetCount = 4, peers = 6)
        val config = DcSlotMessageConfig(
            type = DcSlotMessageType.PAYLOAD_CHUNK,
            sizeBytes = 1024,
            messagesPerSlot = 4,
            topics = DcSlotMessageTopics.Subnets(4)
        )

        assertThatThrownBy {
            DcSlotMessageSchedule.create(network, listOf(30.seconds), config)
        }.hasMessageContaining("${DcSlotMessageType.PAYLOAD_CHUNK} subnet topics have no subscribers: [0, 1, 2, 3]")
    }

    @Test
    fun `distinct message subnet families deliver only to their own subscribers`() {
        val network = DcNetworkBuilder.world(randomSeed = 1, subnetCount = 4)
            .addGroup(count = 24) {
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 1
                peers = 10
                allMessageSubnets(DcSlotMessageType.PAYLOAD_CHUNK, of = 4)
                allMessageSubnets(DcSlotMessageType.BLOB_COLUMN, of = 8)
                allMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, of = 2)
            }
            .build()
        val graph = network.peerGraph(minPeersPerSubnet = 1, randomSeed = 5)
        val config = DcRunConfig(
            slotCount = 1,
            settle = 12.seconds,
            messages = listOf(
                DcSlotMessageConfig(
                    DcSlotMessageType.PAYLOAD_CHUNK,
                    sizeBytes = 1024,
                    messagesPerSlot = 4,
                    topics = DcSlotMessageTopics.Subnets(4)
                ),
                DcSlotMessageConfig(
                    DcSlotMessageType.BLOB_COLUMN,
                    sizeBytes = 1024,
                    messagesPerSlot = 8,
                    topics = DcSlotMessageTopics.Subnets(8)
                ),
                DcSlotMessageConfig(
                    DcSlotMessageType.FFG_ATTESTATION,
                    sizeBytes = 240,
                    messagesPerSlot = 2,
                    topics = DcSlotMessageTopics.Subnets(2)
                )
            ),
            randomSeed = 7
        )
        val schedules = DcScenario.defaultMessageSchedules(network, config)

        val report = DcScenario.run(
            network = network,
            graph = graph,
            config = config,
            messageSchedules = schedules
        )

        schedules.forEach { messageSchedule ->
            val type = messageSchedule.config.type
            val expected = messageSchedule.messages.sumOf { message ->
                network.nodesSubscribedTo(type, requireNotNull(message.subnetId))
                    .count { it.simNodeId != message.publisherNodeId }
            }
            val messageReport = report.messages.getValue(type)
            assertThat(messageReport.overall.expectedDeliveries).isEqualTo(expected)
            assertThat(messageReport.overall.deliveryRatio).isEqualTo(1.0)
        }
    }

    @Test
    fun `a zero offset publishes the block on the slot boundary`() {
        val network = population(nodeCount = 16, subnetCount = 4, peers = 6)
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcRunConfig(
            slotCount = 1,
            messages = ffgMessages(attestersPerSlot = 4, subnetCount = 4),
            settle = 12.seconds,
            blocks = DcBlockConfig(sizeBytes = 64 * 1024),
            randomSeed = 7
        )

        val report = DcScenario.run(network, graph, config)

        assertThat(config.blocks!!.publishOffset).isEqualTo(Duration.ZERO)
        val blocks = report.messages.getValue(DcSlotMessageType.BLOCK)
        assertThat(blocks.publishTimes[0]).containsExactly(config.slotTimes[0])
        assertThat(blocks.overall.deliveryRatio).isEqualTo(1.0)
    }

    @Test
    fun `a run without a block config issues no blocks and reports none`() {
        val network = population(nodeCount = 16, subnetCount = 4, peers = 6)
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcRunConfig(
            slotCount = 1,
            messages = ffgMessages(attestersPerSlot = 4, subnetCount = 4),
            randomSeed = 7
        )

        val report = DcScenario.run(network, graph, config)

        assertThat(config.blocks).isNull()
        assertThat(report.messages[DcSlotMessageType.BLOCK]).isNull()
        assertThat(report.overall.deliveryRatio).isEqualTo(1.0)
    }

    @Test
    fun `the settle window starts from the block rather than from the slot boundary`() {
        val base = DcRunConfig(
            slotCount = 2,
            slotInterval = 12.seconds,
            settle = 12.seconds,
            messages = ffgMessages(attestersPerSlot = 1, subnetCount = 1)
        )
        val withBlocks = base.copy(blocks = DcBlockConfig(publishOffset = 2.seconds))

        // Without the extra offset the last block would only get 10s of the 12s settle window, and
        // anything slower than that would be reported as an undelivered block.
        assertThat(withBlocks.completeAt).isEqualTo(base.completeAt + 2.seconds)
    }

    @Test
    fun `a block published later than a whole slot is rejected`() {
        assertThatThrownBy {
            DcRunConfig(
                slotInterval = 12.seconds,
                messages = ffgMessages(attestersPerSlot = 1, subnetCount = 1),
                blocks = DcBlockConfig(publishOffset = 12.seconds)
            )
        }.hasMessageContaining("publishOffset must be less than")
    }

    @Test
    fun `a block too large for gossipsub to carry is rejected`() {
        // The default maxGossipMessageSize is exactly 1 MiB, so a 1 MiB block cannot fit: gossipsub
        // reserves a 1% margin when splitting RPCs and the frame decoder drops anything above the
        // limit. Failing here beats a run in which no block ever arrives.
        assertThatThrownBy {
            DcRunConfig(
                messages = ffgMessages(attestersPerSlot = 1, subnetCount = 1),
                blocks = DcBlockConfig(sizeBytes = 1 shl 20)
            )
        }.hasMessageContaining("exceeds what gossipsub will carry")

        val roomier = GossipParams.builder().maxGossipMessageSize(4 shl 20).build()
        val config = DcRunConfig(
            messages = ffgMessages(attestersPerSlot = 1, subnetCount = 1),
            blocks = DcBlockConfig(sizeBytes = 1 shl 20),
            gossipParams = roomier
        )
        assertThat(config.blocks!!.sizeBytes).isEqualTo(1 shl 20)
    }

    @Test
    fun `one validator-weighted proposer per slot, publishing at the slot time plus the offset`() {
        val network = DcNetworkBuilder.world(randomSeed = 1, subnetCount = 4)
            // A node with 100 validators alongside 10 with one each: it should propose most slots.
            .addGroup(count = 1) {
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 100
                peers = 4
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = 1, of = 4)
            }
            .addGroup(count = 10) {
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 1
                peers = 4
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = 1, of = 4)
            }
            .build()
        val slotTimes = DcSlotCadence(count = 20, first = 30.seconds, interval = 12.seconds).times

        val schedule = DcSlotMessageSchedule.create(
            network = network,
            slotTimes = slotTimes,
            config = DcSlotMessageConfig(
                type = DcSlotMessageType.BLOCK,
                sizeBytes = DcBlockConfig.DEFAULT_SIZE_BYTES,
                publishOffset = 2.seconds,
                publisherSelection = DcPublisherSelection.VALIDATOR_WEIGHTED
            ),
            randomSeed = 3
        )

        assertThat(schedule.messages).hasSize(20)
        assertThat(schedule.messages.map { it.slotIndex }).isEqualTo((0 until 20).toList())
        schedule.messages.forEach { block ->
            assertThat(network.node(block.publisherNodeId).isValidator)
                .describedAs("proposer of slot %s runs a validator", block.slotIndex)
                .isTrue()
            assertThat(schedule.timeOf(block)).isEqualTo(slotTimes[block.slotIndex] + 2.seconds)
        }
        // 100 of the 110 validators sit on node 0, so it should take the clear majority of slots.
        assertThat(schedule.messages.count { it.publisherNodeId == 0 })
            .describedAs("slots proposed by the 100-validator node")
            .isGreaterThan(10)
        assertThat(schedule.messagesOf(0).map { it.slotIndex })
            .isEqualTo(schedule.messages.filter { it.publisherNodeId == 0 }.map { it.slotIndex })
    }

    @Test
    fun `FFG attestations expand one compact slot cadence for every validator`() {
        val network = DcNetworkBuilder.world(randomSeed = 1, subnetCount = 4)
            .addGroup(count = 3) {
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 2
                peers = 2
                allMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, of = 4)
            }
            .build()
        val slots = DcSlotCadence(count = 3, first = 30.seconds, interval = 12.seconds)
        val config = DcSlotMessageConfig(
            type = DcSlotMessageType.FFG_ATTESTATION,
            sizeBytes = 240,
            publishOffset = 4.seconds,
            publisherSelection = DcPublisherSelection.ALL_VALIDATORS,
            topics = DcSlotMessageTopics.Subnets(4)
        )

        val schedule = DcSlotMessageSchedule.create(network, slots, config, randomSeed = 7)

        assertThat(schedule.slotTimes).isEqualTo(listOf(30.seconds, 42.seconds, 54.seconds))
        assertThat(schedule.messages).hasSize(3 * network.validatorCount)
        assertThat(schedule.messages.groupBy { it.slotIndex }.values.map { it.size }).containsOnly(6)
        assertThat(schedule.messages.groupBy { it.publisherNodeId }.values.map { it.size }).containsOnly(6)
        schedule.messages.forEach { message ->
            assertThat(network.node(message.publisherNodeId).subnetIdsFor(DcSlotMessageType.FFG_ATTESTATION))
                .contains(message.subnetId)
            assertThat(schedule.timeOf(message)).isEqualTo(slots.times[message.slotIndex] + 4.seconds)
        }
    }

    /**
     * A population of two named groups: `pools` holds the bulk of the validators, `home` the tail.
     * Named so a scenario can pick proposers out of one of them.
     */
    private fun namedGroups() = DcNetworkBuilder.world(randomSeed = 1, subnetCount = 2)
        .defaults {
            spreadOverRegions()
            bandwidth = Bandwidths.RESIDENTIAL
            peers = 5
            randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = 1, of = 2)
            // Blob-column meshes are distinct from beacon-attestation meshes.
            allMessageSubnets(DcSlotMessageType.BLOB_COLUMN, of = 2)
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
    fun `a group name lands on every node it produced`() {
        val network = namedGroups()

        assertThat(network.groupNames()).containsExactly("pools", "home")
        assertThat(network.nodesInGroup("pools").map { it.simNodeId }).containsExactly(0, 1)
        assertThat(network.nodesInGroup("home")).hasSize(8)
        assertThat(network.nodesInGroups(null))
            .describedAs("null means the whole network, which is what the default proposer draw uses")
            .hasSize(network.nodeCount)
        assertThat(network.nodesInGroups(setOf("home")).map { it.validatorCount }.distinct())
            .containsExactly(1)
        assertThat(network.summary()).contains("group 'pools': nodes=2 validators=200")
    }

    @Test
    fun `an unnamed group leaves its nodes without a name`() {
        val network = population(nodeCount = 4, subnetCount = 2, peers = 2)

        assertThat(network.groupNames()).isEmpty()
        assertThat(network.nodes.map { it.groupName }).containsOnlyNulls()
    }

    @Test
    fun `a name set in defaults is shared by the groups under it`() {
        val network = DcNetworkBuilder.world(randomSeed = 1, subnetCount = 2)
            .defaults {
                name = "tier-1"
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 1
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = 1, of = 2)
            }
            .addGroup(count = 2)
            .addGroup(count = 3)
            .build()

        assertThat(network.groupNames()).containsExactly("tier-1")
        assertThat(network.nodesInGroup("tier-1"))
            .describedAs("both groups selected together as one pool")
            .hasSize(5)
    }

    @Test
    fun `a blank group name is rejected`() {
        assertThatThrownBy {
            DcNetworkBuilder.world().addGroup(count = 1) {
                name = "  "
                bandwidth = Bandwidths.RESIDENTIAL
            }
        }.hasMessageContaining("name must not be blank")
    }

    @Test
    fun `proposerGroups restricts the draw to the named groups`() {
        val network = namedGroups()
        val slotTimes = DcSlotCadence(count = 30, first = 30.seconds, interval = 12.seconds).times

        fun weightedSchedule(slotTimes: List<Duration>, proposerGroups: Set<String>? = null) =
            DcSlotMessageSchedule.create(
                network = network,
                slotTimes = slotTimes,
                config = DcSlotMessageConfig(
                    type = DcSlotMessageType.BLOCK,
                    sizeBytes = DcBlockConfig.DEFAULT_SIZE_BYTES,
                    publisherGroups = proposerGroups,
                    publisherSelection = DcPublisherSelection.VALIDATOR_WEIGHTED
                ),
                randomSeed = 3
            )

        val homeOnly = weightedSchedule(slotTimes, proposerGroups = setOf("home"))

        // Every proposer from `home`, even though `pools` holds 200 of the 208 validators and would
        // otherwise take almost every slot.
        assertThat(homeOnly.messages.map { it.publisherNodeId }.distinct())
            .allMatch { network.node(it).groupName == "home" }
        assertThat(homeOnly.messages.map { it.publisherNodeId }.distinct())
            .describedAs("all 8 home nodes weigh the same, so 30 slots should reach most of them")
            .hasSizeGreaterThan(4)

        // Left alone, the same draw is dominated by the pools. Over enough slots to make the
        // comparison meaningful: pools hold 96% of the validators, so 30 slots is short enough that
        // an unlucky seed lands well off that share.
        val manySlots = DcSlotCadence(count = 200, first = 30.seconds, interval = 12.seconds).times
        val anyGroup = weightedSchedule(manySlots)
        assertThat(anyGroup.messages.count { network.node(it.publisherNodeId).groupName == "pools" })
            .describedAs("pools hold 200 of 208 validators, so they should take ~96% of 200 slots")
            .isGreaterThan(170)
    }

    @Test
    fun `proposerGroups names a group that does not exist`() {
        val network = namedGroups()

        assertThatThrownBy {
            DcSlotMessageSchedule.create(
                network = network,
                slotTimes = listOf(30.seconds),
                config = DcSlotMessageConfig(
                    type = DcSlotMessageType.BLOCK,
                    sizeBytes = DcBlockConfig.DEFAULT_SIZE_BYTES,
                    publisherGroups = setOf("home", "whales"),
                    publisherSelection = DcPublisherSelection.VALIDATOR_WEIGHTED
                )
            )
        }.hasMessageContaining("names no such group: [whales]")
            .hasMessageContaining("[pools, home]")
    }

    @Test
    fun `proposerGroups naming only groups without validators is rejected`() {
        val network = DcNetworkBuilder.world(randomSeed = 1, subnetCount = 2)
            .defaults {
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                randomMessageSubnets(DcSlotMessageType.FFG_ATTESTATION, count = 1, of = 2)
            }
            .addGroup(count = 2) {
                name = "stakers"
                validators = 4
            }
            .addGroup(count = 4) { name = "relays" }
            .build()

        assertThatThrownBy {
            DcSlotMessageSchedule.create(
                network = network,
                slotTimes = listOf(30.seconds),
                config = DcSlotMessageConfig(
                    type = DcSlotMessageType.BLOCK,
                    sizeBytes = DcBlockConfig.DEFAULT_SIZE_BYTES,
                    publisherGroups = setOf("relays"),
                    publisherSelection = DcPublisherSelection.VALIDATOR_WEIGHTED
                )
            )
        }.hasMessageContaining("No node in [relays] runs a validator")
    }

    @Test
    fun `an empty proposerGroups set is rejected rather than silently meaning all`() {
        assertThatThrownBy { DcBlockConfig(proposerGroups = emptySet()) }
            .hasMessageContaining("must name at least one group")
    }

    @Test
    fun `a run proposes only from the configured groups`() {
        val network = namedGroups()
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcRunConfig(
            slotCount = 2,
            messages = ffgMessages(attestersPerSlot = 4, subnetCount = 2),
            settle = 12.seconds,
            blocks = DcBlockConfig(
                sizeBytes = 64 * 1024,
                publishOffset = 1.seconds,
                proposerGroups = setOf("pools")
            ),
            randomSeed = 7
        )

        val report = DcScenario.run(network, graph, config)
        println(report)

        val blocks = requireNotNull(report.messages[DcSlotMessageType.BLOCK])
        assertThat(blocks.config.publisherGroups).containsExactly("pools")
        assertThat(blocks.publishers.values.flatten().map { network.node(it).groupName }.distinct())
            .containsExactly("pools")
        assertThat(blocks.overall.deliveryRatio).isEqualTo(1.0)
    }

    @Test
    fun `a block payload is the configured size and round-trips its header`() {
        val payload = DcMessagePayload.encode(
            kind = DcMessageKind.BLOCK,
            id = 7,
            slotIndex = 3,
            subnetId = DcMessagePayload.NO_SUBNET,
            publishedAt = 1234.milliseconds,
            sizeBytes = 128 * 1024,
            random = Random(1)
        )

        assertThat(payload).hasSize(128 * 1024)
        val header = requireNotNull(DcMessagePayload.decode(payload))
        assertThat(header.kind).isEqualTo(DcMessageKind.BLOCK)
        assertThat(header.id).isEqualTo(7)
        assertThat(header.slotIndex).isEqualTo(3)
        assertThat(header.subnetId).isEqualTo(DcMessagePayload.NO_SUBNET)
        assertThat(header.publishedAt).isEqualTo(1234.milliseconds)
    }

    @Test
    fun `both kinds are attributed to their slot, and foreign payloads to none`() {
        // The byte counter reads the slot index straight off the wire, so it has to recognise a
        // block as readily as an attestation or the per-slot byte breakdown would silently omit it.
        listOf(DcMessageKind.BLOCK, DcMessageKind.ATTESTATION).forEach { kind ->
            val payload = DcMessagePayload.encode(
                kind = kind,
                id = 1,
                slotIndex = 5,
                subnetId = 0,
                publishedAt = Duration.ZERO,
                sizeBytes = DcMessagePayload.HEADER_BYTES,
                random = Random(1)
            )
            assertThat(DcMessagePayload.slotIndexOf(ByteString.copyFrom(payload)))
                .describedAs("slot index of a %s payload", kind)
                .isEqualTo(5)
        }

        val foreign = ByteString.copyFrom(ByteArray(64) { 0xAB.toByte() })
        assertThat(DcMessagePayload.slotIndexOf(foreign)).isNull()
        assertThat(DcMessagePayload.decode(ByteArray(4))).isNull()
    }
}
