package io.libp2p.example.dc

import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import pubsub.pb.Rpc
import java.util.concurrent.atomic.AtomicLong

/**
 * Netty handler that counts gossip RPC bytes at the protobuf level (after encoding/before
 * decoding), giving traffic figures that exclude QUIC/yamux framing.
 *
 * Position in the pipeline: after [ProtobufEncoder]/[ProtobufDecoder], so the objects seen are
 * [Rpc.RPC] instances. [Rpc.RPC.getSerializedSize] gives the raw protobuf byte count; add 2 for
 * the varint length prefix written by [ProtobufVarint32LengthFieldPrepender].
 */
@io.netty.channel.ChannelHandler.Sharable
class GossipByteCounter : ChannelDuplexHandler() {

    private val _bytesRead = AtomicLong(0)
    private val _bytesWritten = AtomicLong(0)

    val bytesRead: Long get() = _bytesRead.get()
    val bytesWritten: Long get() = _bytesWritten.get()

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is Rpc.RPC) _bytesRead.addAndGet(msg.serializedSize + VARINT_OVERHEAD)
        super.channelRead(ctx, msg)
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (msg is Rpc.RPC) _bytesWritten.addAndGet(msg.serializedSize + VARINT_OVERHEAD)
        super.write(ctx, msg, promise)
    }

    companion object {
        // ProtobufVarint32LengthFieldPrepender adds a 1–5 byte varint before each RPC message.
        // For our gossip RPCs (~270 bytes) the varint is always 2 bytes.
        private const val VARINT_OVERHEAD = 2L
    }
}
