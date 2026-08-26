package io.libp2p.pubsub.gossip

import com.google.protobuf.ByteString
import io.libp2p.core.PeerId
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.Topic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PartialMessagesTest : GossipTestsBase() {

    private val topic: Topic = "partial-topic"

    @Test
    fun `delivers a negotiated incoming partial rpc to the application handler`() {
        val received = CompletableFuture<Rpc.PartialMessagesExtension>()
        val handler = object : PartialMessagesHandler<Unit> {
            override fun onIncomingRpc(
                from: PeerId,
                peerStates: Map<PeerId, Unit>,
                rpc: Rpc.PartialMessagesExtension
            ) {
                received.complete(rpc)
            }

            override fun onEmitGossip(
                topic: Topic,
                groupId: ByteArray,
                gossipPeers: Collection<PeerId>,
                peerStates: Map<PeerId, Unit>
            ) = Unit
        }
        val test = partialTest(handler)
        enableAndSubscribe(test)
        announcePartialSubscription(test)

        test.mockRouter.sendToSingle(
            Rpc.RPC.newBuilder().setPartial(
                Rpc.PartialMessagesExtension.newBuilder()
                    .setTopicID(topic)
                    .setGroupID(ByteString.copyFromUtf8("block-1"))
                    .setPartialMessage(ByteString.copyFromUtf8("cell"))
                    .setPartsMetadata(ByteString.copyFromUtf8("bitmap"))
            ).build()
        )

        val rpc = received.get(2, TimeUnit.SECONDS)
        assertThat(rpc.topicID).isEqualTo(topic)
        assertThat(rpc.partialMessage.toStringUtf8()).isEqualTo("cell")
        assertThat(rpc.partsMetadata.toStringUtf8()).isEqualTo("bitmap")
    }

    @Test
    fun `queues partial payload only for peers which request partial messages`() {
        val test = partialTest(noopHandler)
        enableAndSubscribe(test)
        announcePartialSubscription(test)

        test.gossipRouter.publishPartial(
            topic,
            "block-2".toByteArray(),
            PublishActionsFn<Unit> { _, _ ->
                sequenceOf(test.router2.peerId to PublishAction(partialMessage = "cell".toByteArray(), partsMetadata = "bitmap".toByteArray()))
            }
        ).get(2, TimeUnit.SECONDS)

        val rpc = test.mockRouter.waitForMessage { it.hasPartial() }.partial
        assertThat(rpc.partialMessage.toStringUtf8()).isEqualTo("cell")
        assertThat(rpc.partsMetadata.toStringUtf8()).isEqualTo("bitmap")
    }

    @Test
    fun `supports-only peer receives metadata but not eager partial payload`() {
        val test = partialTest(noopHandler)
        enableAndSubscribe(test)
        announcePartialSubscription(test, requestsPartial = false, supportsSendingPartial = true)

        test.gossipRouter.publishPartial(
            topic,
            "block-3".toByteArray(),
            PublishActionsFn<Unit> { _, _ ->
                sequenceOf(test.router2.peerId to PublishAction(partialMessage = "cell".toByteArray(), partsMetadata = "bitmap".toByteArray()))
            }
        ).get(2, TimeUnit.SECONDS)

        val rpc = test.mockRouter.waitForMessage { it.hasPartial() }.partial
        assertThat(rpc.hasPartialMessage()).isFalse()
        assertThat(rpc.partsMetadata.toStringUtf8()).isEqualTo("bitmap")
    }

    private fun partialTest(handler: PartialMessagesHandler<*>): TwoRoutersTest =
        TwoRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_3,
            enabledGossipExtensions = listOf(GossipExtension.PARTIAL_MESSAGES),
            partialMessagesHandler = handler
        )

    private fun enableAndSubscribe(test: TwoRoutersTest) {
        test.gossipRouter.enablePartialMessagesForTopic(topic).get(2, TimeUnit.SECONDS)
        test.gossipRouter.subscribe(topic)
        test.mockRouter.waitForMessage { rpc -> rpc.subscriptionsList.any { it.topicid == topic && it.subscribe } }
    }

    private fun announcePartialSubscription(
        test: TwoRoutersTest,
        requestsPartial: Boolean = true,
        supportsSendingPartial: Boolean = true
    ) {
        test.mockRouter.sendToSingle(
            Rpc.RPC.newBuilder()
                .setControl(Rpc.ControlMessage.newBuilder().setExtensions(
                    Rpc.ControlExtensions.newBuilder().setPartialMessages(true)
                ))
                .addSubscriptions(
                    Rpc.RPC.SubOpts.newBuilder()
                        .setTopicid(topic)
                        .setSubscribe(true)
                        .setRequestsPartial(requestsPartial)
                        .setSupportsSendingPartial(supportsSendingPartial)
                )
                .build()
        )
    }

    private val noopHandler = object : PartialMessagesHandler<Unit> {
        override fun onIncomingRpc(from: PeerId, peerStates: Map<PeerId, Unit>, rpc: Rpc.PartialMessagesExtension) = Unit
        override fun onEmitGossip(topic: Topic, groupId: ByteArray, gossipPeers: Collection<PeerId>, peerStates: Map<PeerId, Unit>) = Unit
    }
}
