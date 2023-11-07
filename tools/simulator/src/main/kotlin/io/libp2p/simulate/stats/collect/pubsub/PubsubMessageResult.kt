package io.libp2p.simulate.stats.collect.pubsub

import com.google.protobuf.AbstractMessage
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.MessageId
import io.libp2p.simulate.SimPeer
import io.libp2p.simulate.pubsub.PubsubMessageSizes
import io.libp2p.simulate.stats.collect.CollectedMessage
import io.libp2p.simulate.stats.collect.PubsubMessageId
import io.libp2p.simulate.stats.collect.SimMessageId
import io.libp2p.simulate.stats.collect.gossip.PubsubMessageIdGenerator
import pubsub.pb.Rpc

data class PubsubMessageResult(
    val messages: List<CollectedMessage<Rpc.RPC>>,
    private val msgGenerator: PubsubMessageSizes,
    private val pubsubMessageIdGenerator: PubsubMessageIdGenerator,
    private val pubsubMessageIdToSimMessageIdHint: Map<PubsubMessageId, SimMessageId> = emptyMap()
) {

    interface MessageWrapper<TMessage> {
        val origMsg: CollectedMessage<TMessage>
    }

    data class PubMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.Message>,
        val simMsgId: SimMessageId,
        val gossipMsgId: PubsubMessageId
    ) : MessageWrapper<Rpc.Message>

    data class ErasureSampleMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ErasureSample>,
        val simMsgId: SimMessageId
    ) : MessageWrapper<Rpc.ErasureSample> {
        override fun toString() = "ErasureSample[id:$simMsgId, sampleIndex: '${origMsg.message.sampleIndex}']"
    }

    data class GraftMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ControlGraft>,
    ) : MessageWrapper<Rpc.ControlGraft> {
        override fun toString() = "Graft[$origMsg, topicId: '${origMsg.message.topicID}']"
    }

    data class PruneMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ControlPrune>,
    ) : MessageWrapper<Rpc.ControlPrune> {
        override fun toString() = "Prune[$origMsg, topicId: '${origMsg.message.topicID}']"
    }

    data class IHaveMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ControlIHave>,
        val simMsgId: List<SimMessageId?>
    ) : MessageWrapper<Rpc.ControlIHave> {
        override fun toString() = "IHave[$origMsg, messageIds: $simMsgId]"
    }

    data class IWantMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ControlIWant>,
        val simMsgIds: List<SimMessageId?>
    ) : MessageWrapper<Rpc.ControlIWant> {
        override fun toString() = "IWant[$origMsg, messageIds: $simMsgIds]"
    }

    data class ChokeMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ControlChoke>,
    ) : MessageWrapper<Rpc.ControlChoke> {
        override fun toString() = "Choke[$origMsg, topicId: '${origMsg.message.topicID}']"
    }

    data class ChokeMessageMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ControlChokeMessage>,
        val simMsgId: SimMessageId?
    ) : MessageWrapper<Rpc.ControlChokeMessage> {
        override fun toString() = "ChokeMessage[$origMsg, messageId: '$simMsgId']"
    }

    data class UnChokeMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ControlUnChoke>,
    ) : MessageWrapper<Rpc.ControlUnChoke> {
        override fun toString() = "Unchoke[$origMsg, topicId: '${origMsg.message.topicID}']"
    }

    data class ErasureHeaderMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ErasureHeader>,
        val simMsgId: SimMessageId?
    ) : MessageWrapper<Rpc.ErasureHeader> {
        override fun toString() = "ErasureHeader[$origMsg, messageId: '$simMsgId']"
    }

    data class ErasureAckMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ErasureAck>,
    ) : MessageWrapper<Rpc.ErasureAck> {
        override fun toString() = "ErasureAck[$origMsg]"
    }

    val publishMessages by lazy {
        messages
            .flatMap { collMsg ->
                collMsg.message.publishList.map { pubMsg ->
                    val origMsg = collMsg.withMessage(pubMsg)
                    PubMessageWrapper(
                        origMsg,
                        msgGenerator.messageBodyGenerator.messageIdRetriever(origMsg.message.data.toByteArray()),
                        pubsubMessageIdGenerator(pubMsg)
                    )
                }
            }
    }

    val erasureSampleMessages by lazy {
        messages
            .flatMap { collMsg ->
                collMsg.message.erasureSampleList.map { pubMsg ->
                    val origMsg = collMsg.withMessage(pubMsg)
                    ErasureSampleMessageWrapper(
                        origMsg,
                        msgGenerator.messageBodyGenerator.messageIdRetriever(origMsg.message.data.toByteArray())
                    )
                }
            }
    }

    val graftMessages by lazy {
        flattenControl({ it.graftList }, { GraftMessageWrapper(it) })
    }
    val pruneMessages by lazy {
        flattenControl({ it.pruneList }, { PruneMessageWrapper(it) })
    }
    val iHaveMessages by lazy {
        flattenControl({ it.ihaveList }, {
            IHaveMessageWrapper(
                it,
                it.message.messageIDsList.map {
                    pubsubMessageIdToSimMessageIdMap[it.toWBytes()]
                }
            )
        })
    }
    val iWantMessages by lazy {
        flattenControl({ it.iwantList }, {
            IWantMessageWrapper(
                it,
                it.message.messageIDsList.map {
                    pubsubMessageIdToSimMessageIdMap[it.toWBytes()]
                }
            )
        })
    }
    val chokeMessages by lazy {
        flattenControl({ it.chokeList }, { ChokeMessageWrapper(it) })
    }
    val unchokeMessages by lazy {
        flattenControl({ it.unchokeList }, { UnChokeMessageWrapper(it) })
    }
    val chokeMessageMessages by lazy {
        flattenControl({ it.chokeMessageList }, {
            ChokeMessageMessageWrapper(
                it,
                pubsubMessageIdToSimMessageIdMap[it.message.messageID.toWBytes()]
            )
        })
    }
    val erasureHeaderMessages by lazy {
        flattenControl({ it.erasureHeaderList }, {
            ErasureHeaderMessageWrapper(
                it,
                msgGenerator.messageBodyGenerator.messageIdRetriever(it.message.data.toByteArray())
            )
        })
    }
    val erasureAckMessages by lazy {
        flattenControl({ it.erasureAckList }, { ErasureAckMessageWrapper(it) })
    }

    val allPubsubMessages by lazy {
        (publishMessages + graftMessages + pruneMessages +
                iHaveMessages + iWantMessages +
                chokeMessages + unchokeMessages +
                chokeMessageMessages +
                erasureSampleMessages + erasureHeaderMessages + erasureAckMessages)
            .sortedBy { it.origMsg.sendTime }
    }

    val peerReceivedMessages by lazy {
        messages.groupBy { it.receivingPeer }
            .mapValues { (_, msgs) ->
                copyWithMessages(msgs)
            }
    }
    val peerSentMessages by lazy {
        messages.groupBy { it.sendingPeer }
            .mapValues { (_, msgs) ->
                copyWithMessages(msgs)
            }
    }

    val allPeers by lazy {
        peerSentMessages.keys + peerReceivedMessages.keys
    }
    val allPeersById by lazy {
        allPeers.associateBy { it.simPeerId }
    }

    fun slice(startTime: Long, endTime: Long = Long.MAX_VALUE): PubsubMessageResult =
        copyWithMessages(
            messages.filter { it.sendTime in (startTime until endTime) }
        )

    fun <K> groupBy(keySelectror: (CollectedMessage<Rpc.RPC>) -> K): Map<K, PubsubMessageResult> =
        messages
            .groupBy { keySelectror(it) }
            .mapValues { copyWithMessages(it.value) }

    fun filter(predicate: (CollectedMessage<Rpc.RPC>) -> Boolean): PubsubMessageResult =
        messages
            .filter(predicate)
            .let { copyWithMessages(it) }

    val originatingPublishMessages: Map<SimMessageId, PubMessageWrapper> by lazy {
        publishMessages
            .groupBy { it.simMsgId }
            .mapValues { (_, pubMessages) ->
                pubMessages.first()
            }
    }

    fun findPubMessagePath(peer: SimPeer, msgId: Long): List<PubMessageWrapper> {
        val ret = mutableListOf<PubMessageWrapper>()
        var curPeer = peer
        while (true) {
            val msg = findPubMessageFirst(curPeer, msgId)
                ?: break
            ret += msg
            curPeer = msg.origMsg.sendingPeer
        }
        return ret.reversed()
    }

    fun findPubMessageFirst(peer: SimPeer, msgId: SimMessageId): PubMessageWrapper? =
        publishMessages
            .filter { it.origMsg.receivingPeer === peer && it.simMsgId == msgId }
            .minByOrNull { it.origMsg.receiveTime }

    fun getPeerMessages(peer: SimPeer) =
        ((peerReceivedMessages[peer]?.messages ?: emptyList()) +
                (peerSentMessages[peer]?.messages ?: emptyList()))
            .sortedBy { if (it.sendingPeer == peer) it.sendTime else it.receiveTime }
            .let { copyWithMessages(it) }

    fun getConnectionMessages(peer1: SimPeer, peer2: SimPeer) =
        getPeerMessages(peer1)
            .getPeerMessages(peer2)

    fun getTotalTraffic() = messages
        .sumOf { msgGenerator.sizeEstimator.estimateSize(it.message) }

    fun getTotalMessageCount() = messages.size

    private val pubsubMessageIdToSimMessageIdMap: Map<PubsubMessageId, SimMessageId> by lazy {
        publishMessages.associate { it.gossipMsgId to it.simMsgId } + pubsubMessageIdToSimMessageIdHint
    }

    private fun <TMessage : AbstractMessage, TMsgWrapper : MessageWrapper<TMessage>> flattenControl(
        listExtractor: (Rpc.ControlMessage) -> Collection<TMessage>,
        messageFactory: (CollectedMessage<TMessage>) -> TMsgWrapper
    ): List<TMsgWrapper> =
        messages
            .filter { it.message.hasControl() }
            .flatMap { collMsg ->
                listExtractor(collMsg.message.control).map {
                    messageFactory(
                        collMsg.withMessage(it)
                    )
                }
            }

    private fun copyWithMessages(messages: List<CollectedMessage<Rpc.RPC>>) =
        this.copy(messages = messages, pubsubMessageIdToSimMessageIdHint = pubsubMessageIdToSimMessageIdMap)
}
