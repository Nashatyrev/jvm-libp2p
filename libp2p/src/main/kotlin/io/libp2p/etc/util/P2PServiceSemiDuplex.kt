package io.libp2p.etc.util

import io.libp2p.core.BadPeerException
import io.libp2p.core.SemiDuplexNoOutboundStreamException
import io.libp2p.etc.types.completedExceptionally
import io.libp2p.etc.types.toVoidCompletableFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

/**
 * The service where communication between peers is performed via two [io.libp2p.core.Stream]s
 * They are initiated asynchronously by each peer. Initiated stream is used solely for writing data
 * and accepted steam is used solely for reading
 *
 * A peer may additionally have lane streams (see [P2PService.StreamHandler.lane]) opened after the
 * main ones. They follow the same rule: written by the initiator, read by the acceptor. A write names
 * its lane, and goes over the main outbound stream while that lane has no active stream of its own.
 * Losing a lane stream doesn't disconnect the peer
 */
abstract class P2PServiceSemiDuplex(executor: ScheduledExecutorService) : P2PService(executor) {

    inner class SDPeerHandler(streamHandler: StreamHandler) : PeerHandler(streamHandler) {

        var otherStreamHandler: StreamHandler? = null

        /** Lane streams this peer writes to us over, by lane */
        val inboundLanes = mutableMapOf<Int, StreamHandler>()

        /** Lane streams we write to this peer over, by lane */
        val outboundLanes = mutableMapOf<Int, StreamHandler>()

        override fun writeAndFlush(msg: Any): CompletableFuture<Unit> = writeAndFlush(MAIN_LANE, msg)

        override fun writeAndFlush(lane: Int, msg: Any): CompletableFuture<Unit> =
            getOutboundHandler(lane)?.ctx?.writeAndFlush(msg)?.toVoidCompletableFuture() ?: completedExceptionally(
                SemiDuplexNoOutboundStreamException("No active outbound stream to write data $msg")
            )

        override fun enqueueWrite(messageSupplier: Sequence<Any>): CompletableFuture<Unit> = enqueueWrite(MAIN_LANE, messageSupplier)

        override fun enqueueWrite(lane: Int, messageSupplier: Sequence<Any>): CompletableFuture<Unit> =
            getOutboundHandler(lane)?.enqueueWrite(messageSupplier) ?: completedExceptionally(
                SemiDuplexNoOutboundStreamException("No active outbound stream to write data")
            )

        override fun isActive() = getOutboundHandler()?.ctx != null

        override fun getInboundHandler() = if (streamHandler.stream.isInitiator) otherStreamHandler else streamHandler
        override fun getOutboundHandler() = if (streamHandler.stream.isInitiator) streamHandler else otherStreamHandler

        /** The stream writes to [lane] go over: the lane's own once it is active, the main outbound one otherwise */
        fun getOutboundHandler(lane: Int): StreamHandler? =
            outboundLanes[lane]?.takeIf { it.ctx != null } ?: getOutboundHandler()
    }

    override fun createPeerHandler(streamHandler: StreamHandler) = SDPeerHandler(streamHandler)

    override fun streamAdded(streamHandler: StreamHandler) {
        val stream = streamHandler.stream
        val peerHandler = peers.find { it.peerId == stream.remotePeerId() }
        if (streamHandler.lane != MAIN_LANE) {
            laneStreamAdded(streamHandler, peerHandler as SDPeerHandler?)
        } else if (peerHandler == null) {
            super.streamAdded(streamHandler)
        } else {
            peerHandler as SDPeerHandler
            when {
                peerHandler.otherStreamHandler != null -> {
                    streamHandler.closeAbruptly()
                    throw BadPeerException("Duplicate steam for peer ${peerHandler.peerId}. Closing it silently")
                }
                peerHandler.streamHandler.stream.isInitiator == stream.isInitiator -> {
                    streamHandler.closeAbruptly()
                    throw BadPeerException("Duplicate stream with initiator = ${stream.isInitiator} for peer ${peerHandler.peerId}")
                }
                else -> {
                    peerHandler.otherStreamHandler = streamHandler
                    streamHandler.initPeerHandler(peerHandler)
                }
            }
        }
    }

    private fun laneStreamAdded(streamHandler: StreamHandler, peerHandler: SDPeerHandler?) {
        val lanes = when {
            // Lanes are opened once the main streams are up, so there is no peer only if it is already gone
            peerHandler == null -> null
            streamHandler.stream.isInitiator -> peerHandler.outboundLanes
            else -> peerHandler.inboundLanes
        }
        if (lanes == null || streamHandler.lane in lanes) {
            streamHandler.closeAbruptly()
        } else {
            lanes[streamHandler.lane] = streamHandler
            streamHandler.initPeerHandler(peerHandler!!)
        }
    }

    override fun streamActive(stream: StreamHandler) {
        if (!stream.aborted && stream == (stream.getPeerHandler() as SDPeerHandler).getOutboundHandler()) {
            // invoke streamActive only when outbound handler is activated
            super.streamActive(stream)
        }
    }

    override fun streamDisconnected(stream: StreamHandler) {
        if (stream.lane == MAIN_LANE) {
            super.streamDisconnected(stream)
        } else if (!stream.aborted) {
            val peer = stream.getPeerHandler() as SDPeerHandler
            peer.inboundLanes.remove(stream.lane, stream)
            peer.outboundLanes.remove(stream.lane, stream)
        }
    }

    override fun onPeerDisconnected(peer: PeerHandler) {
        // close stream for the same peer
        peer as SDPeerHandler
        if (!peer.streamHandler.closed) peer.streamHandler.ctx?.close()
        if (peer.otherStreamHandler?.closed == false) peer.otherStreamHandler?.ctx?.close()
        (peer.inboundLanes.values + peer.outboundLanes.values).forEach { it.stream.close() }
    }
}
