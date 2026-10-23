package io.libp2p.pubsub.gossip

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.multistream.ProtocolId
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.core.pubsub.PubsubApi
import io.libp2p.etc.util.MAIN_LANE
import io.libp2p.pubsub.PubsubApiImpl
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.transport.quic.QuicStream
import io.netty.channel.ChannelHandler
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class Gossip @JvmOverloads constructor(
    private val router: GossipRouter = GossipRouterBuilder().build(),
    private val api: PubsubApi = PubsubApiImpl(router),
    private val debugGossipHandler: ChannelHandler? = null
) :
    ProtocolBinding<Unit>, ConnectionHandler, PubsubApi by api {

    private val logger = LoggerFactory.getLogger(Gossip::class.java)

    fun updateTopicScoreParams(scoreParams: Map<String, GossipTopicScoreParams>) {
        router.score.updateTopicParams(scoreParams)
    }

    fun getGossipScore(peerId: PeerId): Double {
        return router.score.getCachedScore(peerId)
    }

    /** Configure a topic before subscribing to receive its data as partial messages. */
    fun enablePartialMessagesForTopic(
        topic: String,
        options: PartialTopicOptions = PartialTopicOptions()
    ): CompletableFuture<Unit> = router.enablePartialMessagesForTopic(topic, options)

    fun <PeerState> publishPartial(
        topic: String,
        groupId: ByteArray,
        actions: PublishActionsFn<PeerState>
    ): CompletableFuture<Unit> = router.publishPartial(topic, groupId, actions)

    fun reportPartialMessagesFeedback(topic: String, peerId: PeerId, kind: PartialMessagesFeedbackKind) {
        router.reportFeedback(topic, peerId, kind)
    }

    private val announceProtocols =
        when (router.protocol) {
            PubsubProtocol.Gossip_V_1_3 -> {
                listOf(
                    PubsubProtocol.Gossip_V_1_3.announceStr,
                    PubsubProtocol.Gossip_V_1_2.announceStr,
                    PubsubProtocol.Gossip_V_1_1.announceStr,
                    PubsubProtocol.Gossip_V_1_0.announceStr
                )
            }
            PubsubProtocol.Gossip_V_1_2 -> {
                listOf(
                    PubsubProtocol.Gossip_V_1_2.announceStr,
                    PubsubProtocol.Gossip_V_1_1.announceStr,
                    PubsubProtocol.Gossip_V_1_0.announceStr
                )
            }
            PubsubProtocol.Gossip_V_1_1 -> {
                listOf(
                    PubsubProtocol.Gossip_V_1_1.announceStr,
                    PubsubProtocol.Gossip_V_1_0.announceStr
                )
            }
            else -> {
                listOf(PubsubProtocol.Gossip_V_1_0.announceStr)
            }
        }

    // Lane streams are accepted whether or not this side opens lanes of its own
    override val protocolDescriptor = ProtocolDescriptor(
        announceProtocols,
        object : ProtocolMatcher {
            override fun matches(proposed: ProtocolId) = proposed in announceProtocols || laneOfProtocol(proposed) != null
        }
    )

    override fun handleConnection(conn: Connection) {
        val mainStream = conn.muxerSession().createStream(listOf(this))
        val lanes = router.streamLanes ?: return
        // Opened once the main stream is up, so the remote already has the peer to add them to
        mainStream.controller.thenRun {
            (MAIN_LANE + 1 until lanes.count).forEach { lane ->
                conn.muxerSession().createStream(listOf(toInitiator(listOf(laneProtocol(lane)))))
                    .controller.whenComplete { _, err ->
                        if (err != null) logger.debug("Gossip lane {} stream to {} failed: {}", lane, conn.secureSession().remoteId, err.toString())
                    }
            }
        }
    }

    override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out Unit> {
        logger.trace("Gossip initChannel - selected protocol: {}", selectedProtocol)
        val stream = ch as Stream
        val lane = laneOfProtocol(selectedProtocol) ?: MAIN_LANE
        if (stream.isInitiator) {
            router.streamLanes?.priorities?.getOrNull(lane)?.let { (stream as? QuicStream)?.setPriority(it.urgency, it.incremental) }
        }
        if (lane == MAIN_LANE) {
            router.addPeerWithDebugHandler(stream, debugGossipHandler)
        } else {
            router.addLaneStream(stream, lane, debugGossipHandler)
        }
        return CompletableFuture.completedFuture(Unit)
    }

    companion object {
        /** Protocol of the additional streams of [GossipStreamLanes], followed by the lane number */
        const val LANE_PROTOCOL_PREFIX = "/meshsub-lane/1.0.0/"

        fun laneProtocol(lane: Int): ProtocolId = "$LANE_PROTOCOL_PREFIX$lane"

        fun laneOfProtocol(protocol: ProtocolId): Int? =
            if (protocol.startsWith(LANE_PROTOCOL_PREFIX)) {
                protocol.removePrefix(LANE_PROTOCOL_PREFIX).toIntOrNull()?.takeIf { it > MAIN_LANE }
            } else {
                null
            }
    }
}
