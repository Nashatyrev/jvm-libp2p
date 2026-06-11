package io.libp2p.quicsim.program.gossip.attestation

import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipScoreParams
import io.libp2p.quicsim.core.schedule.TimePoint
import io.libp2p.quicsim.scenario.QuicScenarioEvent
import io.libp2p.quicsim.scenario.QuicScenarioEventSink
import io.libp2p.quicsim.sim.NetworkContext
import io.libp2p.quicsim.sim.SimContext
import io.libp2p.quicsim.sim.SimNodeId
import io.netty.buffer.Unpooled
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.function.Consumer

class AttestationGossipNodeProgram(
    simNodeId: SimNodeId,
    connectToNodeIds: List<SimNodeId>,
    private val nodeConfig: AttestationGossipNodeConfig,
    private val expectedRemoteAggregates: Set<AttestationAggregateKey>,
    params: GossipParams,
    scoreParams: GossipScoreParams = GossipScoreParams(),
    randomSeed: Long = 0,
    private val eventSink: QuicScenarioEventSink = QuicScenarioEventSink.Noop,
) : io.libp2p.quicsim.program.GossipNodeProgram(
    simNodeId = simNodeId,
    connectToNodeIds = connectToNodeIds,
    params = params,
    scoreParams = scoreParams,
    randomSeed = randomSeed,
) {
    private val aggregateTopic = Topic(nodeConfig.topicName)
    private val emittedAggregateKeys = ConcurrentHashMap.newKeySet<AttestationAggregateKey>()
    private val receivedAggregateKeys = ConcurrentHashMap.newKeySet<AttestationAggregateKey>()
    private val publishFailures = ConcurrentLinkedQueue<Throwable>()
    private lateinit var epoch: TimePoint

    override fun start(simContext: SimContext, networkContext: NetworkContext): CompletableFuture<Unit> {
        epoch = simContext.timer.time()
        return super.start(simContext, networkContext)
    }

    override fun onAllConnected(simContext: SimContext, networkContext: NetworkContext) {
        messageApi.subscribe(Consumer { msg ->
            AttestationAggregateMessageCodec.decode(msg.data)?.let { aggregate ->
                if (aggregate.publisherNodeId == simNodeId) return@let
                receivedAggregateKeys += aggregate.key
                completeIfReady()
                eventSink.record(
                    QuicScenarioEvent.AttestationAggregateReceived(
                        nodeId = simNodeId,
                        at = now(simContext),
                        publisherNodeId = aggregate.publisherNodeId,
                        aggregatorId = aggregate.aggregatorId,
                        slot = aggregate.slot,
                        attestationPercent = aggregate.attestationPercent,
                        ruleId = aggregate.ruleId,
                    )
                )
            }
        }, aggregateTopic)

        val publisher = messageApi.createPublisher(networkContext.myHost.privKey)
        nodeConfig.aggregators.forEach { aggregatorConfig ->
            AttestationAggregator(
                config = aggregatorConfig,
                slotCount = nodeConfig.slotCount,
                slotDuration = nodeConfig.slotDuration,
                firstSlot = nodeConfig.firstSlot,
                firstSlotDelay = nodeConfig.firstSlotDelay,
                scheduler = simContext.scheduler,
                now = { now(simContext) },
                publishAggregate = { emission ->
                    val aggregate = emission.toMessage()
                    publisher.publish(
                        Unpooled.wrappedBuffer(
                            AttestationAggregateMessageCodec.encode(
                                aggregate,
                                nodeConfig.aggregateMessageSizeBytes
                            )
                        ),
                        aggregateTopic
                    ).whenComplete { _, error ->
                        if (error == null) {
                            emittedAggregateKeys += aggregate.key
                            eventSink.record(aggregate.toPublishedEvent())
                            completeIfReady()
                        } else {
                            publishFailures += error
                            completeFuture.completeExceptionally(error)
                        }
                    }
                },
            ).schedule()
        }
        completeIfReady()
    }

    private fun isComplete(): Boolean {
        publishFailures.peek()?.let { error ->
            throw IllegalStateException("Attestation aggregate publish failed", error)
        }
        return emittedAggregateKeys.size >= nodeConfig.aggregators.size * nodeConfig.slotCount &&
            receivedAggregateKeys.containsAll(expectedRemoteAggregates)
    }

    private fun completeIfReady() {
        runCatching {
            if (isComplete()) {
                completeFuture.complete(Unit)
            }
        }.onFailure {
            completeFuture.completeExceptionally(it)
        }
    }

    fun debugState(): String {
        val emitted = emittedAggregateKeys.sortedWith(aggregateKeyComparator())
        val received = receivedAggregateKeys.sortedWith(aggregateKeyComparator())
        val missing = (expectedRemoteAggregates - receivedAggregateKeys).sortedWith(aggregateKeyComparator())
        return "topic=${aggregateTopic.topic} emitted=$emitted received=${received.size} missing=$missing"
    }

    private fun AttestationAggregateEmission.toMessage(): AttestationAggregateMessage =
        AttestationAggregateMessage(
            publisherNodeId = simNodeId,
            aggregatorId = aggregatorId,
            slot = slot,
            attestationPercent = attestationPercent,
            emittedAt = emittedAt,
            ruleId = ruleId,
        )

    private fun AttestationAggregateMessage.toPublishedEvent(): QuicScenarioEvent.AttestationAggregatePublished =
        QuicScenarioEvent.AttestationAggregatePublished(
            nodeId = simNodeId,
            at = emittedAt,
            aggregatorId = aggregatorId,
            slot = slot,
            attestationPercent = attestationPercent,
            ruleId = ruleId,
        )

    private fun now(simContext: SimContext) = simContext.timer.time() - epoch

    private fun aggregateKeyComparator(): Comparator<AttestationAggregateKey> =
        compareBy({ it.publisherNodeId }, { it.aggregatorId }, { it.slot })
}
