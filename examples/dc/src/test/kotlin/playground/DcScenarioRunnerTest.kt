package playground

import io.libp2p.example.dc.Bandwidths
import io.libp2p.example.dc.DcAttestationConfig
import io.libp2p.example.dc.DcAttestationReport
import io.libp2p.example.dc.DcAttestationScenario
import io.libp2p.example.dc.DcAttestationSchedule
import io.libp2p.example.dc.DcNetworkBuilder
import io.libp2p.example.dc.peerGraph
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.ContinentRegion
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Scenario runners. One `@Test` per scenario file; the file supplies the parameters, the defaults in
 * [io.libp2p.example.dc.DcRunConfig] supply the rest.
 *
 * Tagged `simulation` and excluded from the normal `test` task, since these are studies rather than
 * tests — a 1000-node run is minutes to hours, not seconds.
 *
 * ```
 * ./gradlew :examples:dc:simulation --tests "*DcScenarioRunnerTest.attestation 1000 residential*"
 * ```
 *
 * To vary a run, copy the YAML rather than editing it in place: each file is then a durable record
 * of how a particular result was produced.
 */
@Tag("simulation")
class DcScenarioRunnerTest {

    @Test
    fun `attestation 1000 residential`() {
        val network = DcNetworkBuilder
            .world(
                randomSeed = 1,
                subnetCount = 64
            )
            .addGroup(count = 6) {
                // validator pools
                regionWeights = mapOf(
                    ContinentRegion.EUROPE to 0.4,
                    ContinentRegion.US_EAST to 0.4,
                    ContinentRegion.US_WEST to 0.2,
                )
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 10000
                peers = 200
                allSubnets()
            }
            .addGroup(count = 200) {
                // business
                regionWeights = mapOf(
                    ContinentRegion.EUROPE to 0.4,
                    ContinentRegion.US_EAST to 0.4,
                    ContinentRegion.US_WEST to 0.2,
                )
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 200
                peers = 100
                allSubnets()
            }
//            .addGroup(count = 300) {
//                // home stakers
//                spreadOverRegions()
//                bandwidth = Bandwidths.RESIDENTIAL
//                validators = 10
//                peers = 40
//                randomSubnets(10)
//            }
//            .addGroup(count = 700) {
//                spreadOverRegions()
//                bandwidth = Bandwidths.RESIDENTIAL
//                validators = 0
//                peers = 20
//                randomSubnets(2)
//            }
            .build()

        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 1)
        Assertions.assertThat(graph.subnetDeficiencies()).isEmpty()

        val gossipParams = GossipParams.builder()
            // Mesh-only: disables the lazy IHAVE/IWANT gossip mechanism, leaving plain mesh push
            // (GRAFT/PRUNE) as the only way messages travel. gossipSize = 0 means no message ids are
            // exposed for lazy gossip, so IHAVE (and therefore IWANT) never fire.
            .DLazy(0)
            .gossipFactor(0.0)
            .gossipSize(0)
            .build()

        val attestationConfig = DcAttestationConfig(
            waveCount = 1,
            attestationSizeBytes = 240,
            // ~223MB reaches each node, and a 50 Mbit/s residential link carries 6.25MB/s, so the
            // wave needs ~36s of link time alone. The default 12s settle would cut off around two
            // thirds of it and report the shortfall as undelivered; 150s leaves room for the
            // transfer plus queueing so the latency figures mean something.
            settle = 150.seconds,
            gossipParams = gossipParams,
            randomSeed = 1
        )
        val schedule = DcAttestationSchedule.allValidators(
            network = network,
            waveTimes = attestationConfig.waveTimes,
            randomSeed = 1
        )

        val report = DcAttestationScenario.run(
            network = network,
            graph = graph,
            config = attestationConfig,
            schedule = schedule
        )
        println(report)
    }

    /**
     * One rolling-attestation run: 1024 residential nodes, 1024 validators each, one subnet apiece.
     *
     * [subnetCount] changes how the same total number of attestations is spread: with 1024 nodes,
     * a subnet holds 1024/[subnetCount] nodes and carries 1/[subnetCount] of the traffic, so
     * *fewer* subnets means bigger committees and more bytes per node.
     */
    private fun runRolling(d: Int, subnetCount: Int): DcAttestationReport {
        val network = DcNetworkBuilder
            .world(
                randomSeed = 1,
                subnetCount = subnetCount
            )
            .addGroup(count = 1024) {
                // validator pools
                regionWeights = mapOf(
                    ContinentRegion.EUROPE to 0.4,
                    ContinentRegion.US_EAST to 0.4,
                    ContinentRegion.US_WEST to 0.2,
                )
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 1024
                peers = 30
                // Round-robin rather than randomSubnets(1), which assigns independently and so
                // gives multinomial subnet sizes: at 64 subnets the smallest drew 7 nodes, whose
                // members cannot reach minPeersPerSubnet = 8, and the graph check failed. Even
                // assignment also keeps committee size identical at every sweep point, so a node's
                // load does not depend on which subnet it happened to land in.
                subnetsByIndex { index -> setOf(index % subnetCount) }
            }
            .build()

        val graph = network.peerGraph(minPeersPerSubnet = 8, randomSeed = 1)
        Assertions.assertThat(graph.subnetDeficiencies()).isEmpty()

        val gossipParams = GossipParams.builder()
            .D(d)
            // Pin the mesh to D +- 1 instead of the derived defaults (DLow = D*2/3, DHigh = D*2).
            // The heartbeat only grafts below DLow and prunes above DHigh, so the default band
            // leaves the mesh free to drift up to 2*D on inbound GRAFTs — measured at 8.25 for
            // D = 6, which is where the duplication above D came from. A +-1 band keeps mesh size,
            // and therefore duplication, close to D itself.
            .DLow(d - 1)
            .DHigh(d + 1)
            // Mesh-only: disables the lazy IHAVE/IWANT gossip mechanism, leaving plain mesh push
            // (GRAFT/PRUNE) as the only way messages travel. gossipSize = 0 means no message ids are
            // exposed for lazy gossip, so IHAVE (and therefore IWANT) never fire.
            .DLazy(0)
            .gossipFactor(0.0)
            .gossipSize(0)
            .build()

        val attestationConfig = DcAttestationConfig(
            waveCount = 8,
            // A 32nd of the validator set per wave: one slot's worth, from 32 slots per epoch.
            // Deliberately independent of subnetCount — the 32 here is slots, not subnets — so the
            // sweep publishes the same 262144 attestations at every point and only their spread
            // over subnets changes.
            attestersPerWave = 1024 * 1024 / 32,
            waveInterval = 1.seconds,
            attestationSizeBytes = 240,
            settle = 30.seconds,
            // Waves 0-3 are the transport ramping up, not the protocol: QUIC congestion windows
            // start small and the meshes are still settling, which showed up as a p99 two to three
            // times the steady-state value and a max up to four times it. They stay in the per-wave
            // breakdown, they just do not skew the headline numbers.
            warmupWaves = 4,
            gossipParams = gossipParams,
            randomSeed = 1
        )
        // randomValidators, not allValidators: the latter ignores attestersPerWave and has all
        // 1,048,576 validators attest in every wave, 32x a slot's worth.
        val schedule = DcAttestationSchedule
            .randomValidators(
                network = network,
                waveTimes = attestationConfig.waveTimes,
                attestersPerWave = attestationConfig.attestersPerWave,
                randomSeed = 1
            )

        return DcAttestationScenario.run(
            network = network,
            graph = graph,
            config = attestationConfig,
            schedule = schedule
        )
    }

    @Test
    fun `rolling attestation`() {
        println(runRolling(d = 6, subnetCount = 32))
    }

    /**
     * D x subnetCount sweep over the rolling scenario. D drives mesh degree and therefore
     * duplication; subnetCount drives how concentrated the traffic is. Both move bytes per node, so
     * the interesting question is where latency starts to suffer as either is reduced.
     */
    @Test
    fun `rolling attestation D x subnet sweep`() {
        val summary = mutableListOf<String>()
        listOf(6, 5, 4, 3).forEach { d ->
            listOf(16, 32, 64).forEach { subnets ->
                println("======== D=$d subnets=$subnets ========")
                // A point that cannot even build a valid graph should not discard the other
                // eleven results, but it must still be visible in the summary rather than
                // silently missing.
                summary += try {
                    val report = runRolling(d = d, subnetCount = subnets)
                    println(report)
                    SWEEP_ROW.format(
                        d,
                        subnets,
                        report.mesh?.meanSize ?: 0.0,
                        report.duplicationFactor,
                        report.overall.deliveryRatio * 100,
                        report.overall.p50?.inWholeMilliseconds ?: -1,
                        report.overall.p95?.inWholeMilliseconds ?: -1,
                        report.overall.p99?.inWholeMilliseconds ?: -1,
                        report.overall.max?.inWholeMilliseconds ?: -1,
                        // Per node per wave, so the figure does not move with waveCount or
                        // warmupWaves and stays comparable across runs.
                        report.publishBytesReceivedPerNodePerWave / 1e6
                    )
                } catch (e: Throwable) {
                    println("FAILED D=$d subnets=$subnets: $e")
                    e.printStackTrace()
                    "%2d  %7d  FAILED: %s".format(d, subnets, e.toString().take(120))
                }
                // Each point holds 8.4M deliveries while running; drop them before the next.
                System.gc()
            }
        }
        println("\n======== SWEEP SUMMARY ========")
        println(SWEEP_HEADER)
        summary.forEach(::println)
    }

    companion object {
        private const val SWEEP_HEADER =
            " D  subnets  meshMean  dup    deliv%   p50    p95    p99    max   MB/node/wave"
        private const val SWEEP_ROW =
            "%2d  %7d  %8.2f  %5.2fx %6.2f  %5d  %5d  %5d  %6d  %11.2f"
    }
}
