package io.libp2p.example.dc

import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.IWantPolicy
import io.libp2p.quicsim.runner.SimulatedQuicScenarioRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class DcWithholdingAttackTest {

    @Test
    fun `attackers stop passing payloads on but keep announcing, and asking everyone still gets through`() {
        val network = DcNetworkBuilder.world(randomSeed = 1, subnetCounts = mapOf(DcSlotMessageType.PAYLOAD_CHUNK to 4))
            .addGroup(count = 40) {
                name = "all"
                spreadOverRegions()
                bandwidth = Bandwidths.VPS
                validators = 1
                peers = 12
                allMessageSubnets(DcSlotMessageType.PAYLOAD_CHUNK)
                gossipParams = GossipParams(iWantPolicy = IWantPolicy.ALL_ANNOUNCERS)
            }
            .build()
        val graph = network.peerGraph(minPeersPerSubnet = 4, randomSeed = 2)
        val base = DcRunConfig(
            slotCount = 2,
            warmup = 15.seconds,
            settle = 8.seconds,
            messages = listOf(
                DcSlotMessageConfig(
                    type = DcSlotMessageType.PAYLOAD_CHUNK,
                    sizeBytes = 4 * 1024,
                    publishOffset = 1.seconds,
                    publisherSelection = DcPublisherSelection.VALIDATOR_WEIGHTED,
                    messagesPerSlot = 8,
                    topics = DcSlotMessageTopics.Subnets(4)
                )
            ),
            randomSeed = 3
        )
        val schedules = DcScenario.defaultMessageSchedules(network, base)
        val publishers = schedules.flatMap { it.messages }.map { it.publisherNodeId }.toSet()
        val attack = DcWithholdingAttack(
            attackerNodeIds = DcWithholdingAttack.pickAttackers(network, 0.5, publishers, randomSeed = 4),
            startsAt = base.slotTimes[1]
        )
        val config = base.copy(attack = attack)

        val factory = SimulatedQuicScenarioRunner()
            .run(DcScenario.of(network, graph, config, schedules))
            .nodeProgramFactory

        val attackers = factory.programs.filter { attack.isAttacker(it.simNodeId) }
        assertThat(attackers).hasSize(20)
        assertThat(attackers.map { it.withholdingHandler }).doesNotContainNull()
        assertThat((factory.programs - attackers.toSet()).map { it.withholdingHandler }).containsOnlyNulls()
        // Honest in slot 0, silent in slot 1.
        assertThat(attackers.sumOf { it.gossipByteCounter.publishMessagesWrittenBySlot[0] ?: 0 }).isPositive()
        assertThat(attackers.sumOf { it.gossipByteCounter.publishMessagesWrittenBySlot[1] ?: 0 }).isZero()
        assertThat(attackers.sumOf { it.withholdingHandler!!.withheldPublishes }).isPositive()
        assertThat(attackers.sumOf { it.withholdingHandler!!.eagerIHaveIds }).isPositive()

        val outcome = DcAttackOutcome.of(factory, network, DcSlotMessageType.PAYLOAD_CHUNK, listOf(1), attack).first()
        println(outcome)
        assertThat(outcome.honestReceivers).isEqualTo(19)
        assertThat(outcome.payloadCoverage).isEqualTo(1.0)
    }
}
