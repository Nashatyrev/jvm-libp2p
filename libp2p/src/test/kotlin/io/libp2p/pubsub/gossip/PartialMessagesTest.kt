package io.libp2p.pubsub.gossip

import com.google.protobuf.ByteString
import io.libp2p.core.PeerId
import io.libp2p.etc.types.seconds
import io.libp2p.pubsub.NoPeersForOutboundMessageException
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.Topic
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import io.netty.handler.logging.LogLevel
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

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

    @Test
    fun `subscription announces partial request and support flags`() {
        val test = partialTest(noopHandler)
        val subscriptionRpc = enableAndSubscribe(test)

        val subscription = subscriptionRpc.subscriptionsList.first { it.topicid == topic }
        assertThat(subscription.requestsPartial).isTrue()
        assertThat(subscription.supportsSendingPartial).isTrue()
    }

    @Test
    fun `incoming partial is ignored before peer extension negotiation`() {
        val received = CompletableFuture<Rpc.PartialMessagesExtension>()
        val handler = recordingHandler(received)
        val test = partialTest(handler)
        enableAndSubscribe(test)

        test.mockRouter.sendToSingle(partialRpc("unnegotiated", partial = "cell"))

        assertThat(received.isDone).isFalse()
    }

    @ParameterizedTest
    @MethodSource("incomingWireVariants")
    fun `all partial rpc payload metadata combinations reach the handler`(
        partial: String?,
        metadata: String?
    ) {
        val received = CompletableFuture<Rpc.PartialMessagesExtension>()
        val test = partialTest(recordingHandler(received))
        enableAndSubscribe(test)
        announcePartialSubscription(test)

        test.mockRouter.sendToSingle(partialRpc("wire-variant", partial, metadata))

        val rpc = received.get(2, TimeUnit.SECONDS)
        assertThat(rpc.hasPartialMessage()).isEqualTo(partial != null)
        assertThat(rpc.hasPartsMetadata()).isEqualTo(metadata != null)
    }

    @Test
    fun `requests partial implies support even when support flag is omitted`() {
        val test = partialTest(noopHandler)
        enableAndSubscribe(test)
        announcePartialSubscription(test, requestsPartial = true, supportsSendingPartial = false)

        test.gossipRouter.publishPartial(
            topic,
            "block-request".toByteArray(),
            PublishActionsFn<Unit> { _, _ ->
                sequenceOf(test.router2.peerId to PublishAction(partialMessage = "cell".toByteArray()))
            }
        ).get(2, TimeUnit.SECONDS)

        assertThat(test.mockRouter.waitForMessage { it.hasPartial() }.partial.partialMessage.toStringUtf8()).isEqualTo("cell")
    }

    @Test
    fun `publishing preserves application peer state between decisions`() {
        val test = partialTest(noopHandler)
        enableAndSubscribe(test)
        announcePartialSubscription(test)

        test.gossipRouter.publishPartial(
            topic,
            "block-state".toByteArray(),
            PublishActionsFn<String> { _, _ ->
                sequenceOf(test.router2.peerId to PublishAction(partsMetadata = "first".toByteArray(), nextPeerState = "known"))
            }
        ).get(2, TimeUnit.SECONDS)
        test.mockRouter.waitForMessage { it.hasPartial() }

        var observed: Map<PeerId, String>? = null
        test.gossipRouter.publishPartial(
            topic,
            "block-state".toByteArray(),
            PublishActionsFn<String> { peerStates, _ ->
                observed = peerStates
                sequenceOf(test.router2.peerId to PublishAction(partsMetadata = "second".toByteArray()))
            }
        ).get(2, TimeUnit.SECONDS)

        assertThat(observed).containsEntry(test.router2.peerId, "known")
    }

    @Test
    fun `publish action error fails without emitting a partial rpc`() {
        val test = partialTest(noopHandler)
        enableAndSubscribe(test)
        announcePartialSubscription(test)

        val future = test.gossipRouter.publishPartial(
            topic,
            "block-error".toByteArray(),
            PublishActionsFn<Unit> { _, _ ->
                sequenceOf(test.router2.peerId to PublishAction(error = IllegalStateException("nope")))
            }
        )

        assertThatThrownBy { future.get(2, TimeUnit.SECONDS) }
            .hasCauseInstanceOf(IllegalStateException::class.java)
            .hasRootCauseMessage("nope")
    }

    @Test
    fun `full publish and IDONTWANT are suppressed for partial requesting peer`() {
        val test = partialTest(
            noopHandler,
            GossipParams(D = 1, DLow = 1, DHigh = 1, iDontWantMinMessageSizeThreshold = 1)
        )
        enableAndSubscribe(test)
        announcePartialSubscription(test)
        test.fuzz.timeController.addTime(2.seconds)

        val future = test.gossipRouter.publish(newMessage(topic, 1, "full-message".toByteArray()))
        assertThatThrownBy { future.get(2, TimeUnit.SECONDS) }.hasRootCauseInstanceOf(NoPeersForOutboundMessageException::class.java)
        assertThat(test.mockRouter.inboundMessages.flatMap { it.publishList }).isEmpty()
        assertThat(test.mockRouter.inboundMessages.flatMap { it.control.idontwantList }).isEmpty()
    }

    @Test
    fun `supports-only peer continues to receive full messages and IDONTWANT`() {
        val test = partialTest(
            noopHandler,
            GossipParams(D = 1, DLow = 1, DHigh = 1, iDontWantMinMessageSizeThreshold = 1)
        )
        enableAndSubscribe(test)
        announcePartialSubscription(test, requestsPartial = false, supportsSendingPartial = true)
        test.fuzz.timeController.addTime(2.seconds)

        val msg = newMessage(topic, 2, "full-message".toByteArray())
        test.gossipRouter.publish(msg).get(2, TimeUnit.SECONDS)

        assertThat(
            test.mockRouter.waitForMessage { it.control.idontwantCount > 0 }
                .control.idontwantList.flatMap { it.messageIDsList }
        ).contains(ByteString.copyFrom(msg.messageId.array))
        assertThat(test.mockRouter.waitForMessage { it.publishList.contains(msg.protobufMessage) }.publishList)
            .contains(msg.protobufMessage)
    }

    @Test
    fun `unsubscribe clears local partial topic configuration`() {
        val test = partialTest(noopHandler)
        enableAndSubscribe(test)
        announcePartialSubscription(test)

        test.gossipRouter.unsubscribe(topic)
        test.gossipRouter.enablePartialMessagesForTopic(
            topic,
            PartialTopicOptions(requestsPartial = false, supportsSendingPartial = true)
        ).get(2, TimeUnit.SECONDS)
        test.gossipRouter.subscribe(topic)

        val resubscription = test.mockRouter.waitForMessage {
            it.subscriptionsList.any { subscription -> subscription.topicid == topic && subscription.subscribe && subscription.supportsSendingPartial }
        }.subscriptionsList.last { it.topicid == topic && it.subscribe }
        assertThat(resubscription.requestsPartial).isFalse()
        assertThat(resubscription.supportsSendingPartial).isTrue()
    }

    @Test
    fun `invalid feedback penalizes the connected peer`() {
        val test = partialTest(
            noopHandler,
            scoreParams = GossipScoreParams(
                peerScoreParams = GossipPeerScoreParams(behaviourPenaltyWeight = -1.0, behaviourPenaltyThreshold = 0.0)
            )
        )
        enableAndSubscribe(test)
        val peerId = test.router2.peerId
        val initialScore = test.gossipRouter.score.score(peerId)

        test.gossipRouter.reportFeedback(topic, peerId, PartialMessagesFeedbackKind.INVALID)
        test.gossipRouter.submitOnEventThread { Unit }.get(2, TimeUnit.SECONDS)

        assertThat(test.gossipRouter.score.score(peerId)).isLessThan(initialScore)
    }

    @Test
    fun `peer initiated group limits are enforced and expire after TTL`() {
        val received = AtomicInteger()
        val handler = object : PartialMessagesHandler<Unit> {
            override fun onIncomingRpc(from: PeerId, peerStates: Map<PeerId, Unit>, rpc: Rpc.PartialMessagesExtension) {
                received.incrementAndGet()
            }
            override fun onEmitGossip(topic: Topic, groupId: ByteArray, gossipPeers: Collection<PeerId>, peerStates: Map<PeerId, Unit>) = Unit
        }
        val test = partialTest(handler)
        enableAndSubscribe(test)
        announcePartialSubscription(test)

        repeat(9) { index -> test.mockRouter.sendToSingle(partialRpc("group-$index", metadata = "meta")) }
        test.gossipRouter.submitOnEventThread { received.get() }.get(2, TimeUnit.SECONDS)
        assertThat(received.get()).isEqualTo(8)

        test.fuzz.timeController.addTime(5.seconds)
        test.mockRouter.sendToSingle(partialRpc("after-ttl", metadata = "meta"))
        test.gossipRouter.submitOnEventThread { received.get() }.get(2, TimeUnit.SECONDS)
        assertThat(received.get()).isEqualTo(9)
    }

    @Test
    fun `peer disconnect releases its peer initiated group budget`() {
        val received = AtomicInteger()
        val handler = object : PartialMessagesHandler<Unit> {
            override fun onIncomingRpc(from: PeerId, peerStates: Map<PeerId, Unit>, rpc: Rpc.PartialMessagesExtension) {
                received.incrementAndGet()
            }
            override fun onEmitGossip(topic: Topic, groupId: ByteArray, gossipPeers: Collection<PeerId>, peerStates: Map<PeerId, Unit>) = Unit
        }
        val test = partialTest(handler)
        enableAndSubscribe(test)
        announcePartialSubscription(test)
        repeat(8) { index -> test.mockRouter.sendToSingle(partialRpc("before-disconnect-$index", metadata = "meta")) }
        test.gossipRouter.submitOnEventThread { received.get() }.get(2, TimeUnit.SECONDS)
        assertThat(received.get()).isEqualTo(8)

        test.connection.disconnect()
        test.router1.connectSemiDuplex(test.router2, pubsubLogs = LogLevel.ERROR)
        announcePartialSubscription(test)
        test.mockRouter.sendToSingle(partialRpc("after-disconnect", metadata = "meta"))
        test.gossipRouter.submitOnEventThread { received.get() }.get(2, TimeUnit.SECONDS)

        assertThat(received.get()).isEqualTo(9)
    }

    @Test
    fun `partial only groups are emitted to lazy gossip peers`() {
        val emitted = CompletableFuture<Collection<PeerId>>()
        val handler = object : PartialMessagesHandler<Unit> {
            override fun onIncomingRpc(from: PeerId, peerStates: Map<PeerId, Unit>, rpc: Rpc.PartialMessagesExtension) = Unit
            override fun onEmitGossip(topic: Topic, groupId: ByteArray, gossipPeers: Collection<PeerId>, peerStates: Map<PeerId, Unit>) {
                emitted.complete(gossipPeers)
            }
        }
        val test = ManyRoutersTest(
            mockRouterCount = 2,
            protocol = PubsubProtocol.Gossip_V_1_3,
            params = GossipParams(D = 1, DLow = 1, DHigh = 1, DLazy = 1, gossipFactor = 0.0),
            enabledGossipExtensions = listOf(GossipExtension.PARTIAL_MESSAGES),
            partialMessagesHandler = handler
        )
        test.connectAll()
        test.gossipRouter.enablePartialMessagesForTopic(topic).get(2, TimeUnit.SECONDS)
        test.gossipRouter.subscribe(topic)
        test.mockRouters.forEach { mock ->
            mock.sendToSingle(partialSubscriptionRpc())
        }
        test.fuzz.timeController.addTime(2.seconds)

        test.gossipRouter.publishPartial(
            topic,
            "lazy-group".toByteArray(),
            PublishActionsFn<Unit> { _, _ -> emptySequence() }
        ).get(2, TimeUnit.SECONDS)
        test.fuzz.timeController.addTime(2.seconds)

        assertThat(emitted.get(2, TimeUnit.SECONDS)).isNotEmpty()
    }

    private fun partialTest(
        handler: PartialMessagesHandler<*>,
        params: GossipParams = GossipParams(),
        scoreParams: GossipScoreParams = GossipScoreParams()
    ): TwoRoutersTest =
        TwoRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_3,
            coreParams = params,
            scoreParams = scoreParams,
            enabledGossipExtensions = listOf(GossipExtension.PARTIAL_MESSAGES),
            partialMessagesHandler = handler
        )

    private fun enableAndSubscribe(test: TwoRoutersTest): Rpc.RPC {
        test.gossipRouter.enablePartialMessagesForTopic(topic).get(2, TimeUnit.SECONDS)
        test.gossipRouter.subscribe(topic)
        return test.mockRouter.waitForMessage { rpc -> rpc.subscriptionsList.any { it.topicid == topic && it.subscribe } }
    }

    private fun announcePartialSubscription(
        test: TwoRoutersTest,
        requestsPartial: Boolean = true,
        supportsSendingPartial: Boolean = true
    ) {
        test.mockRouter.sendToSingle(partialSubscriptionRpc(requestsPartial, supportsSendingPartial))
    }

    private fun partialSubscriptionRpc(
        requestsPartial: Boolean = true,
        supportsSendingPartial: Boolean = true
    ): Rpc.RPC =
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

    private fun partialRpc(group: String, partial: String? = null, metadata: String? = null): Rpc.RPC =
        Rpc.RPC.newBuilder().setPartial(
            Rpc.PartialMessagesExtension.newBuilder()
                .setTopicID(topic)
                .setGroupID(ByteString.copyFromUtf8(group))
                .apply {
                    partial?.let { setPartialMessage(ByteString.copyFromUtf8(it)) }
                    metadata?.let { setPartsMetadata(ByteString.copyFromUtf8(it)) }
                }
        ).build()

    private fun recordingHandler(received: CompletableFuture<Rpc.PartialMessagesExtension>) = object : PartialMessagesHandler<Unit> {
        override fun onIncomingRpc(from: PeerId, peerStates: Map<PeerId, Unit>, rpc: Rpc.PartialMessagesExtension) {
            received.complete(rpc)
        }
        override fun onEmitGossip(topic: Topic, groupId: ByteArray, gossipPeers: Collection<PeerId>, peerStates: Map<PeerId, Unit>) = Unit
    }

    private val noopHandler = object : PartialMessagesHandler<Unit> {
        override fun onIncomingRpc(from: PeerId, peerStates: Map<PeerId, Unit>, rpc: Rpc.PartialMessagesExtension) = Unit
        override fun onEmitGossip(topic: Topic, groupId: ByteArray, gossipPeers: Collection<PeerId>, peerStates: Map<PeerId, Unit>) = Unit
    }

    companion object {
        @JvmStatic
        fun incomingWireVariants(): Stream<Arguments> = Stream.of(
            Arguments.of("payload", "metadata"),
            Arguments.of("payload", null),
            Arguments.of(null, "metadata"),
            Arguments.of(null, null)
        )
    }
}
