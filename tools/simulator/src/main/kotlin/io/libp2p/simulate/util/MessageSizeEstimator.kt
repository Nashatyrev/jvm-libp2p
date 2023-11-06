package io.libp2p.simulate.util

import io.netty.buffer.ByteBuf

fun interface MsgSizeEstimator {
    fun estimateSize(msg: Any): Long
}
val GeneralSizeEstimator: MsgSizeEstimator = MsgSizeEstimator { msg ->
    when (msg) {
        is ByteBuf -> msg.readableBytes().toLong()
        else -> 0
    }
}
