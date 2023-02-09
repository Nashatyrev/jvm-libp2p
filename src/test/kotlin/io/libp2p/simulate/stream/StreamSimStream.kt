package io.libp2p.simulate.stream

import io.libp2p.core.multistream.ProtocolId
import io.libp2p.etc.types.toVoidCompletableFuture
import io.libp2p.simulate.MessageDelayer
import io.libp2p.simulate.SimConnection
import io.libp2p.simulate.SimStream
import io.libp2p.simulate.SimChannel
import java.util.concurrent.CompletableFuture

class StreamSimStream(
    val ch1: StreamNettyChannel,
    val ch2: StreamNettyChannel,
    override val streamInitiator: SimStream.StreamInitiator,
    override val streamProtocol: ProtocolId
) : SimStream {

    override lateinit var connection: SimConnection

    fun setLatency(latency: MessageDelayer) {
        ch1.setLatency(latency)
        ch2.setLatency(latency)
    }
    fun disconnect(): CompletableFuture<Unit> {
        return CompletableFuture.allOf(
            ch1.close().toVoidCompletableFuture(),
            ch2.close().toVoidCompletableFuture()
        ).thenApply { }
    }

    override val initiatorChannel: SimChannel
        get() = TODO("Not yet implemented")
    override val acceptorChannel: SimChannel
        get() = TODO("Not yet implemented")


    companion object {
        fun interConnect(
            ch1: StreamNettyChannel,
            ch2: StreamNettyChannel,
            streamInitiator: SimStream.StreamInitiator,
            streamProtocol: ProtocolId
        ): StreamSimStream {

            ch1.connect(ch2)
            ch2.connect(ch1)
            return StreamSimStream(ch1, ch2, streamInitiator, streamProtocol)
        }
    }
}
