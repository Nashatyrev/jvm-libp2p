package io.libp2p.quicsim.program

import io.libp2p.core.pubsub.PubsubPublisherApi
import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipScoreParams
import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.core.schedule.TimePoint
import io.libp2p.quicsim.scenario.QuicScenarioEvent
import io.libp2p.quicsim.scenario.QuicScenarioEventSink
import io.libp2p.quicsim.sim.NetworkContext
import io.libp2p.quicsim.sim.SimContext
import io.libp2p.quicsim.sim.SimNodeId
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.nio.charset.StandardCharsets
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Simulates dissemination of erasure-coded symbols for one logical message.
 *
 * Every node can deterministically reconstruct every symbol payload. Once a non-publisher has
 * received [recoveryThreshold] distinct symbols, it treats all [symbolCount] symbols as available
 * and republishes the symbols it has not physically received. Payloads and the topic are identical
 * for a given symbol irrespective of which node publishes it, so the default gossipsub message ID
 * is also identical. Consequently, normal gossipsub IDONTWANT handling applies to both received
 * symbols and recovered symbols republished by this program.
 */
class ErasureCodedGossipNodeProgram(
    simNodeId: SimNodeId,
    connectToNodeIds: List<SimNodeId>,
    params: GossipParams,
    scoreParams: GossipScoreParams = GossipScoreParams(),
    randomSeed: Long = 0,
    private val publisherNodeId: SimNodeId = 0,
    private val symbolCount: Int = 128,
    private val recoveryThreshold: Int = 64,
    private val symbolSizeBytes: Int = 8 * 1024,
    private val waveCount: Int = 1,
    private val topic: Topic = Topic("/quicsim/erasure-coded-symbols"),
    private val initialPublishDelay: Duration = 10.seconds,
    private val publishInterval: Duration = Duration.ZERO,
    private val eventSink: QuicScenarioEventSink = QuicScenarioEventSink.Noop,
) : GossipNodeProgram(simNodeId, connectToNodeIds, params, scoreParams, randomSeed) {
    init {
        require(symbolCount > 0) { "symbolCount must be positive" }
        require(recoveryThreshold in 1..symbolCount) {
            "recoveryThreshold must be in [1, $symbolCount]"
        }
        require(symbolSizeBytes >= SYMBOL_PREFIX_BYTES.size + 2) {
            "symbolSizeBytes must fit a symbol header"
        }
        require(waveCount > 0) { "waveCount must be positive" }
        require(publishInterval >= Duration.ZERO) { "publishInterval must not be negative" }
    }

    private val random = Random(randomSeed)
    private data class WaveState(
        val receivedSymbols: MutableSet<Int> = mutableSetOf(),
        val recovered: AtomicBoolean = AtomicBoolean(false),
        val recoveryPublicationFinished: AtomicBoolean = AtomicBoolean(false),
        @Volatile var recoveryAt: Duration? = null
    )

    private val waveStates = List(waveCount) { WaveState() }
    private val publisherFinishedWaves = List(waveCount) { AtomicBoolean(false) }
    private lateinit var epoch: TimePoint
    private lateinit var timer: MonotonicTimer
    private lateinit var publisher: PubsubPublisherApi

    fun receivedSymbolCount(waveIndex: Int): Int = waveStates[waveIndex].receivedSymbolCount()
    fun recoveryTime(waveIndex: Int): Duration? = waveStates[waveIndex].recoveryAt

    override fun start(simContext: SimContext, networkContext: NetworkContext): CompletableFuture<Unit> {
        timer = simContext.timer
        epoch = simContext.timer.time()
        return super.start(simContext, networkContext)
    }

    override fun onAllConnected(simContext: SimContext, networkContext: NetworkContext) {
        messageApi.subscribe(Consumer { msg ->
            parseSymbol(msg.data)?.let { (waveIndex, symbolIndex) -> onSymbolReceived(waveIndex, symbolIndex) }
        }, topic)

        publisher = messageApi.createPublisher(networkContext.myHost.privKey)
        if (simNodeId == publisherNodeId) {
            repeat(waveCount) { waveIndex ->
                simContext.scheduler.executeAfterDelay(initialPublishDelay + publishInterval * waveIndex) {
                    // The random order is intentional: a publisher must not serialize symbols 0..127
                    // to every mesh peer before beginning the next symbol.
                    val randomizedSymbols = (0 until symbolCount).shuffled(random)
                    randomizedSymbols.forEach { symbolIndex ->
                        eventSink.record(
                            QuicScenarioEvent.GossipMessagePublished(
                                nodeId = simNodeId,
                                at = simContext.timer.time() - epoch,
                                messageIndex = waveIndex * symbolCount + symbolIndex
                            )
                        )
                    }
                    publisher.publishBatch(randomizedSymbols.map { symbolPayload(waveIndex, it) }, topic)
                        .whenComplete { _, error ->
                            check(error == null) { "Initial symbol publication failed: ${error.message}" }
                            publisherFinishedWaves[waveIndex].set(true)
                            completeIfReady()
                        }
                }
            }
        }
    }

    private fun onSymbolReceived(waveIndex: Int, symbolIndex: Int) {
        val state = waveStates[waveIndex]
        val newlyReceived = synchronized(state.receivedSymbols) { state.receivedSymbols.add(symbolIndex) }
        if (!newlyReceived || state.receivedSymbolCount() < recoveryThreshold || !state.recovered.compareAndSet(false, true)) {
            return
        }

        val missingSymbols = synchronized(state.receivedSymbols) {
            (0 until symbolCount).filterNot(state.receivedSymbols::contains)
        }.shuffled(random)
        state.recoveryAt = timer.time() - epoch
        eventSink.record(
            QuicScenarioEvent.GossipSymbolsRecovered(
                nodeId = simNodeId,
                at = state.recoveryAt!!,
                waveIndex = waveIndex,
                receivedSymbolCount = state.receivedSymbolCount(),
                republishedSymbolCount = missingSymbols.size
            )
        )

        missingSymbols.forEach { missingSymbol ->
            eventSink.record(
                QuicScenarioEvent.GossipMessagePublished(
                    nodeId = simNodeId,
                    at = timer.time() - epoch,
                    messageIndex = waveIndex * symbolCount + missingSymbol
                )
            )
        }
        publisher.publishBatch(missingSymbols.map { symbolPayload(waveIndex, it) }, topic)
            .whenComplete { _, error ->
                if (error != null) {
                    // A symbol may arrive between recovery and the router processing this batch.
                    // Retry independently so that one already-seen symbol cannot suppress the rest.
                    CompletableFuture.allOf(*missingSymbols.map { missingSymbol ->
                        publisher.publish(symbolPayload(waveIndex, missingSymbol), topic)
                    }.toTypedArray()).whenComplete { _, _ ->
                        state.recoveryPublicationFinished.set(true)
                        completeIfReady()
                    }
                } else {
                    state.recoveryPublicationFinished.set(true)
                    completeIfReady()
                }
            }
    }

    private fun completeIfReady() {
        if ((simNodeId == publisherNodeId && publisherFinishedWaves.all { it.get() }) ||
            (simNodeId != publisherNodeId && waveStates.all { it.recovered.get() && it.recoveryPublicationFinished.get() })
        ) {
            completeFuture.complete(Unit)
        }
    }

    private fun symbolPayload(waveIndex: Int, symbolIndex: Int): ByteBuf =
        Unpooled.wrappedBuffer(symbolPayloadBytes(waveIndex, symbolIndex))

    private fun symbolPayloadBytes(waveIndex: Int, symbolIndex: Int): ByteArray {
        val prefix = "$SYMBOL_PREFIX$waveIndex:$symbolIndex\n".toByteArray(StandardCharsets.UTF_8)
        require(prefix.size <= symbolSizeBytes) { "symbolSizeBytes is too small for symbol $symbolIndex" }
        return ByteArray(symbolSizeBytes) { offset ->
            if (offset < prefix.size) prefix[offset] else ((symbolIndex * 31 + offset) and 0xff).toByte()
        }
    }

    private fun parseSymbol(data: ByteBuf): Pair<Int, Int>? {
        val start = data.readerIndex()
        if (data.readableBytes() <= SYMBOL_PREFIX_BYTES.size) return null
        if (SYMBOL_PREFIX_BYTES.indices.any { data.getByte(start + it) != SYMBOL_PREFIX_BYTES[it] }) return null

        val values = mutableListOf<Int>()
        var value = 0
        var index = start + SYMBOL_PREFIX_BYTES.size
        val end = start + data.readableBytes()
        while (index < end) {
            val byte = data.getByte(index++)
            if (byte == SYMBOL_INDEX_SEPARATOR_BYTE || byte == NEW_LINE_BYTE) {
                values += value
                if (byte == NEW_LINE_BYTE) {
                    val waveIndex = values.getOrNull(0) ?: return null
                    val symbolIndex = values.getOrNull(1) ?: return null
                    return (waveIndex to symbolIndex)
                        .takeIf {
                            values.size == 2 && waveIndex in 0 until waveCount && symbolIndex in 0 until symbolCount
                        }
                }
                value = 0
                continue
            }
            val digit = byte - '0'.code.toByte()
            if (digit !in 0..9) return null
            value = value * 10 + digit
        }
        return null
    }

    companion object {
        private const val SYMBOL_PREFIX = "erasure-symbol:"
        private val SYMBOL_PREFIX_BYTES = SYMBOL_PREFIX.toByteArray(StandardCharsets.UTF_8)
        private const val NEW_LINE_BYTE: Byte = '\n'.code.toByte()
        private const val SYMBOL_INDEX_SEPARATOR_BYTE: Byte = ':'.code.toByte()
    }

    private fun WaveState.receivedSymbolCount(): Int = synchronized(receivedSymbols) { receivedSymbols.size }
}
