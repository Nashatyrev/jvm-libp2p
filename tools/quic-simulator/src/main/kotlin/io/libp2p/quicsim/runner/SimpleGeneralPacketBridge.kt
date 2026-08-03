package io.libp2p.quicsim.runner

import com.google.common.collect.Comparators.min
import io.libp2p.quicsim.core.ControllablePacketRouter
import io.libp2p.quicsim.core.InOutProcessor
import io.libp2p.quicsim.core.PacketProcessor
import io.libp2p.quicsim.core.schedule.Controllable
import io.libp2p.quicsim.runner.graph.TimedNetworkLink
import io.libp2p.quicsim.runner.graph.TimedNetworkLink.Companion.other
import io.libp2p.quicsim.runner.graph.TimedNetworkVertex
import io.libp2p.quicsim.sim.SimNet
import io.libp2p.quicsim.sim.SimNode
import io.libp2p.quicsim.udpnetwork.RouteResolver
import io.libp2p.quicsim.udpnetwork.UdpSimLink
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork.Companion.findEndpoints
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import io.libp2p.quicsim.udpnetwork.udpSimDestinationNodeId
import io.netty.channel.socket.DatagramPacket
import kotlin.time.Duration

class SimpleGeneralPacketBridge(
    val simNet: SimNet<DatagramPacket>,
    val udpNet: UdpSimNetwork,
    val routeResolver: RouteResolver
) : AbstractSimPacketBridge() {

    abstract class GeneralNode(
        val udpNode: UdpSimNode,
    ) : TimedNetworkVertex, Controllable {
        override val id: String get() = udpNode.id
        override var time: Duration = Duration.ZERO

        override fun equals(other: Any?)=
            id == (other as GeneralNode).id
        override fun hashCode() = id.hashCode()
        override fun toString() = id
    }

    class RouterNode(
        udpNode: UdpSimNode,
    ) : GeneralNode(udpNode), Controllable {
        lateinit var router: ControllablePacketRouter<DatagramPacket>

        override fun advance(advanceDuration: Duration) = router.advance(advanceDuration)
        override fun executePending() = router.executePending()
        override fun nextTaskDuration(): Duration? = router.nextTaskDuration()
    }

    class EndpointNode(
        udpNode: UdpSimNode,
        val simNode: SimNode<DatagramPacket>,
    ) : GeneralNode(udpNode), Controllable {
        lateinit var packetPump: Controllable

        override fun advance(advanceDuration: Duration) = packetPump.advance(advanceDuration)
        override fun executePending() = packetPump.executePending()
        override fun nextTaskDuration(): Duration? = packetPump.nextTaskDuration()
    }

    data class GeneralBidiLink(
        val udpSimLinkL2R: UdpSimLink,
        val udpSimLinkR2L: UdpSimLink,
        override val left: GeneralNode,
        override val right: GeneralNode,
    ) : TimedNetworkLink<GeneralNode> {
        override val latency: Duration =
            min(udpSimLinkL2R.latencyQueue.minimalLatency, udpSimLinkR2L.latencyQueue.minimalLatency)
        val leftProcessor: PacketProcessor<DatagramPacket> = InOutProcessor(udpSimLinkR2L.packetEmitter, udpSimLinkL2R.packetReceiver)
        val rightProcessor: PacketProcessor<DatagramPacket> = InOutProcessor(udpSimLinkL2R.packetEmitter, udpSimLinkR2L.packetReceiver)

        fun processorFor(node: GeneralNode) = when(node) {
            left -> leftProcessor
            right -> rightProcessor
            else -> throw IllegalArgumentException("Unknown node ${node}")
        }
        override fun toString() = "$left <-> $right"
    }

    val endpointNodes = simNet.allNodes.map { simNode ->
        val udpNode = udpNet.nodes.first { it.id == simNode.ip }
        EndpointNode(udpNode, simNode)
    }
    val endpointNodesById = endpointNodes.associateBy { it.udpNode.id }

    val routers = (udpNet.nodes - udpNet.findEndpoints())
        .map { udpNode -> RouterNode(udpNode) }

    val allNodesByUdp = (endpointNodes + routers).associateBy { it.udpNode }

    val bidiLinks = udpNet.links
        .groupBy { setOf(it.from, it.to) }
        .values
        .map { twoLinks ->
            require(twoLinks.size == 2)
            val lNode = allNodesByUdp[twoLinks.first().from]!!
            val rNode = allNodesByUdp[twoLinks.first().to]!!
            GeneralBidiLink(twoLinks[0], twoLinks[1], lNode, rNode)
        }

    val linksByNode =
        bidiLinks.groupBy {it.left.udpNode} + bidiLinks.groupBy {it.right.udpNode}

    init {
        endpointNodes.forEach { initEndpointNode(it) }
        routers.forEach { initRouterNode(it) }
    }

    private fun initEndpointNode(node: EndpointNode) {
        val link = linksByNode[node.udpNode]!!.single()
        node.packetPump = ControllablePacketRouter
            .createSimplePump(node.simNode, link.processorFor(node))
    }

    private fun initRouterNode(node: RouterNode) {
        val links = linksByNode[node.udpNode]!!
        val processors = links.map { it.processorFor(node) }
        val routeNodes = links.map { it.other(node) }
        val routeNodesIndices = routeNodes
            .withIndex()
            .associateBy { it.value.udpNode }
            .mapValues { it.value.index }
        node.router = ControllablePacketRouter(processors) { from, packet ->
            val destNode = endpointNodesById[packet.udpSimDestinationNodeId()]!!
            val nextHopNode = routeResolver.findNextHop(routeNodes[from].udpNode, destNode.udpNode)!!
            routeNodesIndices[nextHopNode]!!
        }
    }

    fun advanceWhile(
        predicate: () -> Boolean,
        afterTimeAdvanced: (Duration) -> Unit = {}
    ) {

    }

    override fun advanceImpl(advanceDuration: Duration) {
    }

    override fun executePending() {
    }

    override fun nextTaskDuration(): Duration? = TODO()
}
