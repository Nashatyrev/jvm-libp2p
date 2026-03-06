package io.libp2p.quicsim.host.impl.sim

import java.net.InetSocketAddress

data class DatagramEnvelope(
    val sender: InetSocketAddress,
    val recipient: InetSocketAddress,
    val bytes: ByteArray
)