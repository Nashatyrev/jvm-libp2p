package io.libp2p.quicsim.program

import io.libp2p.core.Connection
import io.libp2p.core.Stream
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.quicsim.core.schedule.TimePoint
import io.libp2p.quicsim.scenario.QuicScenarioEvent
import io.libp2p.quicsim.scenario.QuicScenarioEventSink
import io.libp2p.quicsim.scenario.QuicScenarioEventSource
import io.libp2p.quicsim.scenario.RecordingQuicScenarioEventSink
import io.libp2p.quicsim.sim.NetworkContext
import io.libp2p.quicsim.sim.SimContext
import io.libp2p.quicsim.sim.SimNodeId
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * Creates all-to-all connected simulator nodes which send configured data chunks in flushed packets.
 */
class DataChunkNodeProgramFactory(
    val nodeCount: Int,
    chunks: List<DataChunk>,
    val packetSizeBytes: Int = DEFAULT_PACKET_SIZE_BYTES,
    val eventSink: QuicScenarioEventSink = RecordingQuicScenarioEventSink(),
) : NodeProgramFactory, QuicScenarioEventSource {

    data class DataChunk(
        val sizeBytes: Int,
        val at: Duration,
        val from: SimNodeId,
        val to: SimNodeId,
    )

    data class PacketReceipt(
        val chunkIndex: Int,
        val sequence: Int,
        val totalPackets: Int,
        val payloadBytes: Int,
        val from: SimNodeId,
        val to: SimNodeId,
        val receivedAt: Duration,
    )

    data class ChunkSend(
        val chunkIndex: Int,
        val from: SimNodeId,
        val to: SimNodeId,
        val sentAt: Duration,
    )

    private data class IndexedDataChunk(
        val index: Int,
        val chunk: DataChunk,
        val expectedPackets: Int,
    )

    private val maxPayloadBytesPerPacket: Int
    private val indexedChunks: List<IndexedDataChunk>
    private val chunksBySender: Map<SimNodeId, List<IndexedDataChunk>>
    private val chunksByReceiver: Map<SimNodeId, List<IndexedDataChunk>>
    private val receiptCounts = ConcurrentHashMap<Int, AtomicInteger>()
    private val sentChunkIndices = ConcurrentHashMap.newKeySet<Int>()
    private val connectedNodeIds = List(nodeCount) { ConcurrentHashMap.newKeySet<SimNodeId>() }
    private val failures = Collections.synchronizedList(mutableListOf<Throwable>())

    init {
        require(nodeCount > 0) { "nodeCount must be positive" }
        require(packetSizeBytes > WIRE_HEADER_BYTES) {
            "packetSizeBytes=$packetSizeBytes must be greater than header size $WIRE_HEADER_BYTES"
        }

        maxPayloadBytesPerPacket = packetSizeBytes - WIRE_HEADER_BYTES
        indexedChunks = chunks.mapIndexed { index, chunk ->
            validateChunk(index, chunk)
            IndexedDataChunk(index, chunk, packetCount(chunk.sizeBytes))
        }
        chunksBySender = indexedChunks.groupBy { it.chunk.from }
        chunksByReceiver = indexedChunks.groupBy { it.chunk.to }
    }

    override fun createNode(id: SimNodeId): NodeProgram {
        require(id in 0 until nodeCount) {
            "Node id $id is outside configured range 0 until $nodeCount"
        }
        return DataChunkNodeProgram(id)
    }

    override fun events(): List<QuicScenarioEvent> =
        (eventSink as? QuicScenarioEventSource)?.events().orEmpty()

    fun packetReceipts(): List<PacketReceipt> =
        DataChunkMetrics.packetReceipts(events())

    fun packetReceipts(chunkIndex: Int): List<PacketReceipt> =
        packetReceipts().filter { it.chunkIndex == chunkIndex }

    fun chunkSends(): List<ChunkSend> =
        DataChunkMetrics.chunkSends(events())

    fun debugState(): String {
        val receiptCountsSnapshot = indexedChunks.associate { chunk ->
            chunk.index to (receiptCounts[chunk.index]?.get() ?: 0)
        }
        return "nodeCount=$nodeCount " +
                "connected=${connectedNodeIds.map { it.size }} " +
                "sent=${sentChunkIndices.toList().sorted()} " +
                "receiptCounts=$receiptCountsSnapshot " +
                "receipts=${packetReceipts().size} " +
                "failures=${synchronized(failures) { failures.map { it.message } }}"
    }

    private fun validateChunk(index: Int, chunk: DataChunk) {
        require(chunk.sizeBytes >= 0) { "Chunk $index has negative sizeBytes=${chunk.sizeBytes}" }
        require(!chunk.at.isNegative()) { "Chunk $index has negative at=${chunk.at}" }
        require(chunk.from in 0 until nodeCount) {
            "Chunk $index has from=${chunk.from}, outside configured range 0 until $nodeCount"
        }
        require(chunk.to in 0 until nodeCount) {
            "Chunk $index has to=${chunk.to}, outside configured range 0 until $nodeCount"
        }
        require(chunk.from != chunk.to) { "Chunk $index must be sent between different nodes" }
    }

    private fun packetCount(sizeBytes: Int): Int =
        if (sizeBytes == 0) 0 else (sizeBytes + maxPayloadBytesPerPacket - 1) / maxPayloadBytesPerPacket

    private fun recordReceipt(receipt: PacketReceipt) {
        val chunk = indexedChunks.getOrNull(receipt.chunkIndex)
            ?: throw IllegalArgumentException("Unknown chunk index ${receipt.chunkIndex}")
        require(receipt.sequence in 0 until receipt.totalPackets) {
            "Invalid packet sequence ${receipt.sequence}/${receipt.totalPackets} for chunk ${receipt.chunkIndex}"
        }
        require(receipt.totalPackets == chunk.expectedPackets) {
            "Unexpected total packet count ${receipt.totalPackets} for chunk ${receipt.chunkIndex}"
        }
        require(receipt.from == chunk.chunk.from && receipt.to == chunk.chunk.to) {
            "Packet route ${receipt.from}->${receipt.to} does not match chunk ${receipt.chunkIndex}"
        }

        eventSink.record(
            QuicScenarioEvent.DataChunkPacketReceived(
                nodeId = receipt.to,
                at = receipt.receivedAt,
                chunkIndex = receipt.chunkIndex,
                sequence = receipt.sequence,
                totalPackets = receipt.totalPackets,
                payloadBytes = receipt.payloadBytes,
                from = receipt.from,
                to = receipt.to
            )
        )
        receiptCounts.computeIfAbsent(receipt.chunkIndex) { AtomicInteger() }.incrementAndGet()
    }

    private fun recordFailure(error: Throwable) {
        failures += error
    }

    private fun throwIfFailed() {
        val failure = synchronized(failures) { failures.firstOrNull() } ?: return
        throw IllegalStateException("Data chunk node program failed", failure)
    }

    private inner class DataChunkNodeProgram(
        override val simNodeId: SimNodeId,
    ) : NodeProgram {
        private lateinit var binding: DataChunkBinding
        private lateinit var simContext: SimContext
        private lateinit var networkContext: NetworkContext
        private lateinit var epoch: TimePoint
        private val connections = ConcurrentHashMap<SimNodeId, Connection>()
        private val streamControllers = ConcurrentHashMap<SimNodeId, CompletableFuture<DataChunkController>>()
        override val completeFuture: CompletableFuture<Unit> = CompletableFuture()

        override fun createProtocols(context: SimContext): List<ProtocolBinding<*>> {
            binding = DataChunkBinding(
                DataChunkProtocol(
                    localNodeId = simNodeId,
                    packetSizeBytes = packetSizeBytes,
                    now = { now() },
                    recordReceipt = {
                        recordReceipt(it)
                        completeIfReady()
                    },
                    recordFailure = {
                        recordFailure(it)
                        completeFuture.completeExceptionally(it)
                    },
                )
            )
            return listOf(binding)
        }

        override fun start(simContext: SimContext, networkContext: NetworkContext): CompletableFuture<Unit> {
            this.simContext = simContext
            this.networkContext = networkContext
            this.epoch = simContext.timer.time()

            val streamFutures = (0 until nodeCount)
                .filter { it != simNodeId }
                .map { nodeId ->
                    val nodeAddress = networkContext.allNodes[nodeId]
                        ?: throw IllegalStateException("Node $nodeId not found")
                    networkContext.myHost.network.connect(nodeAddress)
                        .thenCompose { connection ->
                            connections[nodeId] = connection
                            connectedNodeIds[simNodeId] += nodeId
                            eventSink.record(
                                QuicScenarioEvent.NodeConnected(
                                    nodeId = simNodeId,
                                    at = now(),
                                    remoteNodeId = nodeId
                                )
                            )
                            completeIfReady()
                            openDataStream(nodeId, connection)
                        }
                }

            return CompletableFuture.allOf(*streamFutures.toTypedArray())
                .thenApply {
                    scheduleConfiguredSends()
                    Unit
                }
        }

        private fun isComplete(): Boolean {
            throwIfFailed()
            val expectedConnections = nodeCount - 1
            if (connectedNodeIds[simNodeId].size < expectedConnections) return false

            val sendsComplete = chunksBySender[simNodeId].orEmpty()
                .all { sentChunkIndices.contains(it.index) }
            val receivesComplete = chunksByReceiver[simNodeId].orEmpty()
                .all { chunk ->
                    receiptCounts[chunk.index]?.get() ?: 0 >= chunk.expectedPackets
                }

            return sendsComplete && receivesComplete
        }

        private fun completeIfReady() {
            runCatching {
                if (isComplete()) {
                    completeFuture.complete(Unit)
                }
            }.onFailure {
                completeFuture.completeExceptionally(it)
            }
        }

        private fun scheduleConfiguredSends() {
            chunksBySender[simNodeId].orEmpty().forEach { indexedChunk ->
                val elapsed = now()
                val delay = if (indexedChunk.chunk.at > elapsed) indexedChunk.chunk.at - elapsed else ZERO
                simContext.scheduler.executeAfterDelay(delay) {
                    sendChunk(indexedChunk)
                }
            }
        }

        private fun openDataStream(
            remoteNodeId: SimNodeId,
            connection: Connection
        ): CompletableFuture<Void> {
            val controllerFuture = connection.muxerSession()
                .createStream(binding)
                .controller
            streamControllers[remoteNodeId] = controllerFuture
            return controllerFuture.thenAccept { }
        }

        private fun sendChunk(indexedChunk: IndexedDataChunk) {
            try {
                val target = indexedChunk.chunk.to
                val controllerFuture = streamControllers[target]
                    ?: throw IllegalStateException("Node $simNodeId has no open data stream to node $target")
                val sentAt = now()
                sentChunkIndices += indexedChunk.index
                completeIfReady()
                eventSink.record(
                    QuicScenarioEvent.DataChunkSent(
                        nodeId = simNodeId,
                        at = sentAt,
                        chunkIndex = indexedChunk.index,
                        from = simNodeId,
                        to = target
                    )
                )

                controllerFuture
                    .thenCompose { controller ->
                        controller.send(indexedChunk)
                    }
                    .whenComplete { _, error ->
                        if (error != null) {
                            recordFailure(error)
                        }
                    }
            } catch (t: Throwable) {
                recordFailure(t)
            }
        }

        private fun now(): Duration = simContext.timer.time() - epoch
    }

    private class DataChunkBinding(protocol: DataChunkProtocol) :
        StrictProtocolBinding<DataChunkController>(PROTOCOL_ID, protocol)

    private interface DataChunkController {
        fun send(chunk: IndexedDataChunk): CompletableFuture<Unit>
    }

    private class DataChunkProtocol(
        private val localNodeId: SimNodeId,
        private val packetSizeBytes: Int,
        private val now: () -> Duration,
        private val recordReceipt: (PacketReceipt) -> Unit,
        private val recordFailure: (Throwable) -> Unit,
    ) : ProtocolHandler<DataChunkController>(Long.MAX_VALUE, Long.MAX_VALUE) {

        override fun initProtocolStream(stream: Stream) {
            stream.pushHandler(
                "data-chunk-frame-decoder",
                LengthFieldBasedFrameDecoder(
                    packetSizeBytes + FRAME_LENGTH_BYTES,
                    0,
                    FRAME_LENGTH_BYTES,
                    0,
                    FRAME_LENGTH_BYTES
                )
            )
            stream.pushHandler("data-chunk-frame-encoder", LengthFieldPrepender(FRAME_LENGTH_BYTES))
        }

        override fun onStartInitiator(stream: Stream): CompletableFuture<DataChunkController> {
            val ready = CompletableFuture<Void>()
            val controller = SenderController(packetSizeBytes, ready)
            stream.pushHandler(controller)
            return ready.thenApply { controller }
        }

        override fun onStartResponder(stream: Stream): CompletableFuture<DataChunkController> {
            val controller = ReceiverController(localNodeId, now, recordReceipt, recordFailure)
            stream.pushHandler(controller)
            return CompletableFuture.completedFuture(controller)
        }

        private class SenderController(
            private val packetSizeBytes: Int,
            private val ready: CompletableFuture<Void>,
        ) : ProtocolMessageHandler<ByteBuf>, DataChunkController {
            private lateinit var stream: Stream
            private val maxPayloadBytes = packetSizeBytes - WIRE_HEADER_BYTES

            override fun onActivated(stream: Stream) {
                this.stream = stream
                ready.complete(null)
            }

            override fun onMessage(stream: Stream, msg: ByteBuf) {
                // Send-only controller.
            }

            override fun send(chunk: IndexedDataChunk): CompletableFuture<Unit> {
                var remainingBytes = chunk.chunk.sizeBytes
                var sequence = 0
                while (remainingBytes > 0) {
                    val payloadBytes = min(remainingBytes, maxPayloadBytes)
                    val packet = Unpooled.buffer(WIRE_HEADER_BYTES + payloadBytes, WIRE_HEADER_BYTES + payloadBytes)
                    packet.writeInt(chunk.index)
                    packet.writeInt(sequence)
                    packet.writeInt(chunk.expectedPackets)
                    packet.writeInt(chunk.chunk.from)
                    packet.writeInt(chunk.chunk.to)
                    packet.writeInt(payloadBytes)
                    packet.writeZero(payloadBytes)
                    stream.writeAndFlush(packet)

                    remainingBytes -= payloadBytes
                    sequence++
                }
                return CompletableFuture.completedFuture(Unit)
            }
        }

        private class ReceiverController(
            private val localNodeId: SimNodeId,
            private val now: () -> Duration,
            private val recordReceipt: (PacketReceipt) -> Unit,
            private val recordFailure: (Throwable) -> Unit,
        ) : ProtocolMessageHandler<ByteBuf>, DataChunkController {
            override fun onMessage(stream: Stream, msg: ByteBuf) {
                try {
                    require(msg.readableBytes() >= WIRE_HEADER_BYTES) {
                        "Packet is too small: ${msg.readableBytes()} bytes"
                    }
                    val chunkIndex = msg.readInt()
                    val sequence = msg.readInt()
                    val totalPackets = msg.readInt()
                    val from = msg.readInt()
                    val to = msg.readInt()
                    val payloadBytes = msg.readInt()
                    require(to == localNodeId) {
                        "Packet for node $to received by node $localNodeId"
                    }
                    require(payloadBytes == msg.readableBytes()) {
                        "Packet payload header says $payloadBytes bytes, frame has ${msg.readableBytes()}"
                    }
                    msg.skipBytes(payloadBytes)

                    recordReceipt(
                        PacketReceipt(
                            chunkIndex = chunkIndex,
                            sequence = sequence,
                            totalPackets = totalPackets,
                            payloadBytes = payloadBytes,
                            from = from,
                            to = to,
                            receivedAt = now(),
                        )
                    )
                } catch (t: Throwable) {
                    recordFailure(t)
                    stream.reset()
                }
            }

            override fun send(chunk: IndexedDataChunk): CompletableFuture<Unit> {
                return CompletableFuture.failedFuture(UnsupportedOperationException("Responder doesn't initiate sends"))
            }
        }
    }

    private companion object {
        private const val DEFAULT_PACKET_SIZE_BYTES = 1024
        private const val FRAME_LENGTH_BYTES = 4
        private const val WIRE_HEADER_BYTES = 24
        private const val PROTOCOL_ID = "/quicsim/data-chunk/1.0.0"
    }
}
