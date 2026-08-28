package io.libp2p.quicsim.program

import io.libp2p.core.PeerId
import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.GossipExtension
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipScoreParams
import io.libp2p.pubsub.gossip.PartialMessagesHandler
import io.libp2p.pubsub.gossip.PublishActionsFn
import io.libp2p.pubsub.gossip.PublishAction
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.core.schedule.TimePoint
import io.libp2p.quicsim.scenario.QuicScenarioEvent
import io.libp2p.quicsim.scenario.QuicScenarioEventSink
import io.libp2p.quicsim.sim.NetworkContext
import io.libp2p.quicsim.sim.SimContext
import io.libp2p.quicsim.sim.SimNodeId
import pubsub.pb.Rpc
import java.nio.charset.StandardCharsets
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Simulates erasure-coded dissemination using Gossipsub's partial-messages extension.
 *
 * A wave consists of [symbolCount] symbols. A non-publisher recovers after physically receiving
 * any [recoveryThreshold] different symbols; it can then make every symbol available locally.
 * Availability is exchanged as an opaque bitmap in partial-message metadata. A peer that learns
 * another peer's bitmap sends only the symbols missing from that bitmap. This keeps the erasure
 * coding and bitmap format in the simulation/application while exercising the libp2p extension
 * for the actual per-peer message scheduling.
 */
class PartialErasureCodedGossipNodeProgram(
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
    private val topicName: String = "/quicsim/partial-erasure-coded-symbols",
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

    private data class PeerAvailability(val symbols: Set<Int>)

    private data class WaveState(
        val physicallyReceived: MutableSet<Int> = mutableSetOf(),
        val availableSymbols: MutableSet<Int> = mutableSetOf(),
        val recovered: AtomicBoolean = AtomicBoolean(false),
        @Volatile var recoveryAt: Duration? = null,
    )

    private val random = Random(randomSeed)
    private val allSymbols = (0 until symbolCount).toSet()
    private val waveStates = List(waveCount) { WaveState() }
    private val publisherFinishedWaves = List(waveCount) { AtomicBoolean(false) }
    private lateinit var epoch: TimePoint
    private lateinit var timer: MonotonicTimer

    fun receivedSymbolCount(waveIndex: Int): Int = synchronized(waveStates[waveIndex].physicallyReceived) {
        waveStates[waveIndex].physicallyReceived.size
    }

    fun recoveryTime(waveIndex: Int): Duration? = waveStates[waveIndex].recoveryAt

    override fun configureGossipRouterBuilder(builder: GossipRouterBuilder) {
        builder.protocol = PubsubProtocol.Gossip_V_1_3
        builder.enabledGossipExtensions(GossipExtension.PARTIAL_MESSAGES)
        builder.partialMessagesHandler = partialMessagesHandler
    }

    override fun start(simContext: SimContext, networkContext: NetworkContext): CompletableFuture<Unit> {
        timer = simContext.timer
        epoch = simContext.timer.time()
        return super.start(simContext, networkContext)
    }

    override fun onAllConnected(simContext: SimContext, networkContext: NetworkContext) {
        // The extension options must be installed before the subscription creates a mesh.
        gossipProtocol.enablePartialMessagesForTopic(topicName).thenRun {
            messageApi.subscribe(Consumer { }, Topic(topicName))
            if (simNodeId == publisherNodeId) {
                repeat(waveCount) { waveIndex ->
                    val publishAt = initialPublishDelay + publishInterval * waveIndex
                    val publishDelay = (publishAt - (simContext.timer.time() - epoch)).coerceAtLeast(Duration.ZERO)
                    simContext.scheduler.executeAfterDelay(publishDelay) {
                        publishInitialWave(waveIndex)
                    }
                }
            }
        }.whenComplete { _, error ->
            if (error != null) completeFuture.completeExceptionally(error)
        }
    }

    private fun publishInitialWave(waveIndex: Int) {
        val state = waveStates[waveIndex]
        synchronized(state.availableSymbols) { state.availableSymbols += allSymbols }
        val meshPeers = gossipRouter.mesh[topicName].orEmpty().map { it.peerId }.shuffled(random)
        check(meshPeers.isNotEmpty()) { "No mesh peers for initial partial publication" }

        val groupId = groupId(waveIndex)
        val symbols = allSymbols.shuffled(random)
        gossipProtocol.publishPartial(topicName, groupId, PublishActionsFn<PeerAvailability> { _, requestsPartial ->
            meshPeers.asSequence()
                .filter(requestsPartial)
                .flatMap { peer ->
                    symbols.shuffled(random).asSequence().mapIndexed { index, symbolIndex ->
                        eventSink.record(
                            QuicScenarioEvent.GossipMessagePublished(
                                nodeId = simNodeId,
                                at = timer.time() - epoch,
                                messageIndex = waveIndex * symbolCount + symbolIndex
                            )
                        )
                        peer to PublishAction<PeerAvailability>(
                            partialMessage = symbolPayloadBytes(waveIndex, symbolIndex),
                            partsMetadata = (index == symbols.lastIndex).whenTrue { encodeAvailability(allSymbols) },
                            // All symbols are now queued to this peer, so subsequent synchronisation
                            // need only consider a newer bitmap received from that peer.
                            nextPeerState = PeerAvailability(allSymbols)
                        )
                    }
                }
        }).whenComplete { _, error ->
            check(error == null) { "Initial partial symbol publication failed: ${error.message}" }
            publisherFinishedWaves[waveIndex].set(true)
            completeIfReady()
        }
    }

    private fun onIncomingRpc(
        from: PeerId,
        knownRemoteAvailability: Set<Int>?,
        rpc: Rpc.PartialMessagesExtension
    ) {
        val waveIndex = parseGroupId(rpc.groupID.toByteArray()) ?: return
        rpc.takeIf { it.hasPartialMessage() }
            ?.partialMessage
            ?.toByteArray()
            ?.let(::parseSymbol)
            ?.takeIf { (payloadWave, _) -> payloadWave == waveIndex }
            ?.second
            ?.let { symbolIndex -> onSymbolReceived(waveIndex, symbolIndex) }

        // Metadata is a compact availability announcement. Respond even when this node has no
        // data the sender lacks: the response is what lets a recovered peer discover our bitmap
        // and send the missing symbols back to us.
        rpc.takeIf { it.hasPartsMetadata() }
            ?.partsMetadata
            ?.toByteArray()
            ?.let(::decodeAvailability)
            ?.takeIf { it != knownRemoteAvailability }
            ?.let { remoteAvailability -> synchronizeWith(from, waveIndex, remoteAvailability) }
    }

    private fun onSymbolReceived(waveIndex: Int, symbolIndex: Int) {
        val state = waveStates[waveIndex]
        val isNew = synchronized(state.physicallyReceived) { state.physicallyReceived.add(symbolIndex) }
        if (!isNew) return

        val recoveredNow = synchronized(state.availableSymbols) {
            state.availableSymbols += symbolIndex
            state.physicallyReceived.size >= recoveryThreshold && state.recovered.compareAndSet(false, true)
                .also { if (it) state.availableSymbols += allSymbols }
        }
        if (!recoveredNow) return

        state.recoveryAt = timer.time() - epoch
        eventSink.record(
            QuicScenarioEvent.GossipSymbolsRecovered(
                nodeId = simNodeId,
                at = state.recoveryAt!!,
                waveIndex = waveIndex,
                receivedSymbolCount = receivedSymbolCount(waveIndex),
                republishedSymbolCount = symbolCount - receivedSymbolCount(waveIndex)
            )
        )
        announceAvailability(waveIndex)
        completeIfReady()
    }

    private fun announceAvailability(waveIndex: Int) {
        val meshPeers = gossipRouter.mesh[topicName].orEmpty().map { it.peerId }.shuffled(random)
        if (meshPeers.isEmpty()) return
        val availability = currentAvailability(waveIndex)
        gossipProtocol.publishPartial(topicName, groupId(waveIndex), PublishActionsFn<PeerAvailability> { _, requestsPartial ->
            meshPeers.asSequence()
                .filter(requestsPartial)
                .map { peer ->
                    peer to PublishAction<PeerAvailability>(partsMetadata = encodeAvailability(availability))
                }
        })
    }

    private fun synchronizeWith(from: PeerId, waveIndex: Int, remoteAvailability: Set<Int>) {
        val localAvailability = currentAvailability(waveIndex)
        val missingAtRemote = (localAvailability - remoteAvailability).shuffled(random)
        val nextPeerState = PeerAvailability(remoteAvailability + missingAtRemote)
        gossipProtocol.publishPartial(topicName, groupId(waveIndex), PublishActionsFn<PeerAvailability> { _, requestsPartial ->
            if (!requestsPartial(from)) return@PublishActionsFn emptySequence<Pair<PeerId, PublishAction<PeerAvailability>>>()
            sequence {
                if (missingAtRemote.isEmpty()) {
                    yield(
                        from to PublishAction<PeerAvailability>(
                            partsMetadata = encodeAvailability(localAvailability),
                            nextPeerState = nextPeerState
                        )
                    )
                } else {
                    missingAtRemote.forEachIndexed { index, symbolIndex ->
                        yield(
                            from to PublishAction<PeerAvailability>(
                                partialMessage = symbolPayloadBytes(waveIndex, symbolIndex),
                                partsMetadata = (index == missingAtRemote.lastIndex)
                                    .whenTrue { encodeAvailability(localAvailability) },
                                nextPeerState = nextPeerState
                            )
                        )
                    }
                }
            }
        })
    }

    private fun currentAvailability(waveIndex: Int): Set<Int> = synchronized(waveStates[waveIndex].availableSymbols) {
        waveStates[waveIndex].availableSymbols.toSet()
    }

    private fun completeIfReady() {
        if ((simNodeId == publisherNodeId && publisherFinishedWaves.all { it.get() }) ||
            (simNodeId != publisherNodeId && waveStates.all { it.recovered.get() })
        ) {
            completeFuture.complete(Unit)
        }
    }

    private fun groupId(waveIndex: Int): ByteArray = "$GROUP_PREFIX$waveIndex".toByteArray(StandardCharsets.UTF_8)

    private fun parseGroupId(groupId: ByteArray): Int? = groupId.toString(StandardCharsets.UTF_8)
        .takeIf { it.startsWith(GROUP_PREFIX) }
        ?.removePrefix(GROUP_PREFIX)
        ?.toIntOrNull()
        ?.takeIf { it in 0 until waveCount }

    private fun symbolPayloadBytes(waveIndex: Int, symbolIndex: Int): ByteArray {
        val prefix = "$SYMBOL_PREFIX$waveIndex:$symbolIndex\n".toByteArray(StandardCharsets.UTF_8)
        require(prefix.size <= symbolSizeBytes) { "symbolSizeBytes is too small for symbol $symbolIndex" }
        return ByteArray(symbolSizeBytes) { offset ->
            if (offset < prefix.size) prefix[offset] else ((symbolIndex * 31 + offset) and 0xff).toByte()
        }
    }

    private fun parseSymbol(data: ByteArray): Pair<Int, Int>? {
        if (!data.startsWith(SYMBOL_PREFIX_BYTES)) return null
        val headerEnd = data.indexOf(NEW_LINE_BYTE, SYMBOL_PREFIX_BYTES.size)
        if (headerEnd == -1) return null
        val values = data.copyOfRange(SYMBOL_PREFIX_BYTES.size, headerEnd)
            .toString(StandardCharsets.UTF_8)
            .split(':')
        if (values.size != 2) return null
        val waveIndex = values[0].toIntOrNull() ?: return null
        val symbolIndex = values[1].toIntOrNull() ?: return null
        return (waveIndex to symbolIndex)
            .takeIf { waveIndex in 0 until waveCount && symbolIndex in 0 until symbolCount }
    }

    private fun encodeAvailability(symbols: Set<Int>): ByteArray = ByteArray((symbolCount + 7) / 8).also { bytes ->
        symbols.forEach { symbol -> bytes[symbol / 8] = (bytes[symbol / 8].toInt() or (1 shl (symbol % 8))).toByte() }
    }

    private fun decodeAvailability(metadata: ByteArray): Set<Int>? {
        if (metadata.size != (symbolCount + 7) / 8) return null
        return buildSet {
            (0 until symbolCount).filterTo(this) { symbol ->
                metadata[symbol / 8].toInt() and (1 shl (symbol % 8)) != 0
            }
        }
    }

    private val partialMessagesHandler = object : PartialMessagesHandler<PeerAvailability> {
        override fun onIncomingRpc(
            from: PeerId,
            peerStates: Map<PeerId, PeerAvailability>,
            rpc: Rpc.PartialMessagesExtension
        ) = onIncomingRpc(from, peerStates[from]?.symbols, rpc)

        override fun onEmitGossip(
            topic: String,
            groupId: ByteArray,
            gossipPeers: Collection<PeerId>,
            peerStates: Map<PeerId, PeerAvailability>
        ) {
            // The simulation announces newly recovered availability to its mesh immediately.
            // Its configured lazy-gossip degree is zero, so no additional fanout is required here.
        }
    }

    private fun <T> Boolean.whenTrue(block: () -> T): T? = if (this) block() else null

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.indexOf(value: Byte, fromIndex: Int): Int {
        for (index in fromIndex until size) if (this[index] == value) return index
        return -1
    }

    companion object {
        private const val GROUP_PREFIX = "partial-erasure:"
        private const val SYMBOL_PREFIX = "partial-erasure-symbol:"
        private val SYMBOL_PREFIX_BYTES = SYMBOL_PREFIX.toByteArray(StandardCharsets.UTF_8)
        private const val NEW_LINE_BYTE: Byte = '\n'.code.toByte()
    }
}
