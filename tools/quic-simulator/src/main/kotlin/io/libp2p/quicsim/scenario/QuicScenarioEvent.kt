package io.libp2p.quicsim.scenario

import io.libp2p.quicsim.sim.SimNodeId
import java.util.Collections
import kotlin.time.Duration

interface QuicScenarioEventSink {
    fun record(event: QuicScenarioEvent)

    object Noop : QuicScenarioEventSink {
        override fun record(event: QuicScenarioEvent) {}
    }
}

interface QuicScenarioEventSource {
    fun events(): List<QuicScenarioEvent>
}

class RecordingQuicScenarioEventSink : QuicScenarioEventSink, QuicScenarioEventSource {
    private val events = Collections.synchronizedList(mutableListOf<QuicScenarioEvent>())

    override fun record(event: QuicScenarioEvent) {
        events += event
    }

    override fun events(): List<QuicScenarioEvent> = synchronized(events) { events.toList() }
}

sealed class QuicScenarioEvent {
    abstract val nodeId: SimNodeId
    abstract val at: Duration

    data class NodeConnected(
        override val nodeId: SimNodeId,
        override val at: Duration,
        val remoteNodeId: SimNodeId
    ) : QuicScenarioEvent()

    data class DataChunkSent(
        override val nodeId: SimNodeId,
        override val at: Duration,
        val chunkIndex: Int,
        val from: SimNodeId,
        val to: SimNodeId
    ) : QuicScenarioEvent()

    data class DataChunkPacketReceived(
        override val nodeId: SimNodeId,
        override val at: Duration,
        val chunkIndex: Int,
        val sequence: Int,
        val totalPackets: Int,
        val payloadBytes: Int,
        val from: SimNodeId,
        val to: SimNodeId
    ) : QuicScenarioEvent()

    data class GossipMessageReceived(
        override val nodeId: SimNodeId,
        override val at: Duration,
        val publisherNodeId: SimNodeId,
        val messageIndex: Int = 0
    ) : QuicScenarioEvent()

    data class GossipMessagePublished(
        override val nodeId: SimNodeId,
        override val at: Duration,
        val messageIndex: Int = 0
    ) : QuicScenarioEvent()

    data class AttestationAggregatePublished(
        override val nodeId: SimNodeId,
        override val at: Duration,
        val aggregatorId: String,
        val slot: Long,
        val attestationPercent: Double,
        val ruleId: String,
    ) : QuicScenarioEvent()

    data class AttestationAggregateReceived(
        override val nodeId: SimNodeId,
        override val at: Duration,
        val publisherNodeId: SimNodeId,
        val aggregatorId: String,
        val slot: Long,
        val attestationPercent: Double,
        val ruleId: String,
    ) : QuicScenarioEvent()
}
