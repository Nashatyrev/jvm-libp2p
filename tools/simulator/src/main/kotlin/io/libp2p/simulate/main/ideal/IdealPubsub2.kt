package io.libp2p.simulate.main.ideal

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.mbitsPerSecond
import io.libp2p.simulate.util.cartesianProduct
import java.lang.Integer.max
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    IdealPubsubSimulation2().runAndPrint()
}

val testBandwidth = Bandwidth(1000)
val testParams = IdealPubsub2.SimParams(
    nodeCount = 64,
    messageSize = 10 * 4,
    msgPartCount = 4,
    bandwidth = testBandwidth,
    latency = 10.milliseconds * 3,
//    latency = 0.milliseconds,
)


class IdealPubsubSimulation2(
    val bandwidthParams: List<Bandwidth> = listOf(
//        Bandwidth(1000)
        10.mbitsPerSecond,
//        25.mbitsPerSecond,
//        50.mbitsPerSecond,
//        100.mbitsPerSecond,
//        200.mbitsPerSecond,
//        500.mbitsPerSecond,
//        1024.mbitsPerSecond,
//        (5 * 1024).mbitsPerSecond,
    ),
    val latencyParams: List<Duration> = listOf(
//        0.milliseconds,
//        1.milliseconds,
//        5.milliseconds,
//        10.milliseconds * 3,
//        25.milliseconds,
        50.milliseconds,
//        100.milliseconds,
//        200.milliseconds,
//        500.milliseconds,
//        1000.milliseconds,
    ),

    val msgPartCountParams: List<Int> = listOf(
        32,
    ),
    val messageSizeParams: List<Long> = listOf(
//        10 * 4
        5 * 128 * 1024
    ),
    val nodeCountParams: List<Int> = listOf(
        128
//        100,
//        500,
//        1000
    ),


    val paramsSet: List<IdealPubsub2.SimParams> =
//        listOf(testParams)
        cartesianProduct(
            nodeCountParams,
            messageSizeParams,
            msgPartCountParams,
            bandwidthParams,
            latencyParams,
            IdealPubsub2::SimParams
        ),
) {

    fun runAndPrint() {
        paramsSet.forEach { params ->
            println("Running: $params")

            val sim = IdealPubsub2(params)
            var totSent = 0
            var lastTotDelivered = sim.totalDeliveredCount()
            while (true) {
                val round = sim.curRound
                val sent = sim.simulateNextStep()
                val totDelivered = sim.totalDeliveredCount()

                totSent += sent.size
                println("Step #$round: " +
                        "active: ${sim.activeNodeCount()}, " +
                        "sent: ${sent.size}, " +
                        "delivered: ${totDelivered - lastTotDelivered}, " +
                        "total sent: $totSent")

                println("Delivered: " + sim.countDeliveredParts().toSortedMap().values.joinToString("\t"))
                println("Sent     : " + sim.countSentParts().toSortedMap().values.joinToString("\t"))
                println("Part counts: " + sim.nodes.map { it.deliveredMessageParts.size }.joinToString(" "))

                lastTotDelivered = totDelivered
                if (sim.allDelivered()) break
            }
        }
    }

}

typealias MessagePartId = Int

class IdealPubsub2(
    val params: SimParams
) {
    data class SimParams(
        val nodeCount: Int,
        val messageSize: Long,
        val msgPartCount: Int,
        val bandwidth: Bandwidth,
        val latency: Duration,
    )

    val messagePartCount = params.msgPartCount
    val messagePartSize = params.messageSize / messagePartCount
    val roundDuration = params.bandwidth.getTransmitTime(messagePartSize)
    val latencyRounds = (params.latency / roundDuration).roundToInt()
    val latencyAdjusted = roundDuration * latencyRounds
    val deliveryRounds = 1 + latencyRounds

    val nodes = List(params.nodeCount) { Node(it) }
    var curRound = 0

    init {
        nodes[0].deliveredMessageParts += 0 until messagePartCount
        nodes[0].allMessageParts += nodes[0].deliveredMessageParts
    }

    data class NodeDelivery(
        val from: Node,
        val to: Node,
        val part: MessagePartId,
        val sendRound: Int,
        val deliverRound: Int
    )

    inner class Node(val num: Int) {
        val deliveredMessageParts = mutableSetOf<MessagePartId>()
        val allMessageParts = mutableSetOf<MessagePartId>()

        val outbounds = mutableListOf<NodeDelivery>()
        val inboundsByDeliverRound = mutableListOf<NodeDelivery?>()
        val inbounds get() = inboundsByDeliverRound.filterNotNull()

        fun getPendingDeliveries(round: Int) =
            inboundsByDeliverRound.slice(round + 1 until  inboundsByDeliverRound.size).filterNotNull()
        fun getPartsWithDeliveryDistance(round: Int) =
            inbounds.map {
                it.part to max(0, it.deliverRound - round)
            }.toMap()

        fun onRoundStart(round: Int) {
            val delivery = inboundsByDeliverRound.getOrNull(round)
            if (delivery != null) {
                check(delivery.deliverRound == round)
                check(delivery.part !in deliveredMessageParts)
                deliveredMessageParts += delivery.part
            }
        }

        private fun addInbound(d: NodeDelivery) {
            val round = d.deliverRound
            require(inboundsByDeliverRound.size <= round)
            while (inboundsByDeliverRound.size < round) inboundsByDeliverRound += null
            inboundsByDeliverRound += d
            require(d.part !in allMessageParts)
            allMessageParts += d.part
        }

        fun send(to: Node, part: MessagePartId, curRound: Int): NodeDelivery {
            require((outbounds.lastOrNull()?.sendRound ?: -1) < curRound)
            require(part in deliveredMessageParts)
            val delivery = NodeDelivery(this, to, part, curRound, curRound + deliveryRounds)
            outbounds += delivery
            to.deliver(delivery)
            return delivery
        }

        fun deliver(delivery: NodeDelivery) {
            require((inbounds.lastOrNull()?.sendRound ?: -1) < delivery.sendRound)
            require(delivery.part !in deliveredMessageParts)
            addInbound(delivery)
        }


        override fun toString(): String {
            return "$num $deliveredMessageParts"
        }

        override fun equals(other: Any?) = num == (other as Node).num
        override fun hashCode() = num
    }

    fun activeNodeCount() = nodes.count { it.deliveredMessageParts.isNotEmpty() }

    fun totalDeliveredCount() = nodes.sumOf { it.deliveredMessageParts.size }

    fun countDeliveredParts(): Map<MessagePartId, Int> =
        nodes
            .fold(mutableMapOf<MessagePartId, Int>()) { acc, node ->
                node.deliveredMessageParts.forEach { partId ->
                    acc.compute(partId) { _, existingCount ->
                        1 + (existingCount ?: 0)
                    }
                }
                acc
            }

    fun countSentParts(): Map<MessagePartId, Int> =
        nodes
            .fold(mutableMapOf<MessagePartId, Int>()) { acc, node ->
                node.deliveredMessageParts.forEach { partId ->
                    acc.compute(partId) { _, existingCount ->
                        1 + (existingCount ?: 0)
                    }
                }
                acc
            }

    fun calcSentPartScores(round: Int): MutableMap<MessagePartId, Int> = nodes
        .map { it.getPartsWithDeliveryDistance(round) }
        .map {
            it.mapValues { (_, distance) ->
                deliveryRounds - distance
            }
        }
        .fold(mutableMapOf<MessagePartId, Int>()) { acc, node ->
            node.forEach { partId, score ->
                acc.compute(partId) { _, existingScore ->
                    score + (existingScore ?: 0)
                }
            }
            acc
        }

    private fun List<Int>.transpose(): List<Int> {
        require(this.indices.toSet() == this.toSet())
        val ret = MutableList<Int>(this.size) { 0 }
        for (idx in this.indices) {
            ret[this[idx]] = idx
        }
        return ret
    }

    fun allDelivered() = nodes.all { it.deliveredMessageParts.size == messagePartCount }

    fun simulateNextStep(): List<NodeDelivery> {
        val ret = mutableListOf<NodeDelivery>()

        nodes.forEach { node ->
            node.onRoundStart(curRound)
        }

        // total number of message parts sent (but maybe not yet delivered)
        val existingPartScores = calcSentPartScores(curRound)
        val allPartScores: Map<MessagePartId, Int> =
            (0 until messagePartCount)
                .associateWith { partId -> (existingPartScores[partId] ?: 0) }
                .toSortedMap()

        // message parts sorted by their occurrences in the network
        // (the first is the rarest, the last is the most widespread
        val priorityParts: List<MessagePartId> = allPartScores
            .values
            .withIndex()
            .sortedWith(
                compareBy(
                    { it.value },
                    { it.index },
                )
            )
            .map { it.index }

        // the most widespread part would have the lowes score (less priority)
        val partScores = priorityParts.reversed().transpose()

        // score of a peer according to possessed message parts
        // the rarest parts yields highest score (lower priority)
        // the less a peer has rare parts the more it needs to receive
        fun nodePartsScore(parts: Set<MessagePartId>): Int =
            parts.sumOf { partScores[it] }

        // receiving peers (with missing parts) sorted by their priority
        val priorityReceiveNodes = nodes
            .filter { it.allMessageParts.size < messagePartCount }
            .sortedWith(compareBy(
                { it.allMessageParts.size },
                { nodePartsScore(it.allMessageParts) },
                { it.num }
            )).toMutableList()

        if (!priorityReceiveNodes.isEmpty()) {
            // nodes eligible for sending (having one or more parts)
            val sendingNodes = nodes
                .filter { it.deliveredMessageParts.isNotEmpty() }
                .toMutableList()

            // disseminate parts starting from the rarest
            for (part in priorityParts) {
                val partReceivingNodes = priorityReceiveNodes.filter { part !in it.allMessageParts }
                val partSendingNodes = sendingNodes.filter { part in it.deliveredMessageParts }
                partSendingNodes.zip(partReceivingNodes).forEach { (sendNode, receiveNode) ->
                    ret += sendNode.send(receiveNode, part, curRound)
                    sendingNodes -= sendNode
                    priorityReceiveNodes -= receiveNode
                }
            }
        }

        curRound++

        return ret
    }

    fun simulate() {
        while (true) {
            val res = simulateNextStep()
            if (res.isEmpty()) break
        }
    }
}