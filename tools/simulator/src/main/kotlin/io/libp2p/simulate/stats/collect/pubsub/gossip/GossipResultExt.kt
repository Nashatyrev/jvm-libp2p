package io.libp2p.simulate.stats.collect.pubsub.gossip

import io.libp2p.simulate.stats.collect.pubsub.PubsubMessageResult


fun PubsubMessageResult.getIWantsForPubMessage(msg: PubsubMessageResult.PubMessageWrapper) =
    peerSentMessages[msg.origMsg.receivingPeer]!!
        .iWantMessages
        .filter { iWantMsg ->
            iWantMsg.origMsg.receivingPeer == msg.origMsg.sendingPeer &&
                    msg.simMsgId in iWantMsg.simMsgIds &&
                    iWantMsg.origMsg.receiveTime <= msg.origMsg.sendTime
        }

fun PubsubMessageResult.isByIWantPubMessage(msg: PubsubMessageResult.PubMessageWrapper): Boolean =
    getIWantsForPubMessage(msg).isNotEmpty()

/**
 * Number of messages requested via IWant
 */
val PubsubMessageResult.iWantRequestCount: Int get() = iWantMessages.sumOf { it.simMsgIds.size }

/**
 * Publish messages sent after corresponding IWant request
 */
val PubsubMessageResult.publishesByIWant: List<PubsubMessageResult.PubMessageWrapper> get() =
    publishMessages.filter {
        isByIWantPubMessage(it)
    }

/**
 * Lists publish messages that were sent from one peer to another more than once
 */
val PubsubMessageResult.duplicatePublishes get() =
    publishMessages
        .groupBy {
            Triple(it.simMsgId, it.origMsg.sendingPeer, it.origMsg.receivingPeer)
        }
        .filterValues { it.size > 1 }
        .values

/**
 * Lists publish messages that were sent between two peers forth and back
 */
val PubsubMessageResult.roundPublishes get() =
    publishMessages
        .groupBy {
            Pair(it.simMsgId, setOf(it.origMsg.sendingPeer, it.origMsg.receivingPeer))
        }
        .filterValues {
            it.size > 1 && it.map { it.origMsg.sendingPeer }.distinct().size > 1
        }
        .values

/**
 * Deliveries (first arrived published message) received due to IWant request
 */
fun GossipPubDeliveryResult.getDeliveriesByIWant(msgResult: PubsubMessageResult):
        List<GossipPubDeliveryResult.MessageDelivery> =
    deliveries.filter {
        msgResult.isByIWantPubMessage(it.origGossipMsg)
    }

fun PubsubMessageResult.getGossipPubDeliveryResult() = GossipPubDeliveryResult.fromGossipMessageResult(this)
fun Collection<GossipPubDeliveryResult>.merge() = GossipPubDeliveryResult.merge(this)
