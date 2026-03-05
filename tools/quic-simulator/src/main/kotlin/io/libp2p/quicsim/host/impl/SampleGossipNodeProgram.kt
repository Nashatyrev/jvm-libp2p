package io.libp2p.quicsim.host.impl

import io.libp2p.core.pubsub.Topic
import io.libp2p.core.pubsub.Subscriber
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipScoreParams
import io.libp2p.quicsim.host.NetworkContext
import io.libp2p.quicsim.host.SimContext
import io.libp2p.quicsim.host.SimNodeId
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.nio.charset.StandardCharsets
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SampleGossipNodeProgram(
    simNodeId: SimNodeId,
    connectToNodeIds: List<SimNodeId>,
    params: GossipParams,
    scoreParams: GossipScoreParams = GossipScoreParams(),
    randomSeed: Long = 0,
    testTopicName: String = "/quicsim/test-topic",
    val messageSizeBytes: Int = 1024,
    val initialPublishDelay: Duration = 1.minutes,
) : GossipNodeProgram(simNodeId, connectToNodeIds, params, scoreParams, randomSeed) {

    private val random = Random(randomSeed)
    private val testTopic = Topic(testTopicName)
    private val receivedNodeIds = ConcurrentHashMap.newKeySet<SimNodeId>()
    @Volatile
    private var expectedNodeIds: Set<Int> = emptySet()
    @Volatile
    private var publishScheduled = false
    @Volatile
    private var publishAttempted = false
    @Volatile
    private var publishSucceeded = false
    @Volatile
    private var lastPublishError: String? = null

    override fun onAllConnected(simContext: SimContext, networkContext: NetworkContext) {
        fun log(msg: String) = println("[${System.currentTimeMillis()}ms] node=$simNodeId $msg")
        expectedNodeIds = networkContext.allNodes.keys - simNodeId
        log("expected senders: $expectedNodeIds")

        messageApi.subscribe(Subscriber { msg ->
            parseSenderNodeId(msg.data)?.let { receivedNodeIds += it }
        }, testTopic)
        log("subscribed to ${testTopic.topic}")

        val publisher = messageApi.createPublisher(networkContext.myHost.privKey)
        publishScheduled = true
        simContext.scheduler.executeAfterDelay(initialPublishDelay) {
            publishAttempted = true
            log("publishing to ${testTopic.topic}")
            publisher.publish(Unpooled.wrappedBuffer(createPayload()), testTopic)
                .whenComplete { _, err ->
                    if (err == null) {
                        publishSucceeded = true
                        lastPublishError = null
                        log("publish succeeded")
                    } else {
                        publishSucceeded = false
                        lastPublishError = err.message
                        log("publish failed: ${err.message}")
                    }
                }
        }
    }

    override fun isComplete(): Boolean =
        expectedNodeIds.isNotEmpty() && receivedNodeIds.containsAll(expectedNodeIds)

    fun debugState(): String {
        val received = receivedNodeIds.toSortedSet().toList()
        val missing = (expectedNodeIds - receivedNodeIds).toSortedSet().toList()
        return "scheduled=$publishScheduled attempted=$publishAttempted " +
            "publishSucceeded=$publishSucceeded lastPublishError=${lastPublishError ?: "-"} " +
            "expected=$expectedNodeIds received=$received missing=$missing complete=${isComplete()}"
    }

    private fun createPayload(): ByteArray {
        val prefix = "sender:$simNodeId\n".toByteArray(StandardCharsets.UTF_8)
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

    private fun parseSenderNodeId(data: ByteBuf): SimNodeId? {
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

        val idBytes = ByteArray(idEnd - idStart)
        data.getBytes(idStart, idBytes)
        return String(idBytes, StandardCharsets.UTF_8).toInt()
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
}
