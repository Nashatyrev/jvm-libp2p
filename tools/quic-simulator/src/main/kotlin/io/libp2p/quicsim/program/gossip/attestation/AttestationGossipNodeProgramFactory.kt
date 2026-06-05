package io.libp2p.quicsim.program.gossip.attestation

import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipScoreParams
import io.libp2p.quicsim.program.NodeProgram
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.scenario.QuicScenarioEvent
import io.libp2p.quicsim.scenario.QuicScenarioEventSink
import io.libp2p.quicsim.scenario.QuicScenarioEventSource
import io.libp2p.quicsim.scenario.RecordingQuicScenarioEventSink
import io.libp2p.quicsim.sim.SimNodeId

class AttestationGossipNodeProgramFactory(
    private val nodeCount: Int,
    private val connectionsByNode: Map<SimNodeId, List<SimNodeId>>,
    private val nodeConfigs: Map<SimNodeId, AttestationGossipNodeConfig>,
    private val params: GossipParams = GossipParams(),
    private val scoreParams: GossipScoreParams = GossipScoreParams(),
    private val randomSeed: (SimNodeId) -> Long = { it.toLong() },
    private val eventSink: QuicScenarioEventSink = RecordingQuicScenarioEventSink(),
) : NodeProgramFactory, QuicScenarioEventSource {
    init {
        require(nodeCount > 0) { "nodeCount must be positive" }
        require(nodeConfigs.keys == (0 until nodeCount).toSet()) {
            "nodeConfigs must include exactly node ids 0 until $nodeCount"
        }
        require(nodeConfigs.values.map { it.topicName }.toSet().size == 1) {
            "all nodes must subscribe to the same attestation aggregate topic"
        }
        connectionsByNode.forEach { (nodeId, peers) ->
            require(nodeId in 0 until nodeCount) { "Unknown node id $nodeId in connectionsByNode" }
            peers.forEach { peer ->
                require(peer in 0 until nodeCount) { "Unknown peer id $peer for node $nodeId" }
                require(peer != nodeId) { "Node $nodeId must not connect to itself" }
            }
        }
    }

    override fun createNode(id: SimNodeId): NodeProgram {
        require(id in 0 until nodeCount) { "Node id $id is outside configured range 0 until $nodeCount" }
        val nodeConfig = nodeConfigs.getValue(id)
        return AttestationGossipNodeProgram(
            simNodeId = id,
            connectToNodeIds = connectionsByNode[id].orEmpty(),
            nodeConfig = nodeConfig,
            expectedRemoteAggregates = expectedRemoteAggregates(id),
            params = params,
            scoreParams = scoreParams,
            randomSeed = randomSeed(id),
            eventSink = eventSink,
        )
    }

    override fun events(): List<QuicScenarioEvent> =
        (eventSink as? QuicScenarioEventSource)?.events().orEmpty()

    fun expectedRemoteAggregates(nodeId: SimNodeId): Set<AttestationAggregateKey> =
        nodeConfigs
            .filterKeys { it != nodeId }
            .flatMap { (publisherNodeId, config) ->
                config.aggregators.flatMap { aggregator ->
                    (0 until config.slotCount).map { slotIndex ->
                        AttestationAggregateKey(
                            publisherNodeId = publisherNodeId,
                            aggregatorId = aggregator.aggregatorId,
                            slot = config.firstSlot + slotIndex,
                        )
                    }
                }
            }
            .toSet()
}
