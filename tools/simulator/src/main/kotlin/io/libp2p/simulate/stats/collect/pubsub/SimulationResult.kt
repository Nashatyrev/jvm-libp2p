package io.libp2p.simulate.stats.collect.pubsub

import io.libp2p.simulate.SimPeer
import io.libp2p.simulate.SimPeerId
import io.libp2p.simulate.pubsub.SimMessageDelivery

class SimulationResult(
    val pubsubMessageResult: PubsubMessageResult,
    val apiDeliveries: List<SimMessageDelivery>
) {

    val idToSimPeerMap: Map<SimPeerId, SimPeer> = pubsubMessageResult.allPeers.associateBy { it.simPeerId }

    val apiDeliverDelays = apiDeliveries
        .associate {
            idToSimPeerMap[it.receivingPeer]!! to it.receiveTime - it.message.sentTime
        }

    val messagesPriorToDelivery: PubsubMessageResult by lazy {

        val simPeerToDeliveryTime = apiDeliveries
            .associate {
                idToSimPeerMap[it.receivingPeer]!! to it.receiveTime
            }

        pubsubMessageResult
            .filter {
                it.receiveTime <= (simPeerToDeliveryTime[it.receivingPeer] ?: 0L)
            }
    }
}