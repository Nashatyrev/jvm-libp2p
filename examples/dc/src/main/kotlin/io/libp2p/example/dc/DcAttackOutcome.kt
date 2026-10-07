package io.libp2p.example.dc

import io.libp2p.quicsim.sim.SimNodeId
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How one message type fared at the *honest* nodes of a run under [DcWithholdingAttack]: attackers
 * are neither counted as receivers nor as missing ones, since what an attacker holds is no help to
 * anyone.
 *
 * A message type issued as many messages per slot by one publisher -- payload chunks -- is also
 * judged as a whole: a node "has the payload" of a slot once every chunk of that slot has arrived,
 * at the latency of its last chunk.
 */
data class DcAttackOutcome(
    val slot: Int?,
    val honestReceivers: Int,
    /** Honest (message, receiver) pairs expected and delivered. */
    val expected: Int,
    val delivered: Int,
    val messageLatency: Percentiles?,
    /** Honest (slot, node) pairs, and how many of them got every message of their slot. */
    val payloadsExpected: Int,
    val payloadsComplete: Int,
    val payloadLatency: Percentiles?,
    /** Share of honest (slot, node) pairs complete within each of [DEADLINES]. */
    val payloadCompleteBy: Map<Duration, Double>,
    /** Copies of a message an honest node read off the wire, per message it was delivered. */
    val copiesPerMessage: Double,
    val perGroup: Map<String, GroupOutcome>
) {
    val coverage: Double get() = if (expected == 0) 1.0 else delivered.toDouble() / expected
    val payloadCoverage: Double get() = if (payloadsExpected == 0) 1.0 else payloadsComplete.toDouble() / payloadsExpected

    data class GroupOutcome(val payloadsExpected: Int, val payloadsComplete: Int, val payloadLatency: Percentiles?) {
        val payloadCoverage: Double get() = if (payloadsExpected == 0) 1.0 else payloadsComplete.toDouble() / payloadsExpected
    }

    data class Percentiles(val p50: Duration, val p90: Duration, val p99: Duration, val max: Duration) {
        override fun toString(): String = "p50=${ms(p50)} p90=${ms(p90)} p99=${ms(p99)} max=${ms(max)}"

        companion object {
            fun of(samples: List<Duration>): Percentiles? {
                if (samples.isEmpty()) return null
                val sorted = samples.sorted()
                fun at(q: Double) = sorted[(ceil(q * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
                return Percentiles(at(0.50), at(0.90), at(0.99), sorted.last())
            }
        }
    }

    override fun toString(): String = buildString {
        append(if (slot == null) "all attacked slots" else "slot $slot")
        append(": honest=$honestReceivers")
        append(" | messages ${pct(coverage)} ($delivered/$expected) ${messageLatency ?: "-"}")
        append(" | payload ${pct(payloadCoverage)} ($payloadsComplete/$payloadsExpected) ${payloadLatency ?: "-"}")
        append(" | by ")
        append(payloadCompleteBy.entries.joinToString(" ") { (d, share) -> "${d.inWholeSeconds}s=${pct(share)}" })
        append(" | copies/msg=${"%.2f".format(copiesPerMessage)}")
    }

    companion object {
        val DEADLINES: List<Duration> = listOf(1.seconds, 2.seconds, 4.seconds, 8.seconds)

        internal fun ms(d: Duration) = "${d.inWholeMilliseconds}ms"
        internal fun pct(x: Double) = "%.1f%%".format(x * 100)

        /**
         * One outcome per slot in [slots], plus the combined outcome over all of them (slot null) as
         * the first element.
         */
        fun <R> of(
            factory: DcNodeProgramFactory<R>,
            network: DcNetwork<R>,
            type: DcSlotMessageType,
            slots: Collection<Int>,
            attack: DcWithholdingAttack?
        ): List<DcAttackOutcome> {
            val recorder = factory.messageRecorders.getValue(type)
            val isHonest = { id: SimNodeId -> attack?.isAttacker(id) != true }
            val published = recorder.published().map { it.message }.filter { it.slotIndex in slots }
            val latencyOf = recorder.deliveries()
                .filter { it.slotIndex in slots && isHonest(it.receiverNodeId) }
                .associate { (it.messageId to it.receiverNodeId) to it.latency }
            val counters = factory.programs.associate { it.simNodeId to it.gossipByteCounter }

            fun outcome(slot: Int?, inSlots: Set<Int>): DcAttackOutcome {
                val messages = published.filter { it.slotIndex in inSlots }
                // (slot, honest receiver) -> latencies of the messages it got, null for each one it did not
                val perPayload = linkedMapOf<Pair<Int, SimNodeId>, MutableList<Duration?>>()
                messages.forEach { message ->
                    factory.subscribersOf(message)
                        .map { it.simNodeId }
                        .filter { it != message.publisherNodeId && isHonest(it) }
                        .forEach { receiver ->
                            perPayload.getOrPut(message.slotIndex to receiver) { mutableListOf() } +=
                                latencyOf[message.id to receiver]
                        }
                }
                val samples = perPayload.values.flatten()
                val delivered = samples.filterNotNull()
                val payloadLatencies = perPayload.mapValues { (_, got) ->
                    if (got.all { it != null }) got.maxOf { it!! } else null
                }
                val complete = payloadLatencies.values.filterNotNull()
                val receivers = perPayload.keys.map { it.second }.toSet()
                val copies = receivers.sumOf { id ->
                    val bySlot = counters[id]?.publishMessagesReadBySlot.orEmpty()
                    inSlots.sumOf { bySlot[it] ?: 0L }
                }
                val groups = payloadLatencies.entries
                    .groupBy { (key, _) -> network.node(key.second).groupName ?: DcGroupStats.UNNAMED }
                    .mapValues { (_, entries) ->
                        val ok = entries.mapNotNull { it.value }
                        GroupOutcome(entries.size, ok.size, Percentiles.of(ok))
                    }
                return DcAttackOutcome(
                    slot = slot,
                    honestReceivers = receivers.size,
                    expected = samples.size,
                    delivered = delivered.size,
                    messageLatency = Percentiles.of(delivered),
                    payloadsExpected = payloadLatencies.size,
                    payloadsComplete = complete.size,
                    payloadLatency = Percentiles.of(complete),
                    payloadCompleteBy = DEADLINES.associateWith { deadline ->
                        if (payloadLatencies.isEmpty()) 1.0 else complete.count { it <= deadline }.toDouble() / payloadLatencies.size
                    },
                    copiesPerMessage = if (delivered.isEmpty()) 0.0 else copies.toDouble() / delivered.size,
                    perGroup = groups
                )
            }

            val all = outcome(null, slots.toSet())
            return listOf(all) + slots.sorted().map { outcome(it, setOf(it)) }
        }
    }
}
