package io.libp2p.quicsim.program

import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.pubsub.Topic
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.pubsub.PubsubMessage
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipRouterEventListener
import io.libp2p.pubsub.gossip.GossipScoreParams
import io.libp2p.quicsim.SimLogger
import io.libp2p.quicsim.core.schedule.TimePoint
import io.libp2p.quicsim.scenario.QuicScenarioEvent
import io.libp2p.quicsim.scenario.QuicScenarioEventSink
import io.libp2p.quicsim.sim.NetworkContext
import io.libp2p.quicsim.sim.SimContext
import io.libp2p.quicsim.sim.SimNodeId
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class SampleGossipNodeProgram(
    simNodeId: SimNodeId,
    connectToNodeIds: List<SimNodeId>,
    val publishersCount: Int,
    params: GossipParams,
    scoreParams: GossipScoreParams = GossipScoreParams(),
    randomSeed: Long = 0,
    testTopicName: String = "/quicsim/test-topic",
    val messageSizeBytes: Int = 1024,
    val messagesPerPublisher: Int = 1,
    val initialPublishDelay: Duration = 1.minutes,
    private val eventSink: QuicScenarioEventSink = QuicScenarioEventSink.Noop,
) : GossipNodeProgram(simNodeId, connectToNodeIds, params, scoreParams, randomSeed) {
    var log: (String) -> Unit = { println("[SampleGossipNodeProgram] $it") }
    private val verboseLog = System.getProperty("quicsim.sampleGossip.log", "true").toBoolean()

    private val random = Random(randomSeed)
    private val testTopic = Topic(testTopicName)
    private val receivedMessageIds = ConcurrentHashMap.newKeySet<MessageId>()
    @Volatile
    private var expectedMessageIds: Set<MessageId> = emptySet()
    @Volatile
    private var publishScheduled = false
    @Volatile
    private var publishAttempted = false
    @Volatile
    private var publishSucceeded = false
    @Volatile
    private var lastPublishError: String? = null
    @Volatile
    private var eventsListenerInstalled = false
    private lateinit var epoch: TimePoint

    override fun createProtocols(context: SimContext): List<ProtocolBinding<*>> {
        val simLogger = SimLogger(context.timer)
        log = if (verboseLog) simLogger::log else { _ -> }
        return super.createProtocols(context)
    }

    override fun start(simContext: SimContext, networkContext: NetworkContext): CompletableFuture<Unit> {
        epoch = simContext.timer.time()
        return super.start(simContext, networkContext)
    }

    override fun onAllConnected(simContext: SimContext, networkContext: NetworkContext) {
        expectedMessageIds = (networkContext.allNodes.keys.filter { it < publishersCount }.toSet() - simNodeId)
            .flatMap { publisherNodeId ->
                (0 until messagesPerPublisher).map { messageIndex ->
                    MessageId(publisherNodeId, messageIndex)
                }
            }
            .toSet()
        log("[$simNodeId] expected messages: $expectedMessageIds")
        installRouterEventLogger()

        messageApi.subscribe(Consumer { msg ->
            parseSenderMessageId(msg.data)?.let { messageId ->
                receivedMessageIds += messageId
                completeIfReady()
                eventSink.record(
                    QuicScenarioEvent.GossipMessageReceived(
                        nodeId = simNodeId,
                        at = simContext.timer.time() - epoch,
                        publisherNodeId = messageId.publisherNodeId
                    )
                )
            }
        }, testTopic)
        log("[$simNodeId] subscribed to ${testTopic.topic}")
        completeIfReady()

        val publisher = messageApi.createPublisher(networkContext.myHost.privKey)
        publishScheduled = true
        if (simNodeId < publishersCount) {
            val elapsedSinceStart = simContext.timer.time() - epoch
            val publishDelay = (initialPublishDelay - elapsedSinceStart).coerceAtLeast(Duration.ZERO)
            simContext.scheduler.executeAfterDelay(publishDelay) {
                publishAttempted = true
                for (messageIndex in 0 until messagesPerPublisher) {
                    log("[$simNodeId] publishing message=$messageIndex to ${testTopic.topic}")
                    eventSink.record(
                        QuicScenarioEvent.GossipMessagePublished(
                            nodeId = simNodeId,
                            at = simContext.timer.time() - epoch
                        )
                    )
                    publisher.publish(Unpooled.wrappedBuffer(createPayload(messageIndex)), testTopic)
                        .whenComplete { _, err ->
                            if (err == null) {
                                publishSucceeded = true
                                lastPublishError = null
                                log("[$simNodeId] publish message=$messageIndex succeeded")
                            } else {
                                publishSucceeded = false
                                lastPublishError = err.message
                                log("[$simNodeId] publish message=$messageIndex failed: ${err.message}")
                            }
                        }
                }
            }
        }
    }

    private fun isComplete(): Boolean =
        expectedMessageIds.isNotEmpty() && receivedMessageIds.containsAll(expectedMessageIds)

    private fun completeIfReady() {
        if (isComplete()) {
            completeFuture.complete(Unit)
        }
    }

    fun debugState(): String {
        val received = receivedMessageIds.toList().sorted()
        val missing = (expectedMessageIds - receivedMessageIds).sorted()
        val meshPeers = gossipRouter.mesh[testTopic.topic]
            ?.map { it.peerId.toBase58().take(12) }
            ?.sorted()
            ?: emptyList()
        val fanoutPeers = gossipRouter.fanout[testTopic.topic]
            ?.map { it.peerId.toBase58().take(12) }
            ?.sorted()
            ?: emptyList()
        return "scheduled=$publishScheduled attempted=$publishAttempted " +
            "publishSucceeded=$publishSucceeded lastPublishError=${lastPublishError ?: "-"} " +
            "meshPeers=$meshPeers fanoutPeers=$fanoutPeers " +
            "expected=$expectedMessageIds received=$received missing=$missing complete=${completeFuture.isDone}"
    }

    private fun installRouterEventLogger() {
        if (eventsListenerInstalled) return
        eventsListenerInstalled = true
        gossipRouter.eventBroadcaster.listeners += object : GossipRouterEventListener {
            override fun notifyDisconnected(peerId: PeerId) {
                log("[$simNodeId] router disconnected peer=${peerId.toBase58().take(12)}")
            }

            override fun notifyConnected(peerId: PeerId, peerAddress: Multiaddr) {
                log("[$simNodeId] router connected peer=${peerId.toBase58().take(12)} addr=$peerAddress")
            }

            override fun notifyUnseenMessage(peerId: PeerId, msg: PubsubMessage) {
//                log("[$simNodeId] router unseen from=${peerId.toBase58().take(12)} msgId=${msg.messageId} topics=${msg.topics}")
            }

            override fun notifySeenMessage(
                peerId: PeerId,
                msg: PubsubMessage,
                validationResult: Optional<ValidationResult>
            ) {
//                log("[$simNodeId] router seen from=${peerId.toBase58().take(12)} msgId=${msg.messageId} result=$validationResult")
            }

            override fun notifyUnseenInvalidMessage(peerId: PeerId, msg: PubsubMessage) {
                log("[$simNodeId] router unseen INVALID from=${peerId.toBase58().take(12)} msgId=${msg.messageId}")
            }

            override fun notifyUnseenValidMessage(peerId: PeerId, msg: PubsubMessage) {
//                log("[$simNodeId] router unseen VALID from=${peerId.toBase58().take(12)} msgId=${msg.messageId} topics=${msg.topics}")
            }

            override fun notifyMeshed(peerId: PeerId, topic: String) {
                if (topic == testTopic.topic) {
                    log("[$simNodeId] router MESHED peer=${peerId.toBase58().take(12)} topic=$topic")
                }
            }

            override fun notifyPruned(peerId: PeerId, topic: String) {
                if (topic == testTopic.topic) {
                    log("[$simNodeId] router PRUNED peer=${peerId.toBase58().take(12)} topic=$topic")
                }
            }

            override fun notifyRouterMisbehavior(peerId: PeerId, count: Int) {
                log("[$simNodeId] router MISBEHAVIOR peer=${peerId.toBase58().take(12)} count=$count")
            }
        }
        log("[$simNodeId] router event listener installed")
    }

    private fun createPayload(messageIndex: Int): ByteArray {
        val prefix = "sender:$simNodeId:$messageIndex\n".toByteArray(StandardCharsets.UTF_8)
        require(messageSizeBytes >= prefix.size) {
            "messageSizeBytes=$messageSizeBytes is too small, should be at least ${prefix.size} bytes"
        }

        return ByteArray(messageSizeBytes).also { payload ->
            System.arraycopy(prefix, 0, payload, 0, prefix.size)
            val randomBody = ByteArray(messageSizeBytes - prefix.size)
            random.nextBytes(randomBody)
            System.arraycopy(randomBody, 0, payload, prefix.size, randomBody.size)
        }
    }

    private fun parseSenderMessageId(data: ByteBuf): MessageId? {
        val readerIndex = data.readerIndex()
        val readableBytes = data.readableBytes()
        val prefix = SENDER_PREFIX_BYTES
        if (readableBytes < prefix.size + 1) return null

        for (i in prefix.indices) {
            if (data.getByte(readerIndex + i) != prefix[i]) return null
        }

        val idStart = readerIndex + prefix.size
        val idEnd = findNewlineIndex(data, idStart, readerIndex + readableBytes)
        if (idEnd <= idStart) return null

        val idAndIndexBytes = ByteArray(idEnd - idStart)
        data.getBytes(idStart, idAndIndexBytes)
        val idAndIndex = String(idAndIndexBytes, StandardCharsets.UTF_8).split(':', limit = 2)
        val publisherNodeId = idAndIndex[0].toInt()
        val messageIndex = idAndIndex.getOrNull(1)?.toInt() ?: 0
        return MessageId(publisherNodeId, messageIndex)
    }

    private fun findNewlineIndex(data: ByteBuf, start: Int, endExclusive: Int): Int {
        for (i in start until endExclusive) {
            if (data.getByte(i) == NEW_LINE_BYTE) return i
        }
        return -1
    }

    companion object {
        private const val NEW_LINE_BYTE: Byte = '\n'.code.toByte()
        private val SENDER_PREFIX_BYTES = "sender:".toByteArray(StandardCharsets.UTF_8)
    }

    private data class MessageId(
        val publisherNodeId: SimNodeId,
        val messageIndex: Int
    ) : Comparable<MessageId> {
        override fun compareTo(other: MessageId): Int =
            compareValuesBy(this, other, MessageId::publisherNodeId, MessageId::messageIndex)
    }
}
