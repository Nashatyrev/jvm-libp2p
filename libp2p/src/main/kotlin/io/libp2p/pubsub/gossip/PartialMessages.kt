package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.pubsub.Topic
import pubsub.pb.Rpc

/**
 * Application hook for the Gossipsub partial-messages extension.
 *
 * Callbacks run on the router event thread. Implementations must not block it; expensive
 * decoding and validation belongs on an application executor. The payload and metadata are
 * intentionally opaque to the library.
 */
interface PartialMessagesHandler<PeerState> {
    fun onIncomingRpc(
        from: PeerId,
        peerStates: Map<PeerId, PeerState>,
        rpc: Rpc.PartialMessagesExtension
    )

    fun onEmitGossip(
        topic: Topic,
        groupId: ByteArray,
        gossipPeers: Collection<PeerId>,
        peerStates: Map<PeerId, PeerState>
    )
}

/** Selects the data to send, and optionally atomically replaces the application's state for a peer. */
fun interface PublishActionsFn<PeerState> {
    fun decide(
        peerStates: Map<PeerId, PeerState>,
        peerRequestsPartial: (PeerId) -> Boolean
    ): Sequence<Pair<PeerId, PublishAction<PeerState>>>
}

data class PublishAction<PeerState>(
    val partialMessage: ByteArray? = null,
    val partsMetadata: ByteArray? = null,
    val nextPeerState: PeerState? = null,
    val error: Throwable? = null
)

/** Local per-topic capability announcement. `requestsPartial` implies support for sending partials. */
data class PartialTopicOptions(
    val requestsPartial: Boolean = true,
    val supportsSendingPartial: Boolean = requestsPartial
) {
    init {
        require(!requestsPartial || supportsSendingPartial) {
            "A partial-message requester must also support sending partial messages"
        }
    }
}

enum class PartialMessagesFeedbackKind { USEFUL, INVALID, IGNORED }

interface PartialMessagesPeerFeedback {
    fun reportFeedback(topic: Topic, peer: PeerId, kind: PartialMessagesFeedbackKind)
}
