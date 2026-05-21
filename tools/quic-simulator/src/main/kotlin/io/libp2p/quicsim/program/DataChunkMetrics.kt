package io.libp2p.quicsim.program

import io.libp2p.quicsim.scenario.QuicScenarioEvent

object DataChunkMetrics {
    fun packetReceipts(events: List<QuicScenarioEvent>): List<DataChunkNodeProgramFactory.PacketReceipt> =
        events.filterIsInstance<QuicScenarioEvent.DataChunkPacketReceived>()
            .map {
                DataChunkNodeProgramFactory.PacketReceipt(
                    chunkIndex = it.chunkIndex,
                    sequence = it.sequence,
                    totalPackets = it.totalPackets,
                    payloadBytes = it.payloadBytes,
                    from = it.from,
                    to = it.to,
                    receivedAt = it.at
                )
            }

    fun chunkSends(events: List<QuicScenarioEvent>): List<DataChunkNodeProgramFactory.ChunkSend> =
        events.filterIsInstance<QuicScenarioEvent.DataChunkSent>()
            .map {
                DataChunkNodeProgramFactory.ChunkSend(
                    chunkIndex = it.chunkIndex,
                    from = it.from,
                    to = it.to,
                    sentAt = it.at
                )
            }
}
