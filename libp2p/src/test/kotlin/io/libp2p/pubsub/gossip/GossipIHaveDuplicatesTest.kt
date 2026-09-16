package io.libp2p.pubsub.gossip

import io.libp2p.etc.types.seconds
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.DefaultPubsubMessage
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.MockRouter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

/**
 * Verifies that the router never advertises the same message id to the same peer more than once.
 *
 * Gossip emission is probabilistic: on every heartbeat a fresh random set of non-mesh peers is
 * picked and the whole [GossipParams.gossipSize] mcache window is offered to them. Without
 * bookkeeping a peer picked on several heartbeats in a row receives the very same ids again and
 * again, which wastes bandwidth and burns the receiver's per-heartbeat IHAVE budget.
 */
class GossipIHaveDuplicatesTest : GossipTestsBase() {

    private fun MockRouter.advertisedMessageIds(): List<MessageId> =
        inboundMessages
            .filter { it.hasControl() }
            .flatMap { it.control.ihaveList }
            .flatMap { it.messageIDsList }
            .map { it.toWBytes() }

    private fun gossipParams(dLazy: Int) = GossipParams(
        D = 3,
        DLow = 3,
        DHigh = 3,
        DOut = 0,
        DLazy = dLazy,
        floodPublishMaxMessageSizeThreshold = NEVER_FLOOD_PUBLISH
    )

    @Test
    fun `should not advertise the same message id to a peer on subsequent heartbeats`() {
        val params = gossipParams(dLazy = 100)
        val test = ManyRoutersTest(mockRouterCount = 8, params = params)

        test.gossipRouter.subscribe("topic1")
        test.routers.forEach { it.router.subscribe("topic1") }
        test.connectAll()
        test.fuzz.timeController.addTime(2.seconds)
        test.mockRouters.forEach { it.inboundMessages.clear() }

        test.gossipRouter.publish(newMessage("topic1", 0L, "Hello-0".toByteArray()))

        // keep the message in the mcache gossip window for its whole lifetime
        repeat(params.gossipSize + 1) {
            test.fuzz.timeController.addTime(params.heartbeatInterval)
        }

        // every peer was gossiped to on each heartbeat, but must see the id at most once
        test.mockRouters.forEach { mockRouter ->
            assertThat(mockRouter.advertisedMessageIds()).doesNotHaveDuplicates()
        }
    }

    @Test
    fun `should still gossip newly published messages to already gossiped peers`() {
        val params = gossipParams(dLazy = 100)
        val test = ManyRoutersTest(mockRouterCount = 8, params = params)

        test.gossipRouter.subscribe("topic1")
        test.routers.forEach { it.router.subscribe("topic1") }
        test.connectAll()
        test.fuzz.timeController.addTime(2.seconds)
        test.mockRouters.forEach { it.inboundMessages.clear() }

        val messageIds = (0L until 3L).map { seqNo ->
            val msg = newMessage("topic1", seqNo, "Hello-$seqNo".toByteArray())
            test.gossipRouter.publish(msg)
            test.fuzz.timeController.addTime(params.heartbeatInterval)
            getMessageId(msg.protobufMessage)
        }

        // non-mesh peers (DLazy covers all of them) should learn about every message exactly once
        val gossipedPeers = test.mockRouters.filter { it.advertisedMessageIds().isNotEmpty() }
        assertThat(gossipedPeers).isNotEmpty()
        gossipedPeers.forEach { mockRouter ->
            assertThat(mockRouter.advertisedMessageIds())
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(messageIds)
        }
    }

    @Test
    fun `should not advertise the same message id twice within a single heartbeat`() {
        val params = gossipParams(dLazy = 100)
        val test = ManyRoutersTest(mockRouterCount = 8, params = params)

        listOf("topic1", "topic2").forEach { topic ->
            test.gossipRouter.subscribe(topic)
            test.routers.forEach { it.router.subscribe(topic) }
        }
        test.connectAll()
        test.fuzz.timeController.addTime(2.seconds)
        test.mockRouters.forEach { it.inboundMessages.clear() }

        // a single message belonging to two topics is held by the mcache under both of them
        val twoTopicMessage = Rpc.Message.newBuilder(newProtoMessage("topic1", 0L, "Hello-0".toByteArray()))
            .addTopicIDs("topic2")
            .build()
        test.gossipRouter.publish(DefaultPubsubMessage(twoTopicMessage))

        test.fuzz.timeController.addTime(params.heartbeatInterval)

        test.mockRouters.forEach { mockRouter ->
            assertThat(mockRouter.advertisedMessageIds()).doesNotHaveDuplicates()
        }
    }
}
