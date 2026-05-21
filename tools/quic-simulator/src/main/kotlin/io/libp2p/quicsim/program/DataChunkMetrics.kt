package io.libp2p.quicsim.program

import io.libp2p.quicsim.scenario.QuicScenarioEvent
import kotlin.time.Duration.Companion.milliseconds

object DataChunkMetrics {
    data class PacketBatch(
        val chunkIndex: Int,
        val batchIndex: Int,
        val startAt: kotlin.time.Duration,
        val endAt: kotlin.time.Duration,
        val packetCount: Int,
        val payloadBytes: Int
    )

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

    fun packetBatches(
        events: List<QuicScenarioEvent>,
        interBatchGap: kotlin.time.Duration = 100.milliseconds
    ): List<PacketBatch> =
        packetReceipts(events)
            .groupBy { it.chunkIndex }
            .flatMap { (chunkIndex, chunkReceipts) ->
                chunkReceipts
                    .sortedWith(compareBy<DataChunkNodeProgramFactory.PacketReceipt> { it.receivedAt }.thenBy { it.sequence })
                    .fold(mutableListOf<MutableList<DataChunkNodeProgramFactory.PacketReceipt>>()) { batches, receipt ->
                        val previousReceipt = batches.lastOrNull()?.lastOrNull()
                        if (previousReceipt == null || receipt.receivedAt - previousReceipt.receivedAt > interBatchGap) {
                            batches += mutableListOf(receipt)
                        } else {
                            batches.last() += receipt
                        }
                        batches
                    }
                    .mapIndexed { batchIndex, batchReceipts ->
                        PacketBatch(
                            chunkIndex = chunkIndex,
                            batchIndex = batchIndex,
                            startAt = batchReceipts.first().receivedAt,
                            endAt = batchReceipts.last().receivedAt,
                            packetCount = batchReceipts.size,
                            payloadBytes = batchReceipts.sumOf { it.payloadBytes }
                        )
                    }
            }
            .sortedWith(compareBy<PacketBatch> { it.chunkIndex }.thenBy { it.batchIndex })

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
