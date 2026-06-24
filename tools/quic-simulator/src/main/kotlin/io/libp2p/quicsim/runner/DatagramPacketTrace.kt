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

    object Noop : DatagramPacketTraceRecorder {
        override fun record(event: DatagramPacketTraceEvent) {
        }
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
        val local = when (direction) {
            DatagramPacketTraceEvent.Direction.OUTBOUND -> endpoint(sender() ?: ctx.channel().localAddress())
            DatagramPacketTraceEvent.Direction.INBOUND -> endpoint(recipient() ?: ctx.channel().localAddress())
        }
        val remote = when (direction) {
            DatagramPacketTraceEvent.Direction.OUTBOUND -> endpoint(recipient())
            DatagramPacketTraceEvent.Direction.INBOUND -> endpoint(sender())
        }
        return DatagramPacketTraceEvent(
            direction = direction,
            nodeId = nodeId,
            at = timeSupplier(),
            localHost = local.hostString,
            localPort = local.port,
            remoteHost = remote.hostString,
            remotePort = remote.port,
            bytes = content().readableBytes(),
            payloadSha256 = content().sha256Hex()
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
