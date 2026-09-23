package io.libp2p.multistream

import io.libp2p.compression.SnappyStreamCompression
import io.libp2p.core.P2PChannel
import io.libp2p.core.P2PChannelHandler
import io.libp2p.core.multistream.Multistream
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolId
import io.libp2p.core.multistream.ProtocolMatcher
import java.time.Duration
import java.util.concurrent.CompletableFuture

class MultistreamImpl<TController>(
    override val bindings: List<ProtocolBinding<TController>>,
    val preHandler: P2PChannelHandler<*>? = null,
    val postHandler: P2PChannelHandler<*>? = null,
    val negotiationTimeLimit: Duration = DEFAULT_NEGOTIATION_TIME_LIMIT,
    /**
     * If set, protocols matching it are offered `/snappy-stream` compressed first (see
     * [SnappyStreamCompression]), and compressed streams are accepted for every local protocol.
     */
    val streamCompression: ProtocolMatcher? = null
) : Multistream<TController> {

    private fun announcedProtocols(): List<ProtocolId> {
        val plain = bindings.flatMap { it.protocolDescriptor.announceProtocols }
        val compress = streamCompression ?: return plain
        return plain.flatMap { if (compress.matches(it)) listOf(SnappyStreamCompression.compressed(it), it) else listOf(it) }
    }

    private fun acceptedProtocols(): List<ProtocolMatcher> {
        val plain = bindings.map { it.protocolDescriptor.protocolMatcher }
        if (streamCompression == null) return plain
        val compressed = object : ProtocolMatcher {
            override fun matches(proposed: ProtocolId) =
                SnappyStreamCompression.uncompressed(proposed)?.let { inner -> plain.any { it.matches(inner) } } ?: false
        }
        return plain + compressed
    }

    override fun initChannel(ch: P2PChannel): CompletableFuture<TController> {
        return with(ch) {
            preHandler?.also {
                it.initChannel(ch)
            }
            pushHandler(
                if (ch.isInitiator) {
                    Negotiator.createRequesterInitializer(
                        negotiationTimeLimit,
                        *announcedProtocols().toTypedArray()
                    )
                } else {
                    Negotiator.createResponderInitializer(
                        negotiationTimeLimit,
                        acceptedProtocols()
                    )
                }
            )
            postHandler?.also {
                it.initChannel(ch)
            }
            val protocolSelect = ProtocolSelect(bindings, streamCompression != null)
            pushHandler(protocolSelect)
            protocolSelect.selectedFuture
        }
    }
}
