package io.libp2p.quicsim.runner

import io.libp2p.etc.types.toHex
import io.libp2p.transport.quic.DatagramChannelFactory
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelPromise
import io.netty.channel.socket.DatagramPacket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration

interface DatagramPacketTraceRecorder {
    fun record(event: DatagramPacketTraceEvent)

    /**
     * Whether this recorder reads [DatagramPacketTraceEvent.payloadSha256]. Producing it means
     * hashing every datagram and hex-encoding the digest, so a recorder that never looks at it
     * should say so and be handed [DatagramPacketTraceEvent.NO_DIGEST] instead.
     */
    val needsPayloadDigest: Boolean get() = true

    /**
     * Whether this recorder reads the endpoint host fields. They resolve to a handful of distinct
     * values across a run, so a recorder that ignores them saves a string per datagram.
     */
    val needsEndpoints: Boolean get() = true

    // Noop deliberately keeps the defaults. Skipping the per-datagram digest measurably shifts
    // simulated delivery timing -- the Shadow fidelity comparisons move by ~5% and fail -- so the
    // untraced path stays exactly as it was calibrated. Only a recorder that trades that timing for
    // scale, like DatagramTrafficAggregate, opts out.
    object Noop : DatagramPacketTraceRecorder {
        override fun record(event: DatagramPacketTraceEvent) {
        }
    }
}

/** Packet and byte counts in one direction each, over some selection of nodes and time. */
data class DatagramTotals(
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0
)

/**
 * A recorder that folds each datagram into per-node, per-direction counters on a uniform time grid
 * rather than retaining the events.
 *
 * Retaining them does not scale. A 500k-validator DC run traced 318 million datagrams, and the
 * events — three strings apiece, one of them a SHA-256 hex digest — accounted for about a third of
 * a 330 GB heap, which is what stopped the same run at 800k. Every report built from the trace only
 * ever asks for packet and byte counts per node, per direction, per time bucket, so that is all
 * this keeps: `2 x nodeCount x bucketCount` longs, tens of megabytes however many datagrams pass
 * through.
 *
 * [bucketDuration] sets the resolution, and therefore what the aggregate can still answer: a window
 * boundary that does not fall on a bucket edge is rounded to one. Callers that also bucket by
 * position within a slot cycle should use the same resolution there — see
 * `DcSlotProfileParams.bucketDuration` in the DC scenarios.
 */
class DatagramTrafficAggregate(
    val bucketDuration: Duration,
    val nodeCount: Int,
    runDuration: Duration
) : DatagramPacketTraceRecorder {

    init {
        require(bucketDuration.isPositive()) { "bucketDuration must be > 0, got $bucketDuration" }
        require(nodeCount > 0) { "nodeCount must be > 0, got $nodeCount" }
        require(!runDuration.isNegative()) { "runDuration must be >= 0, got $runDuration" }
    }

    /** One past the last bucket a [runDuration]-long run can reach, so the last instant still fits. */
    val bucketCount: Int =
        (runDuration.inWholeNanoseconds / bucketDuration.inWholeNanoseconds).toInt() + 1

    // [direction ordinal][nodeId * bucketCount + bucket], flat so the whole thing is two allocations.
    private val packets = Array(DIRECTIONS) { LongArray(nodeCount * bucketCount) }
    private val bytes = Array(DIRECTIONS) { LongArray(nodeCount * bucketCount) }

    override val needsPayloadDigest: Boolean get() = false
    override val needsEndpoints: Boolean get() = false

    /** Datagrams whose node id or time fell outside the grid, so a miscount cannot pass silently. */
    var dropped: Long = 0
        private set

    @Synchronized
    override fun record(event: DatagramPacketTraceEvent) {
        val bucket = bucketOf(event.at)
        if (bucket == null || event.nodeId !in 0 until nodeCount) {
            dropped++
            return
        }
        val d = event.direction.ordinal
        val index = event.nodeId * bucketCount + bucket
        packets[d][index] = packets[d][index] + 1
        bytes[d][index] = bytes[d][index] + event.bytes
    }

    /** Bucket [at] falls in, or null when it is outside the grid. */
    fun bucketOf(at: Duration): Int? {
        if (at.isNegative()) return null
        val bucket = (at.inWholeNanoseconds / bucketDuration.inWholeNanoseconds).toInt()
        return if (bucket in 0 until bucketCount) bucket else null
    }

    /** Start of [bucket] on the grid. */
    fun bucketStart(bucket: Int): Duration = bucketDuration * bucket

    /**
     * Totals over [nodeIds] — every node when null — and the buckets covering `[from, until)`.
     * [until] null means to the end of the run.
     */
    @Synchronized
    fun totals(nodeIds: Set<Int>? = null, from: Duration = Duration.ZERO, until: Duration? = null): DatagramTotals {
        val firstBucket = ((from.inWholeNanoseconds / bucketDuration.inWholeNanoseconds).toInt()).coerceIn(0, bucketCount)
        val lastBucket = until
            ?.let { (it.inWholeNanoseconds / bucketDuration.inWholeNanoseconds).toInt().coerceIn(0, bucketCount) }
            ?: bucketCount
        var packetsSent = 0L
        var packetsReceived = 0L
        var bytesSent = 0L
        var bytesReceived = 0L
        forEachNode(nodeIds) { node ->
            val base = node * bucketCount
            for (bucket in firstBucket until lastBucket) {
                val i = base + bucket
                packetsSent += packets[OUT][i]
                packetsReceived += packets[IN][i]
                bytesSent += bytes[OUT][i]
                bytesReceived += bytes[IN][i]
            }
        }
        return DatagramTotals(packetsSent, packetsReceived, bytesSent, bytesReceived)
    }

    /** Inbound bytes per grid bucket, summed over [nodeIds] — every node when null. */
    @Synchronized
    fun inboundBytesPerBucket(nodeIds: Set<Int>? = null): LongArray {
        val out = LongArray(bucketCount)
        forEachNode(nodeIds) { node ->
            val base = node * bucketCount
            for (bucket in 0 until bucketCount) {
                out[bucket] = out[bucket] + bytes[IN][base + bucket]
            }
        }
        return out
    }

    private inline fun forEachNode(nodeIds: Set<Int>?, body: (Int) -> Unit) {
        if (nodeIds == null) {
            for (node in 0 until nodeCount) body(node)
        } else {
            for (node in nodeIds) if (node in 0 until nodeCount) body(node)
        }
    }

    companion object {
        private const val DIRECTIONS = 2
        private val OUT = DatagramPacketTraceEvent.Direction.OUTBOUND.ordinal
        private val IN = DatagramPacketTraceEvent.Direction.INBOUND.ordinal
    }
}

class RecordingDatagramPacketTraceRecorder : DatagramPacketTraceRecorder {
    private val events = mutableListOf<DatagramPacketTraceEvent>()

    @Synchronized
    override fun record(event: DatagramPacketTraceEvent) {
        events += event
    }

    @Synchronized
    fun events(): List<DatagramPacketTraceEvent> =
        events.toList()
}

class FileDatagramPacketTraceRecorder(
    private val path: Path
) : DatagramPacketTraceRecorder {
    private val lock = Any()

    init {
        path.parent?.createDirectories()
        if (!path.exists()) {
            Files.writeString(
                path,
                CSV_HEADER + System.lineSeparator(),
                StandardOpenOption.CREATE_NEW
            )
        }
    }

    override fun record(event: DatagramPacketTraceEvent) {
        synchronized(lock) {
            Files.writeString(
                path,
                event.toCsvRow() + System.lineSeparator(),
                StandardOpenOption.APPEND
            )
        }
    }

    companion object {
        const val CSV_HEADER =
            "direction,node_id,time_ns,time_s,local_host,local_port,remote_host,remote_port,bytes,payload_sha256,match_key"
    }
}

data class DatagramPacketTraceEvent(
    val direction: Direction,
    val nodeId: Int,
    val at: Duration,
    val localHost: String,
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
    val bytes: Int,
    val payloadSha256: String
) {
    enum class Direction {
        OUTBOUND,
        INBOUND
    }

    val matchKey: String
        get() = payloadSha256

    companion object {
        /** Stands in for [payloadSha256] when the recorder said it does not need one. */
        const val NO_DIGEST: String = ""

        /** Stands in for a host field when the recorder said it does not need endpoints. */
        const val NO_HOST: String = ""
    }
}

fun DatagramPacketTraceEvent.toCsvRow(): String =
    listOf(
        direction.name.lowercase(),
        nodeId,
        at.inWholeNanoseconds,
        "%.9f".format(at.inWholeNanoseconds / 1_000_000_000.0),
        localHost,
        localPort,
        remoteHost,
        remotePort,
        bytes,
        payloadSha256,
        matchKey
    ).joinToString(",")

class TracingDatagramChannelFactory(
    private val delegate: DatagramChannelFactory,
    private val nodeId: Int,
    private val timeSupplier: () -> Duration,
    private val traceRecorder: DatagramPacketTraceRecorder
) : DatagramChannelFactory {
    override fun createClientChannel(handler: ChannelHandler): CompletableFuture<Channel> =
        delegate.createClientChannel(tracingInitializer(handler))

    override fun createServerChannel(bindAddress: SocketAddress, handler: ChannelHandler): CompletableFuture<Channel> =
        delegate.createServerChannel(bindAddress, tracingInitializer(handler))

    override fun shutdown(): CompletableFuture<Unit> =
        delegate.shutdown()

    private fun tracingInitializer(handler: ChannelHandler): ChannelHandler =
        object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                ch.pipeline().addLast("udp-trace", DatagramPacketTraceHandler(nodeId, timeSupplier, traceRecorder))
                ch.pipeline().addLast("quic-codec", handler)
            }
        }
}

private class DatagramPacketTraceHandler(
    private val nodeId: Int,
    private val timeSupplier: () -> Duration,
    private val traceRecorder: DatagramPacketTraceRecorder
) : ChannelDuplexHandler() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is DatagramPacket) {
            traceRecorder.record(msg.toTraceEvent(DatagramPacketTraceEvent.Direction.INBOUND, ctx))
        }
        super.channelRead(ctx, msg)
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (msg is DatagramPacket) {
            traceRecorder.record(msg.toTraceEvent(DatagramPacketTraceEvent.Direction.OUTBOUND, ctx))
        }
        super.write(ctx, msg, promise)
    }

    private fun DatagramPacket.toTraceEvent(
        direction: DatagramPacketTraceEvent.Direction,
        ctx: ChannelHandlerContext
    ): DatagramPacketTraceEvent {
        // Both of these cost per datagram -- a string each, and a SHA-256 over the whole payload --
        // so they are only produced for a recorder that says it reads them. An aggregating recorder
        // traces hundreds of millions of datagrams and reads neither.
        val endpoints = traceRecorder.needsEndpoints
        val local = if (!endpoints) {
            null
        } else {
            when (direction) {
                DatagramPacketTraceEvent.Direction.OUTBOUND -> endpoint(sender() ?: ctx.channel().localAddress())
                DatagramPacketTraceEvent.Direction.INBOUND -> endpoint(recipient() ?: ctx.channel().localAddress())
            }
        }
        val remote = if (!endpoints) {
            null
        } else {
            when (direction) {
                DatagramPacketTraceEvent.Direction.OUTBOUND -> endpoint(recipient())
                DatagramPacketTraceEvent.Direction.INBOUND -> endpoint(sender())
            }
        }
        return DatagramPacketTraceEvent(
            direction = direction,
            nodeId = nodeId,
            at = timeSupplier(),
            localHost = local?.hostString ?: DatagramPacketTraceEvent.NO_HOST,
            localPort = local?.port ?: -1,
            remoteHost = remote?.hostString ?: DatagramPacketTraceEvent.NO_HOST,
            remotePort = remote?.port ?: -1,
            bytes = content().readableBytes(),
            payloadSha256 =
            if (traceRecorder.needsPayloadDigest) content().sha256Hex() else DatagramPacketTraceEvent.NO_DIGEST
        )
    }
}

private fun endpoint(address: SocketAddress?): InetSocketAddress =
    address as? InetSocketAddress ?: InetSocketAddress("unknown", -1)

private fun ByteBuf.sha256Hex(): String {
    val bytes = ByteArray(readableBytes())
    getBytes(readerIndex(), bytes)
    return MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}
