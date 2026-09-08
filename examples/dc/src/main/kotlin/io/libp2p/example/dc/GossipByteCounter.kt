package io.libp2p.example.dc

import com.google.protobuf.CodedOutputStream
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
 * [Rpc.RPC] instances. Counts are split into:
 *  - [publishBytesRead]/[publishBytesWritten]: bytes used by [Rpc.RPC.publishList] fields
 *  - [controlBytesRead]/[controlBytesWritten]: bytes used by subscriptions + control fields
 *
 * The sum + 2 (varint length prefix) equals the total stream bytes per RPC.
 */
@io.netty.channel.ChannelHandler.Sharable
class GossipByteCounter : ChannelDuplexHandler() {

    private val _publishBytesRead = AtomicLong(0)
    private val _publishBytesWritten = AtomicLong(0)
    private val _controlBytesRead = AtomicLong(0)
    private val _controlBytesWritten = AtomicLong(0)

    val publishBytesRead: Long get() = _publishBytesRead.get()
    val publishBytesWritten: Long get() = _publishBytesWritten.get()
    val controlBytesRead: Long get() = _controlBytesRead.get()
    val controlBytesWritten: Long get() = _controlBytesWritten.get()

    val bytesRead: Long get() = publishBytesRead + controlBytesRead
    val bytesWritten: Long get() = publishBytesWritten + controlBytesWritten

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is Rpc.RPC) count(msg, _publishBytesRead, _controlBytesRead)
        super.channelRead(ctx, msg)
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (msg is Rpc.RPC) count(msg, _publishBytesWritten, _controlBytesWritten)
        super.write(ctx, msg, promise)
    }

    companion object {
        // ProtobufVarint32LengthFieldPrepender adds a 1–5 byte varint before each RPC.
        // For our gossip RPCs the varint is 2 bytes (size 128–16383).
        private const val VARINT_OVERHEAD = 2L

        private fun encodedFieldSize(msg: com.google.protobuf.MessageLite): Long {
            val s = msg.serializedSize
            // 1 byte tag (field numbers 1–15, wire type 2) + varint(size) + payload
            return (1 + CodedOutputStream.computeUInt32SizeNoTag(s) + s).toLong()
        }

        private fun count(rpc: Rpc.RPC, publishAcc: AtomicLong, controlAcc: AtomicLong) {
            val publishBytes = rpc.publishList.sumOf { encodedFieldSize(it) }
            // Total RPC bytes on stream = rpc.serializedSize + VARINT_OVERHEAD.
            // Everything that isn't publish is control (subscriptions + control msg + RPC overhead).
            val totalBytes = rpc.serializedSize + VARINT_OVERHEAD
            publishAcc.addAndGet(publishBytes)
            controlAcc.addAndGet(totalBytes - publishBytes)
        }
    }
}
