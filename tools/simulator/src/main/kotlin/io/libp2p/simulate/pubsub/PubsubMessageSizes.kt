package io.libp2p.simulate.pubsub

import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.types.toLongBigEndian
import io.libp2p.simulate.stats.collect.gossip.SimMessageId
import io.libp2p.simulate.util.MsgSizeEstimator
import pubsub.pb.Rpc
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MessageBodyGenerator(
    val messageIdRetriever: (ByteArray) -> SimMessageId,
    val messageSizeRetriever: (ByteArray) -> Int,
    val msgGenerator: (messageId: SimMessageId, size: Int) -> ByteArray
)

class PubsubMessageSizes(
    val sizeEstimator: MsgSizeEstimator,
    val messageBodyGenerator: MessageBodyGenerator
)

fun MessageBodyGenerator.createGenericPubsubMessageSizes() =
    PubsubMessageSizes(
        genericPubSubMsgSizeEstimator(this.messageSizeRetriever),
        this
    )


private fun genericPubSubMsgSizeEstimator(
    publishDataSizeEstimator: (ByteArray) -> Int,
): MsgSizeEstimator = MsgSizeEstimator { msg: Any ->
    val payloadSize = (msg as Rpc.RPC).run {
        subscriptionsList.sumOf { it.topicid.length + 2 } +
            control.graftList.sumOf { it.topicID.length + 1 } +
            control.pruneList.sumOf { it.topicID.length + 1 } +
            control.ihaveList.flatMap { it.messageIDsList }.sumOf { it.size() + 1 } +
            control.iwantList.flatMap { it.messageIDsList }.sumOf { it.size() + 1 } +
            publishList.sumOf { publishDataSizeEstimator(it.data.toByteArray()) + it.topicIDsList.sumOf { it.length } + 224 } +

            erasureSampleList.sumOf { publishDataSizeEstimator(it.data.toByteArray()) + it.messageID.size() + 32 * 2 } +
            control.erasureHeaderList.sumOf { publishDataSizeEstimator(it.data.toByteArray()) + it.messageID.size() + it.topicID.length + 32 * 2 } +
            control.erasureAckList.sumOf { it.messageID.size() + 32 * 2 } +
            6
    }
    (payloadSize + ((payloadSize / 1460) + 1) * 40).toLong()
}

private fun generateIdBytes(id: Long): ByteArray = id.toBytesBigEndian()
private fun readIdBytes(bytes: ByteArray): Long = bytes.toLongBigEndian()

fun averageSizeMessageBodyGenerator(avrgMsgLen: Int) =
    MessageBodyGenerator(
        { readIdBytes(it) },
        { avrgMsgLen },
        { id, _ -> generateIdBytes(id) }
    )

val strictMessageBodyGenerator =
    MessageBodyGenerator(
        { bytes -> readIdBytes(bytes.copyOfRange(0, 8)) },
        { it.size },
        { id, size -> generateIdBytes(id) + ByteArray(size) }
    )

val trickyMessageBodyGenerator =
    MessageBodyGenerator(
        { bytes -> readIdBytes(bytes.copyOfRange(0, 8)) },
        { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).getInt(8) },
        { id, size -> generateIdBytes(id) + size.toBytesBigEndian() }
    )
