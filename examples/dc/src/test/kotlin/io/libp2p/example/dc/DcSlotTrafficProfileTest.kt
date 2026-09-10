package io.libp2p.example.dc

import com.google.protobuf.ByteString
import io.libp2p.quicsim.runner.DatagramPacketTraceEvent
import io.netty.channel.embedded.EmbeddedChannel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit-level tests of the bucketing itself: [DcSlotProfileParams.bucketOf], [GossipByteCounter]'s
 * wire-level attribution (including unique-vs-duplicate), [DcSlotMessageTopics.typeOf], and
 * [DcSlotTrafficProfile.of]'s aggregation across nodes — plus one end-to-end run confirming the
 * whole path is wired up. [DcAttestationScenarioTest] and [DcBlockScenarioTest] already exercise
 * real runs at scale; what is worth checking in isolation here is where a byte lands and how it is
 * normalized, which a full run would only show indirectly. The per-group breakdown
 * ([DcGroupStats.slotTraffic]) is exercised in [DcGroupStatsTest].
 */
class DcSlotTrafficProfileTest {

    // --- DcSlotProfileParams.bucketOf ------------------------------------------------------------

    @Test
    fun `bucketOf places a time by its offset from the anchor, folded to one slot`() {
        val params = DcSlotProfileParams(anchor = 30.seconds, slotDuration = 12.seconds, bucketDuration = 100.milliseconds)

        assertThat(params.bucketOf(30.seconds)).isEqualTo(0)
        assertThat(params.bucketOf(30.seconds + 250.milliseconds)).isEqualTo(2)
        // One full slot later, the same offset lands in the same bucket.
        assertThat(params.bucketOf(42.seconds + 250.milliseconds)).isEqualTo(2)
        assertThat(params.bucketOf(30.seconds + 11900.milliseconds)).isEqualTo(119)
    }

    @Test
    fun `bucketOf is null before the anchor or within the excluded warm-up waves`() {
        val params = DcSlotProfileParams(
            anchor = 30.seconds,
            slotDuration = 12.seconds,
            bucketDuration = 100.milliseconds,
            warmupWaves = 1
        )

        assertThat(params.bucketOf(29.seconds)).describedAs("before the anchor").isNull()
        assertThat(params.bucketOf(31.seconds)).describedAs("wave 0, which is warm-up here").isNull()
        assertThat(params.bucketOf(43.seconds)).describedAs("wave 1, past warm-up").isEqualTo(10)
    }

    @Test
    fun `rejects a non-positive bucket or slot duration`() {
        assertThatThrownBy {
            DcSlotProfileParams(anchor = Duration.ZERO, slotDuration = 12.seconds, bucketDuration = Duration.ZERO)
        }.hasMessageContaining("bucketDuration must be > 0")
        assertThatThrownBy {
            DcSlotProfileParams(anchor = Duration.ZERO, slotDuration = Duration.ZERO)
        }.hasMessageContaining("slotDuration must be > 0")
    }

    // --- DcSlotMessageTopics.typeOf ---------------------------------------------------------------

    @Test
    fun `typeOf recovers the type from a global or subnet topic, the inverse of topic()`() {
        assertThat(DcSlotMessageTopics.typeOf("/dc/block")).isEqualTo(DcSlotMessageType.BLOCK)
        val ffgSubnetTopic = DcSlotMessageTopics.Subnets(64).topic(DcSlotMessageType.FFG_ATTESTATION, 12).topic
        assertThat(DcSlotMessageTopics.typeOf(ffgSubnetTopic)).isEqualTo(DcSlotMessageType.FFG_ATTESTATION)

        val globalTopic = DcSlotMessageTopics.Global.topic(DcSlotMessageType.BLOCK, null).topic
        assertThat(DcSlotMessageTopics.typeOf(globalTopic)).isEqualTo(DcSlotMessageType.BLOCK)

        val subnetTopic = DcSlotMessageTopics.Subnets(8).topic(DcSlotMessageType.BLOB_COLUMN, 3).topic
        assertThat(DcSlotMessageTopics.typeOf(subnetTopic)).isEqualTo(DcSlotMessageType.BLOB_COLUMN)
    }

    @Test
    fun `typeOf is null for anything not shaped like one of our topics`() {
        assertThat(DcSlotMessageTopics.typeOf("/floodsub/1.0.0")).isNull()
        assertThat(DcSlotMessageTopics.typeOf("/dc/")).isNull()
        assertThat(DcSlotMessageTopics.typeOf("/dc/has a space")).isNull()
    }

    // --- GossipByteCounter wire-level attribution -------------------------------------------------

    /** A real [DcMessagePayload] header, so [DcMessagePayload.idOf] can tell messages apart by id. */
    private fun rpcWithPublish(topic: String, id: Int, dataSize: Int = 100): Rpc.RPC {
        val data = DcMessagePayload.encode(
            kind = DcMessageKind.SLOT_MESSAGE,
            id = id,
            waveIndex = 0,
            subnetId = DcMessagePayload.NO_SUBNET,
            publishedAt = Duration.ZERO,
            sizeBytes = dataSize,
            random = Random(0)
        )
        val message = Rpc.Message.newBuilder().addTopicIDs(topic).setData(ByteString.copyFrom(data)).build()
        return Rpc.RPC.newBuilder().addPublish(message).build()
    }

    @Test
    fun `an inbound publish's first sighting is attributed to its topic's type and the current bucket, as unique`() {
        val params = DcSlotProfileParams(anchor = Duration.ZERO, slotDuration = 12.seconds, bucketDuration = 100.milliseconds)
        val counter = GossipByteCounter(params)
        counter.currentTimeSupplier = { 250.milliseconds }

        counter.readForTest(rpcWithPublish("/dc/block", id = 1, dataSize = 1000))

        val bytes = counter.uniqueMessageBytesReadByTypeAndBucket.getValue(DcSlotMessageType.BLOCK)
        assertThat(bytes[2])
            .describedAs("wire size includes protobuf framing over the raw data size")
            .isGreaterThan(1000L)
        assertThat(bytes.filterIndexed { index, _ -> index != 2 }).allMatch { it == 0L }
        assertThat(counter.duplicateMessageBytesReadByTypeAndBucket).isEmpty()
    }

    @Test
    fun `a repeat sighting of the same message id is counted as duplicate, not unique again`() {
        val params = DcSlotProfileParams(anchor = Duration.ZERO, slotDuration = 12.seconds, bucketDuration = 100.milliseconds)
        val counter = GossipByteCounter(params)
        counter.currentTimeSupplier = { 0.milliseconds }

        repeat(4) { counter.readForTest(rpcWithPublish("/dc/payload", id = 7, dataSize = 100)) }

        val oneMessageSize = counter.publishBytesRead / 4
        val unique = counter.uniqueMessageBytesReadByTypeAndBucket.getValue(DcSlotMessageType.PAYLOAD)[0]
        val duplicate = counter.duplicateMessageBytesReadByTypeAndBucket.getValue(DcSlotMessageType.PAYLOAD)[0]
        assertThat(unique).describedAs("only the first of the 4 reads is unique").isEqualTo(oneMessageSize)
        assertThat(duplicate).describedAs("the other 3 are duplicates of the same size").isEqualTo(oneMessageSize * 3)
        assertThat(unique + duplicate)
            .describedAs("together they are still every byte read, the same total publishBytesRead tracks")
            .isEqualTo(counter.publishBytesRead)
    }

    @Test
    fun `different message ids on the same node are each their own unique sighting`() {
        val params = DcSlotProfileParams(anchor = Duration.ZERO, slotDuration = 12.seconds, bucketDuration = 100.milliseconds)
        val counter = GossipByteCounter(params)
        counter.currentTimeSupplier = { 0.milliseconds }

        counter.readForTest(rpcWithPublish("/dc/payload", id = 1, dataSize = 100))
        counter.readForTest(rpcWithPublish("/dc/payload", id = 2, dataSize = 100))

        assertThat(counter.duplicateMessageBytesReadByTypeAndBucket).isEmpty()
        assertThat(counter.uniqueMessageBytesReadByTypeAndBucket.getValue(DcSlotMessageType.PAYLOAD)[0])
            .isEqualTo(counter.publishBytesRead)
    }

    @Test
    fun `reads before the current time supplier is set are not bucketed`() {
        val params = DcSlotProfileParams(anchor = Duration.ZERO, slotDuration = 12.seconds, bucketDuration = 100.milliseconds)
        val counter = GossipByteCounter(params)
        // currentTimeSupplier left unset.

        counter.readForTest(rpcWithPublish("/dc/block", id = 1, dataSize = 1000))

        assertThat(counter.uniqueMessageBytesReadByTypeAndBucket).isEmpty()
        assertThat(counter.duplicateMessageBytesReadByTypeAndBucket).isEmpty()
        assertThat(counter.publishBytesRead).isGreaterThan(0L).describedAs("the existing whole-run counter is unaffected")
    }

    @Test
    fun `a counter built with no slot profile at all never bucket-attributes`() {
        val counter = GossipByteCounter()
        counter.currentTimeSupplier = { 250.milliseconds }

        counter.readForTest(rpcWithPublish("/dc/block", id = 1, dataSize = 1000))

        assertThat(counter.uniqueMessageBytesReadByTypeAndBucket).isEmpty()
        assertThat(counter.duplicateMessageBytesReadByTypeAndBucket).isEmpty()
        assertThat(counter.controlBytesReadByBucket).isEmpty()
    }

    @Test
    fun `control bytes -- everything that is not a publish field -- are bucketed without needing a type`() {
        val params = DcSlotProfileParams(anchor = Duration.ZERO, slotDuration = 1.seconds, bucketDuration = 100.milliseconds)
        val counter = GossipByteCounter(params)
        counter.currentTimeSupplier = { 350.milliseconds }
        val subscribeOnly = Rpc.RPC.newBuilder()
            .addSubscriptions(Rpc.RPC.SubOpts.newBuilder().setSubscribe(true).setTopicid("/dc/block"))
            .build()

        counter.readForTest(subscribeOnly)

        assertThat(counter.controlBytesReadByBucket[3]).isGreaterThan(0L)
        assertThat(counter.uniqueMessageBytesReadByTypeAndBucket).isEmpty()
        assertThat(counter.duplicateMessageBytesReadByTypeAndBucket).isEmpty()
    }

    // --- DcSlotTrafficProfile.of aggregation -------------------------------------------------------

    private fun params(bucketDuration: Duration = 100.milliseconds, slotDuration: Duration = 1.seconds) =
        DcSlotProfileParams(anchor = Duration.ZERO, slotDuration = slotDuration, bucketDuration = bucketDuration)

    private fun udpEvent(atMs: Int, bytes: Int, direction: DatagramPacketTraceEvent.Direction = DatagramPacketTraceEvent.Direction.INBOUND) =
        DatagramPacketTraceEvent(
            direction = direction,
            nodeId = 0,
            at = atMs.milliseconds,
            localHost = "127.0.0.1",
            localPort = 1000,
            remoteHost = "127.0.0.1",
            remotePort = 2000,
            bytes = bytes,
            payloadSha256 = "deadbeef"
        )

    @Test
    fun `unique message bytes are summed across nodes, then averaged per node per slot`() {
        val p = params()
        val counterA = GossipByteCounter(p).also { it.currentTimeSupplier = { 50.milliseconds } }
        val counterB = GossipByteCounter(p).also { it.currentTimeSupplier = { 50.milliseconds } }
        // Two different nodes, each seeing this message for the first time -- both unique.
        counterA.readForTest(rpcWithPublish("/dc/block", id = 1, dataSize = 100))
        counterB.readForTest(rpcWithPublish("/dc/block", id = 1, dataSize = 100))

        val profile = DcSlotTrafficProfile.of(
            gossipCounters = listOf(counterA, counterB),
            inboundEvents = emptyList(),
            params = p,
            nodeCount = 2,
            slotsMeasured = 1
        )

        // Both nodes read the same-sized message once; total / 2 nodes / 1 slot = each node's own size.
        assertThat(profile.uniqueMessageBytesPerNode.getValue(DcSlotMessageType.BLOCK)[0])
            .isEqualTo(counterA.publishBytesRead)
        assertThat(profile.duplicateMessageBytesPerNode).isEmpty()
    }

    @Test
    fun `a message forwarded twice to the same node contributes to the duplicate column, summed across nodes`() {
        val p = params()
        val counterA = GossipByteCounter(p).also { it.currentTimeSupplier = { 50.milliseconds } }
        // Node A's two mesh peers both forward it the same message -- one unique sighting, one duplicate.
        counterA.readForTest(rpcWithPublish("/dc/block", id = 1, dataSize = 100))
        counterA.readForTest(rpcWithPublish("/dc/block", id = 1, dataSize = 100))

        val profile = DcSlotTrafficProfile.of(
            gossipCounters = listOf(counterA),
            inboundEvents = emptyList(),
            params = p,
            nodeCount = 1,
            slotsMeasured = 1
        )

        val unique = profile.uniqueMessageBytesPerNode.getValue(DcSlotMessageType.BLOCK)[0]
        val duplicate = profile.duplicateMessageBytesPerNode.getValue(DcSlotMessageType.BLOCK)[0]
        assertThat(duplicate).isEqualTo(unique)
    }

    @Test
    fun `transport overhead is inbound UDP bytes beyond what gossip itself accounts for`() {
        val p = params()
        val counter = GossipByteCounter(p).also { it.currentTimeSupplier = { 50.milliseconds } }
        counter.readForTest(rpcWithPublish("/dc/block", id = 1, dataSize = 100))
        val gossipBytes = counter.bytesRead

        val profile = DcSlotTrafficProfile.of(
            gossipCounters = listOf(counter),
            inboundEvents = listOf(udpEvent(atMs = 50, bytes = (gossipBytes + 40).toInt())),
            params = p,
            nodeCount = 1,
            slotsMeasured = 1
        )

        assertThat(profile.transportOverheadBytesPerNode[0]).isEqualTo(40L)
    }

    @Test
    fun `overhead never reads negative when boundary effects put UDP bytes just under gossip's own count`() {
        val p = params()
        val counter = GossipByteCounter(p).also { it.currentTimeSupplier = { 50.milliseconds } }
        counter.readForTest(rpcWithPublish("/dc/block", id = 1, dataSize = 100))

        val profile = DcSlotTrafficProfile.of(
            gossipCounters = listOf(counter),
            inboundEvents = listOf(udpEvent(atMs = 50, bytes = 1)),
            params = p,
            nodeCount = 1,
            slotsMeasured = 1
        )

        assertThat(profile.transportOverheadBytesPerNode[0]).isZero()
    }

    @Test
    fun `outbound UDP events are not counted as inbound overhead`() {
        val p = params()
        val profile = DcSlotTrafficProfile.of(
            gossipCounters = emptyList(),
            inboundEvents = listOf(udpEvent(atMs = 50, bytes = 500, direction = DatagramPacketTraceEvent.Direction.OUTBOUND)),
            params = p,
            nodeCount = 1,
            slotsMeasured = 1
        )

        assertThat(profile.transportOverheadBytesPerNode).allMatch { it == 0L }
    }

    @Test
    fun `a fractional average rounds up rather than truncating`() {
        val p = params()
        // 1 byte of overhead spread over 10 nodes and 1 slot = 0.1 bytes/node/slot, must read as 1.
        val counters = List(10) { GossipByteCounter(p) }
        val profile = DcSlotTrafficProfile.of(
            gossipCounters = counters,
            inboundEvents = listOf(udpEvent(atMs = 50, bytes = 1)),
            params = p,
            nodeCount = 10,
            slotsMeasured = 1
        )

        assertThat(profile.transportOverheadBytesPerNode[0]).isEqualTo(1L)
    }

    @Test
    fun `a type with no traffic simply does not appear as a column`() {
        val p = params()
        val profile = DcSlotTrafficProfile.of(
            gossipCounters = emptyList(),
            inboundEvents = emptyList(),
            params = p,
            nodeCount = 1,
            slotsMeasured = 1
        )

        assertThat(profile.uniqueMessageBytesPerNode).isEmpty()
        assertThat(profile.duplicateMessageBytesPerNode).isEmpty()
        assertThat(profile.controlBytesPerNode).allMatch { it == 0L }
        // control/transport-overhead still print -- only the per-type message columns are omitted.
        assertThat(profile.toString())
            .contains(DcSlotTrafficProfile.CONTROL_COLUMN)
            .doesNotContain(
                DcSlotMessageType.BLOCK.id,
                DcSlotMessageType.PAYLOAD.id,
                DcSlotMessageType.FFG_ATTESTATION.id
            )
    }

    @Test
    fun `the table has one row per bucket and unique plus duplicate columns per type, plus control and overhead`() {
        val p = params(bucketDuration = 100.milliseconds, slotDuration = 500.milliseconds)
        val counter = GossipByteCounter(p).also { it.currentTimeSupplier = { 200.milliseconds } }
        counter.readForTest(rpcWithPublish("/dc/block", id = 1, dataSize = 1000))

        val profile = DcSlotTrafficProfile.of(
            gossipCounters = listOf(counter),
            inboundEvents = listOf(udpEvent(atMs = 200, bytes = 2000)),
            params = p,
            nodeCount = 1,
            slotsMeasured = 1
        )

        val text = profile.toString()
        assertThat(text)
            .contains("block${DcSlotTrafficProfile.UNIQUE_SUFFIX}")
            .contains("block${DcSlotTrafficProfile.DUPLICATE_SUFFIX}")
            .contains(DcSlotTrafficProfile.CONTROL_COLUMN)
            .contains(DcSlotTrafficProfile.TRANSPORT_OVERHEAD_COLUMN)
        assertThat(text.lines().filter { it.isNotBlank() }).hasSize(2 + profile.bucketCount)
    }

    // --- end to end ---------------------------------------------------------------------------------

    @Test
    fun `a full run reports unique, duplicate, control and transport-overhead columns`() {
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
        val config = DcAttestationConfig(
            waveCount = 2,
            waveInterval = 12.seconds,
            settle = 12.seconds,
            blocks = DcBlockConfig(sizeBytes = 8 * 1024, publishOffset = 2.seconds),
            messages = listOf(
                DcSlotMessageConfig(
                    type = DcSlotMessageType.FFG_ATTESTATION,
                    sizeBytes = 240,
                    publisherSelection = DcPublisherSelection.RANDOM_NODES,
                    messagesPerSlot = 4,
                    topics = DcSlotMessageTopics.Subnets(2)
                )
            ),
            randomSeed = 7
        )

        val report = DcAttestationScenario.run(network, graph, config)

        val profile = requireNotNull(report.slotTraffic)
        assertThat(profile.bucketCount).isEqualTo(120)
        assertThat(profile.uniqueMessageBytesPerNode.keys)
            .containsExactlyInAnyOrder(DcSlotMessageType.FFG_ATTESTATION, DcSlotMessageType.BLOCK)
        assertThat(profile.uniqueMessageBytesPerNode.getValue(DcSlotMessageType.BLOCK).sum())
            .describedAs("blocks were actually published and received, so their bucketed unique total is nonzero")
            .isGreaterThan(0L)
        assertThat(profile.duplicateMessageBytesPerNode.getValue(DcSlotMessageType.BLOCK).sum())
            .describedAs("gossip pushes a block to every mesh peer, so most nodes see more than one copy")
            .isGreaterThan(0L)
        assertThat(profile.controlBytesPerNode.sum())
            .describedAs("gossip mesh maintenance produces control traffic over the run")
            .isGreaterThan(0L)
        assertThat(profile.transportOverheadBytesPerNode.sum())
            .describedAs("QUIC/libp2p always carries some bytes beyond the gossip payload")
            .isGreaterThan(0L)
    }

    @Test
    fun `slotTrafficBucketDuration configures the resolution`() {
        val config = DcAttestationConfig(
            waveInterval = 4.seconds,
            slotTrafficBucketDuration = 500.milliseconds,
            messages = listOf(DcSlotMessageConfig(type = DcSlotMessageType.FFG_ATTESTATION, sizeBytes = 240))
        )
        assertThat(config.slotTrafficBucketDuration).isEqualTo(500.milliseconds)
    }

    @Test
    fun `a non-positive slotTrafficBucketDuration is rejected`() {
        assertThatThrownBy { DcAttestationConfig(slotTrafficBucketDuration = Duration.ZERO) }
            .hasMessageContaining("slotTrafficBucketDuration must be > 0")
    }
}

/** Drives [GossipByteCounter.channelRead] through a real (embedded) Netty pipeline. */
private fun GossipByteCounter.readForTest(rpc: Rpc.RPC) {
    val channel = EmbeddedChannel(this)
    channel.writeInbound(rpc)
    channel.finishAndReleaseAll()
}
