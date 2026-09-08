package io.libp2p.example.dc

import io.libp2p.core.crypto.sha256
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.AbstractPubsubMessage
import io.libp2p.pubsub.DEFAULT_PUBSUB_MESSAGE_ID_LENGTH
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipScoreParams
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.quicsim.program.GossipNodeProgram
import io.libp2p.quicsim.sim.NetworkContext
import io.netty.channel.ChannelHandler
import io.libp2p.quicsim.sim.SimContext
import io.libp2p.quicsim.sim.SimNodeId
import io.netty.buffer.Unpooled
import pubsub.pb.Rpc
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A node that subscribes to its assigned attestation subnets and, when the schedule says so,
 * publishes an attestation on one of them.
 *
 * Delivery latency is measured by putting the publisher's timestamp in the payload. Every node's
 * scheduler starts at zero and they are advanced in lockstep, so `timer.elapsedTime()` is a clock
 * shared by all nodes and the subtraction is exact — no clock skew to correct for.
 */
class DcAttestationNodeProgram(
    simNodeId: SimNodeId,
    connectToNodeIds: List<SimNodeId>,
    private val subnetIds: Set<Int>,
    private val schedule: DcAttestationSchedule,
    private val recorder: DcAttestationRecorder,
    private val attestationSizeBytes: Int,
    private val completeAt: Duration,
    params: GossipParams = GossipParams(),
    scoreParams: GossipScoreParams = GossipScoreParams(),
    randomSeed: Long = 0
) : GossipNodeProgram(simNodeId, connectToNodeIds, params, scoreParams, randomSeed) {

    private val random = Random(randomSeed)
    val gossipByteCounter = GossipByteCounter()

    override fun createDebugGossipHandler(): ChannelHandler = gossipByteCounter

    // Ethereum StrictNoSign: message ID = SHA256(data)[0:20], no from/seqno/signature on wire.
    override fun configureGossipRouterBuilder(builder: GossipRouterBuilder) {
        super.configureGossipRouterBuilder(builder)
        builder.messageFactory = { msg: Rpc.Message ->
            object : AbstractPubsubMessage() {
                override val protobufMessage: Rpc.Message = Rpc.Message.newBuilder()
                    .setData(msg.data)
                    .addAllTopicIDs(msg.topicIDsList)
                    .build()
                override val messageId by lazy {
                    sha256(msg.data.toByteArray()).copyOf(DEFAULT_PUBSUB_MESSAGE_ID_LENGTH).toWBytes()
                }
            }
        }
    }

    init {
        require(attestationSizeBytes >= HEADER_BYTES) {
            "attestationSizeBytes must be at least $HEADER_BYTES, got $attestationSizeBytes"
        }
    }

    override fun start(simContext: SimContext, networkContext: NetworkContext): CompletableFuture<Unit> =
        super.start(simContext, networkContext)

    override fun onAllConnected(simContext: SimContext, networkContext: NetworkContext) {
        subscribe(simContext)
        schedulePublications(simContext, networkContext)
        // Every node stops at the same simulated moment, whether or not it attested, so the run ends
        // on the settle deadline instead of when the last publisher happens to finish.
        val delay = (completeAt - simContext.timer.elapsedTime()).coerceAtLeast(Duration.ZERO)
        simContext.scheduler.executeAfterDelay(delay) { completeFuture.complete(Unit) }
    }

    private fun subscribe(simContext: SimContext) {
        if (subnetIds.isEmpty()) return
        val topics = subnetIds.map { DcAttestationTopics.of(it) }.toTypedArray()
        messageApi.subscribe(
            Consumer { msg ->
                val payload = ByteArray(msg.data.readableBytes()).also { msg.data.getBytes(0, it) }
                val header = readHeader(payload) ?: return@Consumer
                recorder.recordDelivered(
                    DcDelivery(
                        attestationId = header.attestationId,
                        waveIndex = header.waveIndex,
                        receiverNodeId = simNodeId,
                        subnetId = header.subnetId,
                        latency = simContext.timer.elapsedTime() - header.publishedAtNanos.nanoseconds
                    )
                )
            },
            *topics
        )
    }

    private fun schedulePublications(simContext: SimContext, networkContext: NetworkContext) {
        val mine = schedule.attestationsOf(simNodeId)
        if (mine.isEmpty()) return
        val publisher = messageApi.createPublisher(privKey = null, seqIdGenerator = { null })
        mine.forEach { attestation ->
            val at = schedule.timeOf(attestation)
            val delay = (at - simContext.timer.elapsedTime()).coerceAtLeast(Duration.ZERO)
            simContext.scheduler.executeAfterDelay(delay) {
                val publishedAt = simContext.timer.elapsedTime()
                recorder.recordPublished(attestation)
                publisher.publish(
                    Unpooled.wrappedBuffer(payloadOf(attestation, publishedAt)),
                    DcAttestationTopics.of(attestation.subnetId)
                )
            }
        }
    }

    /** `[magic][id][wave][subnet][publishedAtNanos]` followed by random bytes up to the wire size. */
    private fun payloadOf(attestation: DcAttestation, publishedAt: Duration): ByteArray {
        val payload = ByteArray(attestationSizeBytes)
        random.nextBytes(payload)
        ByteBuffer.wrap(payload).apply {
            putInt(MAGIC)
            putInt(attestation.id)
            putInt(attestation.waveIndex)
            putInt(attestation.subnetId)
            putLong(publishedAt.inWholeNanoseconds)
        }
        return payload
    }

    private fun readHeader(payload: ByteArray): Header? {
        if (payload.size < HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(payload)
        if (buffer.int != MAGIC) return null
        return Header(
            attestationId = buffer.int,
            waveIndex = buffer.int,
            subnetId = buffer.int,
            publishedAtNanos = buffer.long
        )
    }

    private data class Header(
        val attestationId: Int,
        val waveIndex: Int,
        val subnetId: Int,
        val publishedAtNanos: Long
    )

    companion object {
        private const val MAGIC = 0x0DCA7757.toInt()

        /** magic + id + wave + subnet + timestamp */
        const val HEADER_BYTES: Int = 4 + 4 + 4 + 4 + 8
    }
}
