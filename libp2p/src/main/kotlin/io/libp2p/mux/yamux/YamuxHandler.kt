package io.libp2p.mux.yamux

import io.libp2p.core.Libp2pException
import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.etc.types.sliceMaxSize
import io.libp2p.etc.util.netty.mux.MuxChannel
import io.libp2p.etc.util.netty.mux.MuxId
import io.libp2p.mux.MuxHandler
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

const val INITIAL_WINDOW_SIZE = 256 * 1024
const val MAX_BUFFERED_CONNECTION_WRITES = 1024 * 1024

open class YamuxHandler(
    override val multistreamProtocol: MultistreamProtocol,
    override val maxFrameDataLength: Int,
    ready: CompletableFuture<StreamMuxer.Session>?,
    inboundStreamHandler: StreamHandler<*>,
    initiator: Boolean
) : MuxHandler(ready, inboundStreamHandler) {
    private val idGenerator = AtomicInteger(if (initiator) 1 else 2) // 0 is reserved
    private val receiveWindows = ConcurrentHashMap<MuxId, AtomicInteger>()
    private val sendWindows = ConcurrentHashMap<MuxId, AtomicInteger>()
    private val sendBuffers = ConcurrentHashMap<MuxId, SendBuffer>()
    private val totalBufferedWrites = AtomicInteger()

    inner class SendBuffer(val ctx: ChannelHandlerContext) {
        private val buffered = ArrayDeque<ByteBuf>()

        fun add(data: ByteBuf) {
            buffered.add(data)
        }

        fun flush(sendWindow: AtomicInteger, id: MuxId): Int {
            var written = 0
            while (! buffered.isEmpty()) {
                val buf = buffered.first()
                if (buf.readableBytes() + written < sendWindow.get()) {
                    buffered.removeFirst()
                    sendBlocks(ctx, buf, sendWindow, id)
                    written += buf.readableBytes()
                } else
                    break
            }
            return written
        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        msg as YamuxFrame
        when (msg.type) {
            YamuxType.DATA -> handleDataRead(msg)
            YamuxType.WINDOW_UPDATE -> handleWindowUpdate(msg)
            YamuxType.PING -> handlePing(msg)
            YamuxType.GO_AWAY -> onRemoteClose(msg.id)
        }
    }

    fun handlePing(msg: YamuxFrame) {
        val ctx = getChannelHandlerContext()
        when (msg.flags) {
            YamuxFlags.SYN -> ctx.writeAndFlush(YamuxFrame(MuxId(msg.id.parentId, 0, msg.id.initiator), YamuxType.PING, YamuxFlags.ACK, msg.lenData))
            YamuxFlags.ACK -> {}
        }
    }

    fun handleFlags(msg: YamuxFrame) {
        val ctx = getChannelHandlerContext()
        when (msg.flags) {
            YamuxFlags.SYN -> {
                // ACK the new stream
                println("yamux:handleFlags - ack remote open")
                onRemoteOpen(msg.id)
                ctx.writeAndFlush(YamuxFrame(msg.id, YamuxType.WINDOW_UPDATE, YamuxFlags.ACK, 0))
                onStreamCreate(msg.id) // sometimes writes can happen before onRemoteCreated is called
            }
            YamuxFlags.FIN -> onRemoteDisconnect(msg.id)
            YamuxFlags.RST -> onRemoteClose(msg.id)
        }
    }

    fun handleDataRead(msg: YamuxFrame) {
        println("yamux:handleDataRead")
        val ctx = getChannelHandlerContext()
        val size = msg.lenData
        handleFlags(msg)
        if (size.toInt() == 0)
            return
        val recWindow = receiveWindows.get(msg.id)
        if (recWindow == null) {
            println("yamux:handleDataRead - null recWindow")
            releaseMessage(msg.data!!)
            throw Libp2pException("No receive window for " + msg.id)
        }
        val newWindow = recWindow.addAndGet(-size.toInt())
        if (newWindow < INITIAL_WINDOW_SIZE / 2) {
            val delta = INITIAL_WINDOW_SIZE / 2
            recWindow.addAndGet(delta)
            ctx.write(YamuxFrame(msg.id, YamuxType.WINDOW_UPDATE, 0, delta.toLong()))
            ctx.flush()
        }
        childRead(msg.id, msg.data!!)
    }

    fun handleWindowUpdate(msg: YamuxFrame) {
        println("yamux:handleWindowUpdate")
        handleFlags(msg)
        val size = msg.lenData.toInt()
        val sendWindow = sendWindows.get(msg.id)
        if (sendWindow == null) {
            println("yamux:handleWindowUpdate - null sendWindow")
            throw Libp2pException("No send window for " + msg.id)
        }
        sendWindow.addAndGet(size)
        val buffer = sendBuffers.get(msg.id)
        if (buffer != null) {
            val writtenBytes = buffer.flush(sendWindow, msg.id)
            totalBufferedWrites.addAndGet(-writtenBytes)
        }
    }

    override fun onChildWrite(child: MuxChannel<ByteBuf>, data: ByteBuf) {
        println("yamux:onChildWrite " + String(data.array()))
        val ctx = getChannelHandlerContext()

        val sendWindow = sendWindows.get(child.id)
        if (sendWindow == null) {
            println("yamux:onChildWrite - null sendWindow " + child.id)
            throw Libp2pException("No send window for " + child.id)
        }
        if (sendWindow.get() <= 0) {
            // wait until the window is increased to send more data
            val buffer = sendBuffers.getOrPut(child.id, { SendBuffer(ctx) })
            buffer.add(data)
            if (totalBufferedWrites.addAndGet(data.readableBytes()) > MAX_BUFFERED_CONNECTION_WRITES)
                throw Libp2pException("Overflowed send buffer for connection")
            return
        }
        sendBlocks(ctx, data, sendWindow, child.id)
    }

    fun sendBlocks(ctx: ChannelHandlerContext, data: ByteBuf, sendWindow: AtomicInteger, id: MuxId) {
        data.sliceMaxSize(minOf(maxFrameDataLength, sendWindow.get()))
            .map { frameSliceBuf ->
                sendWindow.addAndGet(-frameSliceBuf.readableBytes())
                YamuxFrame(id, YamuxType.DATA, 0, frameSliceBuf.readableBytes().toLong(), frameSliceBuf)
            }.forEach { muxFrame ->
                ctx.write(muxFrame)
            }
        ctx.flush()
    }

    override fun onLocalOpen(child: MuxChannel<ByteBuf>) {
        onStreamCreate(child.id)
        getChannelHandlerContext().writeAndFlush(YamuxFrame(child.id, YamuxType.DATA, YamuxFlags.SYN, 0))
    }

    override fun onRemoteCreated(child: MuxChannel<ByteBuf>) {
        println("yamux:onRemoteCreated")
        onStreamCreate(child.id)
    }

    private fun onStreamCreate(childId: MuxId) {
        receiveWindows.putIfAbsent(childId, AtomicInteger(INITIAL_WINDOW_SIZE))
        sendWindows.putIfAbsent(childId, AtomicInteger(INITIAL_WINDOW_SIZE))
    }

    override fun onLocalDisconnect(child: MuxChannel<ByteBuf>) {
        println("yamux:onLocalDisconnect")
        getChannelHandlerContext().writeAndFlush(YamuxFrame(child.id, YamuxType.DATA, YamuxFlags.RST, 0))
    }

    override fun onLocalClose(child: MuxChannel<ByteBuf>) {
        println("yamux:onLocalClose")
        val sendWindow = sendWindows.remove(child.id)
        val buffered = sendBuffers.remove(child.id)
        if (buffered != null && sendWindow != null) {
            buffered.flush(sendWindow, child.id)
        }
        getChannelHandlerContext().writeAndFlush(YamuxFrame(child.id, YamuxType.DATA, YamuxFlags.FIN, 0))
    }

    override fun onChildClosed(child: MuxChannel<ByteBuf>) {
        println("yamux:onChildClosed")
        sendWindows.remove(child.id)
        receiveWindows.remove(child.id)
        sendBuffers.remove(child.id)
    }

    override fun generateNextId() =
        MuxId(getChannelHandlerContext().channel().id(), idGenerator.addAndGet(2).toLong(), true)
}
