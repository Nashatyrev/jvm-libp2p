package io.libp2p.quicsim.host.impl.sim

import io.netty.channel.ChannelId

class SimChannelId(private val id: String) : ChannelId {
    override fun asShortText(): String = id
    override fun asLongText(): String = id
    override fun compareTo(other: ChannelId): Int = asLongText().compareTo(other.asLongText())
}