package playground

import io.libp2p.example.dc.Bandwidths
import io.libp2p.example.dc.DcAttestationConfig
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
}