package io.libp2p.simulate

import io.libp2p.core.multistream.ProtocolId

interface SimStream {

    enum class StreamInitiator { CONNECTION_DIALER, CONNECTION_LISTENER}

    val connection: SimConnection
    val streamInitiator: StreamInitiator
    val streamProtocol: ProtocolId

    val initiatorOutboundChannel: SimChannel
    val initiatorInboundChannel: SimChannel
}

interface SimChannel {

    val stream: SimStream
    var msgHandler: ((SimChannel, message: Any) -> Unit)?
}