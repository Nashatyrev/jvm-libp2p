package io.libp2p.simulate.main

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.mbitsPerSecond
import io.libp2p.simulate.util.cartesianProduct
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    IdealPubsubSimulation2().runAndPrint()
}

class IdealPubsubSimulation2(
    val bandwidthParams: List<Bandwidth> = listOf(
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
        0.milliseconds,
//        1.milliseconds,
//        5.milliseconds,
//        10.milliseconds,
//        25.milliseconds,
//        50.milliseconds,
//        100.milliseconds,
//        200.milliseconds,
//        500.milliseconds,
//        1000.milliseconds,
    ),

    val msgPartCountParams: List<Int> = listOf(
        1024,
//        SendParams.decoupled(16),
//        SendParams.decoupled(64),
//        SendParams.decoupled(128),
//        SendParams.decoupled(256),
//        SendParams.decoupled(1024),
//        SendParams.decoupledAbsolute(),
    ),
    val messageSizeParams: List<Long> = listOf(5 * 128 * 1024),
//    val messageSizeParams: List<Long> = listOf(1 * 1024 * 1024),
    val nodeCountParams: List<Int> = listOf(
        1024
//        100,
//        500,
//        1000
    ),
    val maxSentParams: List<Int> = listOf(Int.MAX_VALUE),

    val paramsSet: List<IdealPubsub2.SimParams> =
        cartesianProduct(
            nodeCountParams,
            messageSizeParams,
            msgPartCountParams,
            bandwidthParams,
            latencyParams,
            maxSentParams,
            IdealPubsub2::SimParams
        ),
) {

    fun runAndPrint() {
        paramsSet.forEach { params ->
            println("Running: $params")

            val sim = IdealPubsub2(params)
            var totDeliveries = 0
            while (true) {
                val step = sim.nextStep
                val deliveries = sim.simulateNextStep()
                if (deliveries.isEmpty()) break

                totDeliveries += deliveries.size
                println("Step #$step: deliveries: ${deliveries.size}, total: $totDeliveries")
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
        val maxSent: Int = Int.MAX_VALUE,
    )

    private val messagePartCount = params.msgPartCount

    val nodes = List(params.nodeCount) { Node(it) }
    var nextStep = 0

    init {
        nodes[0].messageParts += 0 until messagePartCount
    }

    data class NodeDelivery(
        val from: Node,
        val to: Node,
        val part: MessagePartId,
        val step: Int
    )

    data class Node(val num: Int) {
        val messageParts = mutableSetOf<MessagePartId>()

        val outbounds = mutableListOf<NodeDelivery>()
        val inbounds = mutableListOf<NodeDelivery>()

        fun send(to: Node, part: MessagePartId, step: Int): NodeDelivery {
            require((outbounds.lastOrNull()?.step ?: -1) < step)
            require(part in messageParts)
            val delivery = NodeDelivery(this, to, part, step)
            outbounds += delivery
            to.deliver(delivery)
            return delivery
        }

        fun deliver(delivery: NodeDelivery) {
            require((inbounds.lastOrNull()?.step ?: -1) < delivery.step)
            require(delivery.part !in messageParts)
            inbounds += delivery
            messageParts += delivery.part
        }

        override fun toString(): String {
            return "$num $messageParts"
        }
    }

    private fun calcPartCounts(): List<Int> = nodes
        .fold(MutableList(messagePartCount) { 0 }) { acc, node ->
            node.messageParts.forEach { msgPartId ->
                acc[msgPartId]++
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

    fun simulateNextStep(): List<NodeDelivery> {
        val ret = mutableListOf<NodeDelivery>()

        // total number of message parts across all nodes
        val partCounts = calcPartCounts()

        // message parts sorted by their occurrences in the network
        // (the first is the rarest, the last is the most widespread
        val priorityParts: List<MessagePartId> = partCounts
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
            .filter { it.messageParts.size < messagePartCount }
            .sortedWith(compareBy(
                { it.messageParts.size },
                { nodePartsScore(it.messageParts) },
                { it.num }
            )).toMutableList()

        if (priorityReceiveNodes.isEmpty()) {
            // dissemination is completed
            return emptyList()
        }

        // nodes eligible for sending (having one or more parts)
        val sendingNodes = nodes
            .filter { it.messageParts.isNotEmpty() }
            .toMutableList()

        // disseminate parts starting from the rarest
        for (part in priorityParts) {
            val partReceivingNodes = priorityReceiveNodes.filter { part !in it.messageParts }
            val partSendingNodes = sendingNodes.filter { part in it.messageParts }
            partSendingNodes.zip(partReceivingNodes).forEach { (sendNode, receiveNode) ->
                ret += sendNode.send(receiveNode, part, nextStep)
                sendingNodes -= sendNode
                priorityReceiveNodes -= receiveNode
            }
        }
        nextStep++

        return ret
    }

    fun simulate() {
        while (true) {
            val res = simulateNextStep()
            if (res.isEmpty()) break
        }
    }
}