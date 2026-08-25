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
import io.netty.channel.ChannelHandler
import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
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
    /** Number of independently published chunks that make up one logical message wave. */
    val messagesPerWave: Int = 1,
    val initialPublishDelay: Duration = 1.minutes,
    val publishInterval: Duration = Duration.ZERO,
    val completeAfter: Duration? = null,
    private val eventSink: QuicScenarioEventSink = QuicScenarioEventSink.Noop,
    debugGossipHandler: ChannelHandler? = null,
) : GossipNodeProgram(simNodeId, connectToNodeIds, params, scoreParams, randomSeed, debugGossipHandler) {
    init {
        require(messagesPerPublisher > 0) { "messagesPerPublisher must be positive" }
        require(messagesPerWave > 0) { "messagesPerWave must be positive" }
        require(messagesPerPublisher % messagesPerWave == 0) {
            "messagesPerPublisher must be divisible by messagesPerWave"
        }
    }

    var log: (String) -> Unit = { println("[SampleGossipNodeProgram] $it") }
    private val verboseLog = System.getProperty("quicsim.sampleGossip.log", "true").toBoolean()

    private val random = Random(randomSeed)
    private val testTopic = Topic(testTopicName)
    private val receivedMessageCount = AtomicInteger()
    private val successfulPublishCount = AtomicInteger()
    private val routerConnectEvents = AtomicInteger()
    private val routerDisconnectEvents = AtomicInteger()
    private val routerMeshEvents = AtomicInteger()
    private val routerPruneEvents = AtomicInteger()
    private val routerDisconnectEventTimes = ConcurrentLinkedQueue<Duration>()
    @Volatile
    private var expectedMessageCount = 0
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
        expectedMessageCount = (networkContext.allNodes.keys.count { it < publishersCount && it != simNodeId }) *
            messagesPerPublisher
        log("[$simNodeId] expected message count: $expectedMessageCount")
        installRouterEventLogger(simContext)

        messageApi.subscribe(Consumer { msg ->
            parsePublisherNodeId(msg.data)?.let { publisherNodeId ->
                if (publisherNodeId in 0 until publishersCount && publisherNodeId != simNodeId) {
                    val messageIndex = parseMessageIndex(msg.data) ?: 0
                    receivedMessageCount.incrementAndGet()
                    completeIfReady()
                    eventSink.record(
                        QuicScenarioEvent.GossipMessageReceived(
                            nodeId = simNodeId,
                            at = simContext.timer.time() - epoch,
                            publisherNodeId = publisherNodeId,
                            messageIndex = messageIndex
                        )
                    )
                }
            }
        }, testTopic)
        log("[$simNodeId] subscribed to ${testTopic.topic}")
        completeIfReady()
        completeAfter?.let { completeAt ->
            val elapsedSinceStart = simContext.timer.time() - epoch
            val completeDelay = (completeAt - elapsedSinceStart).coerceAtLeast(Duration.ZERO)
            simContext.scheduler.executeAfterDelay(completeDelay) {
                completeFuture.complete(Unit)
            }
        }

        val publisher = messageApi.createPublisher(networkContext.myHost.privKey)
        publishScheduled = true
        if (simNodeId < publishersCount) {
            for (messageIndex in 0 until messagesPerPublisher) {
                val elapsedSinceStart = simContext.timer.time() - epoch
                val waveIndex = messageIndex / messagesPerWave
                val publishAt = initialPublishDelay + publishInterval * waveIndex
                val publishDelay = (publishAt - elapsedSinceStart).coerceAtLeast(Duration.ZERO)
                simContext.scheduler.executeAfterDelay(publishDelay) {
                    log("[$simNodeId] publishing message=$messageIndex to ${testTopic.topic}")
                    publishAttempted = true
                    eventSink.record(
                        QuicScenarioEvent.GossipMessagePublished(
                            nodeId = simNodeId,
                            at = simContext.timer.time() - epoch,
                            messageIndex = messageIndex
                        )
                    )
                    publisher.publish(Unpooled.wrappedBuffer(createPayload(messageIndex)), testTopic)
                        .whenComplete { _, err ->
                            if (err == null) {
                                successfulPublishCount.incrementAndGet()
                                publishSucceeded = true
                                lastPublishError = null
                                log("[$simNodeId] publish message=$messageIndex succeeded")
                                completeIfReady()
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
        receivedMessageCount.get() >= expectedMessageCount &&
            (simNodeId >= publishersCount || successfulPublishCount.get() >= messagesPerPublisher)

    private fun completeIfReady() {
        if (isComplete()) {
            completeFuture.complete(Unit)
        }
    }

    fun debugState(): String {
        val received = receivedMessageCount.get()
        val meshPeers = gossipRouter.mesh[testTopic.topic]
            ?.map { it.peerId.toBase58().take(12) }
            ?.sorted()
            ?: emptyList()
        val fanoutPeers = gossipRouter.fanout[testTopic.topic]
            ?.map { it.peerId.toBase58().take(12) }
            ?.sorted()
            ?: emptyList()
        return "scheduled=$publishScheduled attempted=$publishAttempted " +
            "publishSucceeded=$publishSucceeded successfulPublishCount=${successfulPublishCount.get()} " +
            "lastPublishError=${lastPublishError ?: "-"} " +
            "routerDiagnostics=${routerDiagnosticSummary()} " +
            "meshPeers=$meshPeers fanoutPeers=$fanoutPeers " +
            "expectedCount=$expectedMessageCount receivedCount=$received " +
            "missingCount=${(expectedMessageCount - received).coerceAtLeast(0)} complete=${completeFuture.isDone}"
    }

    fun routerDiagnostics(): RouterDiagnostics =
        RouterDiagnostics(
            connectEvents = routerConnectEvents.get(),
            disconnectEvents = routerDisconnectEvents.get(),
            meshEvents = routerMeshEvents.get(),
            pruneEvents = routerPruneEvents.get(),
            disconnectEventTimes = routerDisconnectEventTimes.toList()
        )

    private fun routerDiagnosticSummary(): String =
        "connectEvents=${routerConnectEvents.get()} " +
            "disconnectEvents=${routerDisconnectEvents.get()} " +
            "meshEvents=${routerMeshEvents.get()} " +
            "pruneEvents=${routerPruneEvents.get()}"

    private fun installRouterEventLogger(simContext: SimContext) {
        if (eventsListenerInstalled) return
        eventsListenerInstalled = true
        gossipRouter.eventBroadcaster.listeners += object : GossipRouterEventListener {
            override fun notifyDisconnected(peerId: PeerId) {
                routerDisconnectEvents.incrementAndGet()
                routerDisconnectEventTimes += simContext.timer.time() - epoch
                log("[$simNodeId] router disconnected peer=${peerId.toBase58().take(12)}")
            }

            override fun notifyConnected(peerId: PeerId, peerAddress: Multiaddr) {
                routerConnectEvents.incrementAndGet()
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
                    routerMeshEvents.incrementAndGet()
                    log("[$simNodeId] router MESHED peer=${peerId.toBase58().take(12)} topic=$topic")
                }
            }

            override fun notifyPruned(peerId: PeerId, topic: String) {
                if (topic == testTopic.topic) {
                    routerPruneEvents.incrementAndGet()
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

    private fun parsePublisherNodeId(data: ByteBuf): SimNodeId? {
        val readerIndex = data.readerIndex()
        val readableBytes = data.readableBytes()
        val prefix = SENDER_PREFIX_BYTES
        if (readableBytes < prefix.size + 1) return null

        for (i in prefix.indices) {
            if (data.getByte(readerIndex + i) != prefix[i]) return null
        }

        val idStart = readerIndex + prefix.size
        val idEnd = findSenderIdEndIndex(data, idStart, readerIndex + readableBytes)
        if (idEnd <= idStart) return null

        var publisherNodeId = 0
        for (i in idStart until idEnd) {
            val digit = data.getByte(i) - '0'.code
            if (digit !in 0..9) return null
            publisherNodeId = publisherNodeId * 10 + digit
        }
        return publisherNodeId
    }

    private fun findSenderIdEndIndex(data: ByteBuf, start: Int, endExclusive: Int): Int {
        for (i in start until endExclusive) {
            val byte = data.getByte(i)
            if (byte == MESSAGE_INDEX_SEPARATOR_BYTE || byte == NEW_LINE_BYTE) return i
        }
        return -1
    }

    private fun parseMessageIndex(data: ByteBuf): Int? {
        val readerIndex = data.readerIndex()
        val readableBytes = data.readableBytes()
        val prefix = SENDER_PREFIX_BYTES
        if (readableBytes < prefix.size + 3) return null

        val idStart = readerIndex + prefix.size
        val idEnd = findSenderIdEndIndex(data, idStart, readerIndex + readableBytes)
        if (idEnd <= idStart) return null

        val messageIndexStart = idEnd + 1
        val messageIndexEnd = findMessageIndexEndIndex(data, messageIndexStart, readerIndex + readableBytes)
        if (messageIndexEnd <= messageIndexStart) return null

        var messageIndex = 0
        for (i in messageIndexStart until messageIndexEnd) {
            val digit = data.getByte(i) - '0'.code
            if (digit !in 0..9) return null
            messageIndex = messageIndex * 10 + digit
        }
        return messageIndex
    }

    private fun findMessageIndexEndIndex(data: ByteBuf, start: Int, endExclusive: Int): Int {
        for (i in start until endExclusive) {
            if (data.getByte(i) == NEW_LINE_BYTE) return i
        }
        return -1
    }

    companion object {
        private const val NEW_LINE_BYTE: Byte = '\n'.code.toByte()
        private const val MESSAGE_INDEX_SEPARATOR_BYTE: Byte = ':'.code.toByte()
        private val SENDER_PREFIX_BYTES = "sender:".toByteArray(StandardCharsets.UTF_8)
    }

    data class RouterDiagnostics(
        val connectEvents: Int,
        val disconnectEvents: Int,
        val meshEvents: Int,
        val pruneEvents: Int,
        val disconnectEventTimes: List<Duration>
    )
}
