package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.pubsub.DefaultPubsubMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MCacheTest : GossipTestsBase() {

    private val gossipSize = 3
    private val historyLength = 5

    private fun newCache() = MCache(gossipSize, historyLength)

    private fun message(seqNo: Long) =
        DefaultPubsubMessage(newProtoMessage("topic1", seqNo, "Hello-$seqNo".toByteArray()))

    @Test
    fun `gossiped peers are tracked per message`() {
        val cache = newCache()
        val msg = message(0L)
        val peer1 = PeerId.random()
        val peer2 = PeerId.random()
        cache += msg

        assertThat(cache.wasGossipedTo(peer1, msg.messageId)).isFalse()

        cache.markGossipedTo(peer1, listOf(msg.messageId))

        assertThat(cache.wasGossipedTo(peer1, msg.messageId)).isTrue()
        assertThat(cache.wasGossipedTo(peer2, msg.messageId)).isFalse()
    }

    @Test
    fun `gossiped peers survive while the message stays in the gossip window`() {
        val cache = newCache()
        val msg = message(0L)
        val peer = PeerId.random()
        cache += msg
        cache.markGossipedTo(peer, listOf(msg.messageId))

        repeat(gossipSize - 1) {
            cache.shift()
            assertThat(cache.getMessageIds("topic1")).contains(msg.messageId)
            assertThat(cache.wasGossipedTo(peer, msg.messageId)).isTrue()
        }
    }

    @Test
    fun `gossiped peers are dropped together with the message`() {
        val cache = newCache()
        val msg = message(0L)
        val peer = PeerId.random()
        cache += msg
        cache.markGossipedTo(peer, listOf(msg.messageId))

        repeat(historyLength) { cache.shift() }

        assertThat(cache[msg.messageId]).isNull()
        assertThat(cache.wasGossipedTo(peer, msg.messageId)).isFalse()
    }

    @Test
    fun `marking an uncached message id is ignored`() {
        val cache = newCache()
        val unknownMessageId = message(42L).messageId
        val peer = PeerId.random()

        cache.markGossipedTo(peer, listOf(unknownMessageId))

        assertThat(cache.wasGossipedTo(peer, unknownMessageId)).isFalse()
    }
}
