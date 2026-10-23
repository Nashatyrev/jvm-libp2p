package io.libp2p.transport.quic

import io.libp2p.core.Connection
import io.libp2p.etc.types.toVoidCompletableFuture
import io.libp2p.transport.implementation.StreamOverNetty
import io.netty.incubator.codec.quic.QuicStreamChannel
import io.netty.incubator.codec.quic.QuicStreamPriority
import java.util.concurrent.CompletableFuture

class QuicStream(
    val quicStreamChannel: QuicStreamChannel,
    connection: Connection,
    initiator: Boolean
) : StreamOverNetty(quicStreamChannel, connection, initiator) {

    init {
        pushHandler(QuicStreamReadCloseEventConverter())
    }

    override fun closeWrite(): CompletableFuture<Unit> {
        return quicStreamChannel.shutdownOutput().toVoidCompletableFuture()
    }

    /**
     * Sets the RFC 9218 priority quiche schedules this stream's outgoing data with: lower [urgency]
     * goes first, and streams of equal urgency take turns when [incremental]
     */
    fun setPriority(urgency: Int, incremental: Boolean): CompletableFuture<Unit> {
        return quicStreamChannel.updatePriority(QuicStreamPriority(urgency, incremental)).toVoidCompletableFuture()
    }
}
