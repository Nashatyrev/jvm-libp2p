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
 * Block issuance: one block per wave, of a configured size, published a configured offset into the
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
                randomSubnets(count = 2, of = subnetCount)
            }
            .build()

    @Test
    fun `every node but the proposer receives each block, at the configured offset`() {
        val network = population(nodeCount = 20, subnetCount = 4, peers = 8)
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcAttestationConfig(
            waveCount = 2,
            attestersPerWave = 4,
            attestationSizeBytes = 240,
            waveInterval = 12.seconds,
            settle = 12.seconds,
            blocks = DcBlockConfig(sizeBytes = 128 * 1024, publishOffset = 2.seconds),
            randomSeed = 7
        )

        val report = DcAttestationScenario.run(network, graph, config)
        println(report)

        val blocks = requireNotNull(report.blocks) { "blocks were configured, so a block report is due" }
        assertThat(blocks.overall.publishedCount)
            .describedAs("one block per wave")
            .isEqualTo(config.waveCount)
        assertThat(blocks.overall.deliveryRatio)
            .describedAs("block delivery ratio; percentiles are meaningless if blocks went missing")
            .isEqualTo(1.0)
        assertThat(blocks.overall.expectedDeliveries)
            .describedAs("the block topic is global, so every node but the proposer expects it")
            .isEqualTo(config.waveCount * (network.nodeCount - 1))

        // The offset is the point of the knob, so check the proposers really published that far into
        // their slot rather than on its boundary.
        config.waveTimes.forEachIndexed { wave, waveTime ->
            assertThat(blocks.publishTimes[wave])
                .describedAs("publish time of the block of wave %s", wave)
                .isEqualTo(waveTime + 2.seconds)
        }

        // ... and the size lands on the wire: each node receives every block at least once, so its
        // inbound publish bytes cannot be less than the blocks alone.
        val blockBytesPerNode = config.waveCount.toLong() * config.blocks!!.sizeBytes
        assertThat(report.gossipPublishBytesReceived / network.nodeCount)
            .describedAs("publish bytes received per node, which includes the blocks")
            .isGreaterThanOrEqualTo(blockBytesPerNode)
    }

    @Test
    fun `a zero offset publishes the block on the slot boundary`() {
        val network = population(nodeCount = 16, subnetCount = 4, peers = 6)
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcAttestationConfig(
            waveCount = 1,
            attestersPerWave = 4,
            settle = 12.seconds,
            blocks = DcBlockConfig(sizeBytes = 64 * 1024),
            randomSeed = 7
        )

        val report = DcAttestationScenario.run(network, graph, config)

        assertThat(config.blocks!!.publishOffset).isEqualTo(Duration.ZERO)
        assertThat(report.blocks!!.publishTimes[0]).isEqualTo(config.waveTimes[0])
        assertThat(report.blocks!!.overall.deliveryRatio).isEqualTo(1.0)
    }

    @Test
    fun `a run without a block config issues no blocks and reports none`() {
        val network = population(nodeCount = 16, subnetCount = 4, peers = 6)
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 5)
        val config = DcAttestationConfig(waveCount = 1, attestersPerWave = 4, randomSeed = 7)

        val report = DcAttestationScenario.run(network, graph, config)

        assertThat(config.blocks).isNull()
        assertThat(report.blocks).isNull()
        assertThat(report.overall.deliveryRatio).isEqualTo(1.0)
    }

    @Test
    fun `the settle window starts from the block rather than from the slot boundary`() {
        val base = DcAttestationConfig(waveCount = 2, waveInterval = 12.seconds, settle = 12.seconds)
        val withBlocks = base.copy(blocks = DcBlockConfig(publishOffset = 2.seconds))

        // Without the extra offset the last block would only get 10s of the 12s settle window, and
        // anything slower than that would be reported as an undelivered block.
        assertThat(withBlocks.completeAt).isEqualTo(base.completeAt + 2.seconds)
    }

    @Test
    fun `a block published later than a whole slot is rejected`() {
        assertThatThrownBy {
            DcAttestationConfig(
                waveInterval = 12.seconds,
                blocks = DcBlockConfig(publishOffset = 12.seconds)
            )
        }.hasMessageContaining("publishOffset must be less than")
    }

    @Test
    fun `a block too large for gossipsub to carry is rejected`() {
        // The default maxGossipMessageSize is exactly 1 MiB, so a 1 MiB block cannot fit: gossipsub
        // reserves a 1% margin when splitting RPCs and the frame decoder drops anything above the
        // limit. Failing here beats a run in which no block ever arrives.
        assertThatThrownBy { DcAttestationConfig(blocks = DcBlockConfig(sizeBytes = 1 shl 20)) }
            .hasMessageContaining("exceeds what gossipsub will carry")

        val roomier = GossipParams.builder().maxGossipMessageSize(4 shl 20).build()
        val config = DcAttestationConfig(
            blocks = DcBlockConfig(sizeBytes = 1 shl 20),
            gossipParams = roomier
        )
        assertThat(config.blocks!!.sizeBytes).isEqualTo(1 shl 20)
    }

    @Test
    fun `one validator-weighted proposer per wave, publishing at the wave time plus the offset`() {
        val network = DcNetworkBuilder.world(randomSeed = 1, subnetCount = 4)
            // A node with 100 validators alongside 10 with one each: it should propose most waves.
            .addGroup(count = 1) {
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 100
                peers = 4
                randomSubnets(count = 1, of = 4)
            }
            .addGroup(count = 10) {
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 1
                peers = 4
                randomSubnets(count = 1, of = 4)
            }
            .build()
        val waveTimes = DcAttestationSchedule.waveTimes(count = 20, first = 30.seconds, interval = 12.seconds)

        val schedule = DcBlockSchedule.validatorWeighted(
            network = network,
            waveTimes = waveTimes,
            publishOffset = 2.seconds,
            randomSeed = 3
        )

        assertThat(schedule.blocks).hasSize(20)
        assertThat(schedule.blocks.map { it.waveIndex }).isEqualTo((0 until 20).toList())
        schedule.blocks.forEach { block ->
            assertThat(network.node(block.proposerNodeId).isValidator)
                .describedAs("proposer of wave %s runs a validator", block.waveIndex)
                .isTrue()
            assertThat(schedule.timeOf(block)).isEqualTo(waveTimes[block.waveIndex] + 2.seconds)
        }
        // 100 of the 110 validators sit on node 0, so it should take the clear majority of waves.
        assertThat(schedule.blocks.count { it.proposerNodeId == 0 })
            .describedAs("waves proposed by the 100-validator node")
            .isGreaterThan(10)
        assertThat(schedule.blocksOf(0).map { it.waveIndex })
            .isEqualTo(schedule.blocks.filter { it.proposerNodeId == 0 }.map { it.waveIndex })
    }

    @Test
    fun `a block payload is the configured size and round-trips its header`() {
        val payload = DcMessagePayload.encode(
            kind = DcMessageKind.BLOCK,
            id = 7,
            waveIndex = 3,
            subnetId = DcMessagePayload.NO_SUBNET,
            publishedAt = 1234.milliseconds,
            sizeBytes = 128 * 1024,
            random = Random(1)
        )

        assertThat(payload).hasSize(128 * 1024)
        val header = requireNotNull(DcMessagePayload.decode(payload))
        assertThat(header.kind).isEqualTo(DcMessageKind.BLOCK)
        assertThat(header.id).isEqualTo(7)
        assertThat(header.waveIndex).isEqualTo(3)
        assertThat(header.subnetId).isEqualTo(DcMessagePayload.NO_SUBNET)
        assertThat(header.publishedAt).isEqualTo(1234.milliseconds)
    }

    @Test
    fun `both kinds are attributed to their wave, and foreign payloads to none`() {
        // The byte counter reads the wave index straight off the wire, so it has to recognise a
        // block as readily as an attestation or the per-wave byte breakdown would silently omit it.
        listOf(DcMessageKind.BLOCK, DcMessageKind.ATTESTATION).forEach { kind ->
            val payload = DcMessagePayload.encode(
                kind = kind,
                id = 1,
                waveIndex = 5,
                subnetId = 0,
                publishedAt = Duration.ZERO,
                sizeBytes = DcMessagePayload.HEADER_BYTES,
                random = Random(1)
            )
            assertThat(DcMessagePayload.waveIndexOf(ByteString.copyFrom(payload)))
                .describedAs("wave index of a %s payload", kind)
                .isEqualTo(5)
        }

        val foreign = ByteString.copyFrom(ByteArray(64) { 0xAB.toByte() })
        assertThat(DcMessagePayload.waveIndexOf(foreign)).isNull()
        assertThat(DcMessagePayload.decode(ByteArray(4))).isNull()
    }
}
