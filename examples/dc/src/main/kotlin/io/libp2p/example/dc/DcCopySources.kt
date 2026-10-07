package io.libp2p.example.dc

import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.pubsub.PubsubMessage
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.pubsub.gossip.GossipRouterEventListener
import java.util.Optional

/**
 * Where each copy of a message a node received came from: a peer in its mesh for that topic at the
 * moment the copy was processed, or a peer outside it -- in an honest network without flood
 * publishing, an answer to one of our IWANTs. Also records the mesh size when the first copy of
 * each message arrived, and how many distinct mesh peers delivered one.
 *
 * Registered as a [GossipRouterEventListener], so it runs on the router's event thread and may read
 * [GossipRouter.mesh] directly. Counts are per slot, read from the message payload.
 */
class DcCopySources(private val router: GossipRouter) : GossipRouterEventListener {

    class SlotCounts {
        var meshCopies = 0L
        var nonMeshCopies = 0L
        var messages = 0L
        var meshSizeAtFirstCopySum = 0L
        var distinctMeshSendersSum = 0L
    }

    private val bySlot = linkedMapOf<Int, SlotCounts>()
    private val meshSendersByMessage = hashMapOf<Any, MutableSet<PeerId>>()

    /** Read once the run is over. */
    fun bySlot(): Map<Int, SlotCounts> = synchronized(this) {
        meshSendersByMessage.forEach { (key, senders) ->
            @Suppress("UNCHECKED_CAST")
            val slot = (key as Pair<Int, *>).first
            bySlot.getOrPut(slot) { SlotCounts() }.distinctMeshSendersSum += senders.size
        }
        meshSendersByMessage.clear()
        bySlot.toMap()
    }

    private fun record(peerId: PeerId, msg: PubsubMessage, first: Boolean) = synchronized(this) {
        val data = msg.protobufMessage.data
        val slot = DcMessagePayload.slotIndexOf(data) ?: return
        val id = DcMessagePayload.idOf(data) ?: return
        val topic: Topic = msg.topics.firstOrNull() ?: return
        val meshPeers = router.mesh[topic].orEmpty()
        val fromMesh = meshPeers.any { it.peerId == peerId }
        val counts = bySlot.getOrPut(slot) { SlotCounts() }
        if (fromMesh) {
            counts.meshCopies++
            meshSendersByMessage.getOrPut(slot to id) { hashSetOf() } += peerId
        } else {
            counts.nonMeshCopies++
        }
        if (first) {
            counts.messages++
            counts.meshSizeAtFirstCopySum += meshPeers.size
        }
    }

    override fun notifyUnseenMessage(peerId: PeerId, msg: PubsubMessage) = record(peerId, msg, first = true)

    override fun notifySeenMessage(peerId: PeerId, msg: PubsubMessage, validationResult: Optional<ValidationResult>) =
        record(peerId, msg, first = false)

    override fun notifyDisconnected(peerId: PeerId) = Unit
    override fun notifyConnected(peerId: PeerId, peerAddress: Multiaddr) = Unit
    override fun notifyUnseenInvalidMessage(peerId: PeerId, msg: PubsubMessage) = Unit
    override fun notifyUnseenValidMessage(peerId: PeerId, msg: PubsubMessage) = Unit
    override fun notifyMeshed(peerId: PeerId, topic: Topic) = Unit
    override fun notifyPruned(peerId: PeerId, topic: Topic) = Unit
    override fun notifyRouterMisbehavior(peerId: PeerId, count: Int) = Unit
}
