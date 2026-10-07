package playground

import io.libp2p.etc.types.millis
import io.libp2p.example.dc.Bandwidths
import io.libp2p.example.dc.Bandwidths.mbitPerSecond
import io.libp2p.example.dc.DcAttackOutcome
import io.libp2p.example.dc.DcLink
import io.libp2p.example.dc.DcNetwork
import io.libp2p.example.dc.DcNetworkBuilder
import io.libp2p.example.dc.DcPublisherSelection
import io.libp2p.example.dc.DcRunConfig
import io.libp2p.example.dc.DcScenario
import io.libp2p.example.dc.DcSlotMessageConfig
import io.libp2p.example.dc.DcSlotMessageTopics
import io.libp2p.example.dc.DcSlotMessageType
import io.libp2p.example.dc.DcWithholdingAttack
import io.libp2p.example.dc.peerGraph
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.IWantPolicy
import io.libp2p.quicsim.runner.SimulatedQuicScenarioRunner
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.ContinentRegion
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Withholding attack against the IWANT follow-up: a fraction of the network behaves until the
 * attack starts, then keeps announcing (IHAVE) but never passes a payload on, by mesh or by IWANT.
 * Asking every announcer ([IWantPolicy.ALL_ANNOUNCERS]) needs only one honest announcer; asking one
 * at a time ([IWantPolicy.DISCIPLINED]) may spend its time on attackers. This sweeps the attacker
 * share for both and reports what the *honest* nodes got.
 *
 * Population: the 1000 nodes of [DcScenarioRunnerTest]'s `large network` (pools, vps and the three
 * residential tiers, eth ladder D=8/6/12/6, 700 ms heartbeat), with the peer-graph fix. Traffic:
 * the 1 MiB block payload as 64 chunks on 64 subnets, nothing else -- the question is propagation,
 * not congestion. Slot 0 is honest; the attack starts with slot 1 and runs to the end.
 *
 * ```
 * ./gradlew :examples:dc:simulation --tests "*DcWithholdingAttackRunnerTest*" \
 *   -Ddc.attackFractions=0,0.5,0.9 -Ddc.iwant=all_announcers,disciplined -Ddc.attackIHave=eager
 * ```
 *
 *  - `dc.attackFractions`: attacker shares of the whole network (default 0,0.3,0.5,0.7,0.8,0.9)
 *  - `dc.iwant`: policies run by every node, comma-separated (default all_announcers,disciplined)
 *  - `dc.attackIHave`: `eager` -- attackers announce every message to all peers at once -- or
 *    `heartbeat` -- only their router's ordinary IHAVE gossip (default eager)
 *  - `dc.attackSlots`: attacked slots after the honest one (default 2)
 *  - `dc.attackSeeds`: attacker draws per point, comma-separated seeds (default 1)
 *  - `dc.settleSeconds`: how long after the last payload a delivery still counts (default 20)
 */
@Tag("simulation")
class DcWithholdingAttackRunnerTest {

    private fun prop(name: String, default: String) = System.getProperty(name) ?: default

    private fun network(params: GossipParams): DcNetwork<ContinentRegion> = DcNetworkBuilder
        .world(subnetCounts = mapOf(DcSlotMessageType.PAYLOAD_CHUNK to 64))
        .defaults {
            allMessageSubnets(DcSlotMessageType.PAYLOAD_CHUNK)
            gossipParams = params
            validators = 1000
        }
        .addGroup(count = 100) {
            name = "validator-pools"
            regionWeights = mapOf(
                ContinentRegion.EUROPE to 0.4,
                ContinentRegion.US_EAST to 0.4,
                ContinentRegion.US_WEST to 0.2,
            )
            bandwidth = Bandwidths.FAST_DATACENTER
            peers = 200
        }
        .addGroup(count = 600) {
            name = "vps"
            spreadOverRegions()
            bandwidth = Bandwidths.VPS
            peers = 100
        }
        .addGroup(count = 100) {
            name = "upper-home"
            spreadOverRegions()
            bandwidth = DcLink(mbitPerSecond(200), mbitPerSecond(100))
            peers = 100
        }
        .addGroup(count = 100) {
            name = "mid-home"
            spreadOverRegions()
            bandwidth = DcLink(mbitPerSecond(100), mbitPerSecond(50))
            peers = 100
        }
        .addGroup(count = 100) {
            name = "home"
            spreadOverRegions()
            bandwidth = Bandwidths.RESIDENTIAL
            peers = 100
        }
        .build()

    @Test
    fun `withholding attack sweep`() {
        val fractions = prop("dc.attackFractions", "0,0.3,0.5,0.7,0.8,0.9").split(",").map { it.trim().toDouble() }
        val policies = prop("dc.iwant", "all_announcers,disciplined").split(",").map { IWantPolicy.valueOf(it.trim().uppercase()) }
        val eagerIHave = when (val mode = prop("dc.attackIHave", "eager")) {
            "eager" -> true
            "heartbeat" -> false
            else -> error("dc.attackIHave must be eager or heartbeat, got $mode")
        }
        val attackSlots = prop("dc.attackSlots", "2").toInt()
        val seeds = prop("dc.attackSeeds", "1").split(",").map { it.trim().toLong() }
        val settle = prop("dc.settleSeconds", "20").toInt().seconds

        val baseConfig = DcRunConfig(
            slotCount = 1 + attackSlots,
            warmupSlots = 1,
            settle = settle,
            messages = listOf(
                DcSlotMessageConfig(
                    type = DcSlotMessageType.PAYLOAD_CHUNK,
                    sizeBytes = 1024 * 1024 / 64,
                    publishOffset = 1.seconds,
                    publisherGroups = setOf("validator-pools"),
                    publisherSelection = DcPublisherSelection.VALIDATOR_WEIGHTED,
                    messagesPerSlot = 64,
                    topics = DcSlotMessageTopics.Subnets(64)
                )
            )
        )
        val attackedSlots = baseConfig.measuredSlots.toList()
        val attackStartsAt = baseConfig.slotTimes[1]
        println(
            "Withholding sweep: fractions=$fractions policies=$policies eagerIHave=$eagerIHave " +
                "attackedSlots=$attackedSlots attackStartsAt=$attackStartsAt settle=$settle seeds=$seeds"
        )

        val summary = mutableListOf<String>()
        for (policy in policies) {
            val params = GossipParams.builder()
                .D(8).DLow(6).DHigh(12).DLazy(6).heartbeatInterval(700.millis)
                .iWantPolicy(policy)
                .build()
            val network = network(params)
            val graph = network.peerGraph(minPeersPerSubnet = 8)
            Assertions.assertThat(graph.subnetDeficiencies()).isEmpty()
            val schedules = DcScenario.defaultMessageSchedules(network, baseConfig)
            val publishers = schedules.flatMap { it.messages }.map { it.publisherNodeId }.toSet()

            for (fraction in fractions) for (seed in seeds) {
                val attack = DcWithholdingAttack(
                    attackerNodeIds = DcWithholdingAttack.pickAttackers(network, fraction, publishers, seed),
                    startsAt = attackStartsAt,
                    eagerIHave = eagerIHave
                )
                val config = baseConfig.copy(attack = attack)
                val started = System.currentTimeMillis()
                val factory = SimulatedQuicScenarioRunner(
                    latencyWindowParallelism = Runtime.getRuntime().availableProcessors()
                ).run(DcScenario.of(network, graph, config, schedules)).nodeProgramFactory
                val wallSeconds = (System.currentTimeMillis() - started) / 1000

                val outcomes = DcAttackOutcome.of(factory, network, DcSlotMessageType.PAYLOAD_CHUNK, attackedSlots, attack)
                val attackers = factory.programs.mapNotNull { it.withholdingHandler }
                val label = "policy=${policy.name.lowercase()} attackers=${attack.attackerNodeIds.size} " +
                    "(${"%.0f".format(fraction * 100)}%) seed=$seed"
                println("=== $label  [${wallSeconds}s wall]")
                println(
                    "  attackers withheld ${attackers.sumOf { it.withheldPublishes }} publishes, " +
                        "announced ${attackers.sumOf { it.eagerIHaveIds }} ids eagerly"
                )
                outcomes.forEach { println("  $it") }
                outcomes.first().perGroup.forEach { (group, g) ->
                    println(
                        "    $group: payload ${"%.1f%%".format(g.payloadCoverage * 100)} " +
                            "(${g.payloadsComplete}/${g.payloadsExpected}) ${g.payloadLatency ?: "-"}"
                    )
                }
                val all = outcomes.first()
                summary += listOf(
                    policy.name.lowercase(),
                    "%.0f%%".format(fraction * 100),
                    "$seed",
                    "%.2f%%".format(all.coverage * 100),
                    "%.2f%%".format(all.payloadCoverage * 100),
                    all.payloadLatency?.p50?.inWholeMilliseconds?.toString() ?: "-",
                    all.payloadLatency?.p90?.inWholeMilliseconds?.toString() ?: "-",
                    all.payloadLatency?.p99?.inWholeMilliseconds?.toString() ?: "-",
                    all.payloadLatency?.max?.inWholeMilliseconds?.toString() ?: "-",
                    DcAttackOutcome.DEADLINES.joinToString("|") { "%.1f".format(all.payloadCompleteBy.getValue(it) * 100) },
                    "%.2f".format(all.copiesPerMessage)
                ).joinToString(",")
            }
        }
        println("\nSUMMARY (honest nodes, attacked slots)")
        println(
            "policy,attackers,seed,chunk_coverage,payload_coverage,payload_p50_ms,payload_p90_ms," +
                "payload_p99_ms,payload_max_ms,complete_by_${DcAttackOutcome.DEADLINES.joinToString("|") { "${it.inWholeSeconds}s" }},copies_per_chunk"
        )
        summary.forEach(::println)
    }
}
