package io.libp2p.simulate.gossip

import com.google.protobuf.ByteString
import io.libp2p.simulate.util.MsgSizeEstimator
import io.netty.buffer.ByteBuf
import pubsub.pb.Rpc
import java.nio.ByteOrder

data class GossipMsgSizeEstimator(
    val estimator: MsgSizeEstimator,
    val msgGenerator: (ByteBuf, size: Int) -> Unit
)

fun genericPubSubMsgSizeEstimator(
    publishDataSizeEstimator: (ByteString) -> Int,
    measureTcpOverhead: Boolean = true
): MsgSizeEstimator = { msg: Any ->
    val payloadSize = (msg as Rpc.RPC).run {
        subscriptionsList.sumBy { it.topicid.length + 2 } +
                control.graftList.sumBy { it.topicID.length + 1 } +
                control.pruneList.sumBy { it.topicID.length + 1 } +
                control.ihaveList.flatMap { it.messageIDsList }.sumBy { it.size() + 1 } +
                control.iwantList.flatMap { it.messageIDsList }.sumBy { it.size() + 1 } +
                publishList.sumBy { publishDataSizeEstimator(it.data) + it.topicIDsList.sumBy { it.length } + 224 } +
                6
    }
    (payloadSize + if (measureTcpOverhead) ((payloadSize / 1460) + 1) * 40 else 0).toLong()
}

fun averagePubSubMsgSizeEstimator(avrgMsgLen: Int, measureTcpOverhead: Boolean = true) =
    GossipMsgSizeEstimator(
        genericPubSubMsgSizeEstimator( { avrgMsgLen }, measureTcpOverhead),
        { _, _ -> }
    )


fun strictPubSubMsgSizeEstimator(measureTcpOverhead: Boolean = true) =
    GossipMsgSizeEstimator(
        genericPubSubMsgSizeEstimator( { it.size() }, measureTcpOverhead),
        { buf, size -> buf.writeBytes(ByteArray(size)) }
    )

fun trickyPubSubMsgSizeEstimator(measureTcpOverhead: Boolean = true) =
    GossipMsgSizeEstimator(
        genericPubSubMsgSizeEstimator(
            { it.asReadOnlyByteBuffer().order(ByteOrder.BIG_ENDIAN).getInt(8) },
            measureTcpOverhead),
        { buf, size -> buf.writeInt(size) }
    )
