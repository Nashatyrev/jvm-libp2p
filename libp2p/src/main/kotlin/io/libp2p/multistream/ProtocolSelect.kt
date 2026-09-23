package io.libp2p.multistream

import io.libp2p.compression.SnappyStreamCodec
import io.libp2p.compression.SnappyStreamCompression
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.NoSuchLocalProtocolException
import io.libp2p.core.NoSuchRemoteProtocolException
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.etc.PROTOCOL
import io.libp2p.etc.events.ProtocolNegotiationFailed
import io.libp2p.etc.events.ProtocolNegotiationSucceeded
import io.libp2p.etc.getP2PChannel
import io.libp2p.etc.types.addAfter
import io.libp2p.etc.types.forward
import io.libp2p.etc.util.netty.nettyInitializer
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import java.util.concurrent.CompletableFuture

/**
 * Created by Anton Nashatyrev on 20.06.2019.
 */
class ProtocolSelect<TController>(
    val protocols: List<ProtocolBinding<TController>> = mutableListOf(),
    /** Whether `/snappy-stream` compressed variants of [protocols] may have been negotiated */
    val streamCompression: Boolean = false
) : ChannelInboundHandlerAdapter() {

    val selectedFuture = CompletableFuture<TController>()
    var activeFired = false

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        // when protocol data immediately follows protocol id in the same packet
        // the protocol data may be transmitted during Negotiator pipeline rebuilding
        // and the `active` event is fired after `read` event
        // See https://github.com/libp2p/jvm-libp2p/issues/94
        activeFired = true
        ctx.fireChannelActive()
        ctx.fireChannelRead(msg)
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        if (!activeFired) {
            ctx.fireChannelActive()
        }
        ctx.pipeline().remove(this)
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        when (evt) {
            is ProtocolNegotiationSucceeded -> {
                // a compressed stream carries the plain protocol: everything after the codec sees just that
                val uncompressed = if (streamCompression) SnappyStreamCompression.uncompressed(evt.proto) else null
                val proto = uncompressed ?: evt.proto
                val protocolBinding = protocols.find { it.protocolDescriptor.protocolMatcher.matches(proto) }
                    ?: throw NoSuchLocalProtocolException("Protocol negotiation failed: not supported protocol ${evt.proto}")
                ctx.channel().attr(PROTOCOL).get()?.complete(proto)
                var protocolStart = ctx.name()
                if (uncompressed != null) {
                    ctx.pipeline().addAfter(protocolStart, SnappyStreamCompression.CODEC_HANDLER_NAME, SnappyStreamCodec())
                    protocolStart = SnappyStreamCompression.CODEC_HANDLER_NAME
                }
                ctx.pipeline().addAfter(
                    protocolStart,
                    "ProtocolBindingInitializer",
                    nettyInitializer {
                        protocolBinding.initChannel(it.channel.getP2PChannel(), proto).forward(selectedFuture)
                    }
                )
                super.userEventTriggered(ctx, ProtocolNegotiationSucceeded(proto))
                return
            }
            is ProtocolNegotiationFailed -> throw NoSuchRemoteProtocolException("ProtocolNegotiationFailed: $evt")
        }
        super.userEventTriggered(ctx, evt)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable?) {
        ctx.channel().attr(PROTOCOL).get()?.completeExceptionally(cause)
        selectedFuture.completeExceptionally(cause)
        ctx.close()
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext) {
        val exception = ConnectionClosedException("Channel closed ${ctx.channel()}")
        selectedFuture.completeExceptionally(exception)
        ctx.channel().attr(PROTOCOL).get()?.completeExceptionally(exception)
    }
}
