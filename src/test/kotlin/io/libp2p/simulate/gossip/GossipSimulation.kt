package io.libp2p.simulate.gossip

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

data class SimMessage(
    val msgId: Long,
    val sendingPeer: Int,
    val sentTime: Long,
    val pubResult: CompletableFuture<Unit>
)

data class SimMessageDelivery(
    val msgId: Long,
    val receivedPeer: Int,
    val receivedTime: Long
)

class GossipSimulation(
    val cfg: GossipSimConfig,
    val network: GossipSimNetwork
) {

    private val idCounter = AtomicLong(1)
    val publishedMessages = mutableListOf<SimMessage>()

    init {
        forwardTime(cfg.warmUpDelay)
    }

    fun forwardTime(duration: Duration): Long {
        network.timeController.addTime(duration)
        return network.timeController.time
    }

    fun publishMessage(srcPeer: Int): SimMessage {
        return publishMessage(srcPeer, 0)
    }

    fun publishMessage(srcPeer: Int, size: Int): SimMessage {
        val peer = network.peers[srcPeer] ?: throw IllegalArgumentException("Invalid peer index $srcPeer")
        val msgId = idCounter.incrementAndGet()

        val msg = idToMsg(msgId)
        msg.writeBytes(ByteArray(size))
        val future = peer.apiPublisher.publish(msg, cfg.topic)
        val ret = SimMessage(msgId, srcPeer, network.timeController.time, future)
        publishedMessages += ret
        return ret
    }

    fun gatherMessageResults(): Map<SimMessage, List<SimMessageDelivery>> {
        val deliveries = network.peers.flatMap { (peerId, peer) ->
            peer.allMessages.map { SimMessageDelivery(msgToId(it.first.data), peerId, it.second) }
        }.groupBy { it.msgId }

        val list =
            publishedMessages.associateWith { deliveries[it.msgId] ?: emptyList() }

        return list
    }

    private fun idToMsg(msgId: Long) = Unpooled.buffer().writeLong(msgId)
    private fun msgToId(msg: ByteBuf) = msg.slice().readLong()
}