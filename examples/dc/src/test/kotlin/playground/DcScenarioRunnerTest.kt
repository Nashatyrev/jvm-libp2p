package playground

import io.libp2p.example.dc.Bandwidths
import io.libp2p.example.dc.DcAttestationConfig
import io.libp2p.example.dc.DcAttestationScenario
import io.libp2p.example.dc.DcAttestationSchedule
import io.libp2p.example.dc.DcNetworkBuilder
import io.libp2p.example.dc.peerGraph
import io.libp2p.pubsub.gossip.GossipParams
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

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
            .world(randomSeed = 1, subnetCount = 1)
            .addGroup(count = 1000) {
                spreadOverRegions()
//                region = RegionalNetworkDescriptor.Companion.ContinentRegion.EUROPE
                bandwidth = Bandwidths.DATACENTER
                validators = 2
                peers = 20
                allSubnets()
//                subnets = setOf(0)
            }
            .build()

        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 1)
        Assertions.assertThat(graph.subnetDeficiencies()).isEmpty()

        // Mesh-only: disables the lazy IHAVE/IWANT gossip mechanism, leaving plain mesh push
        // (GRAFT/PRUNE) as the only way messages travel. gossipSize = 0 means no message ids are
        // exposed for lazy gossip, so IHAVE (and therefore IWANT) never fire.
        val gossipParams = GossipParams.builder()
            .DLazy(0)
            .gossipFactor(0.0)
            .gossipSize(0)
            .build()

        val attestationConfig = DcAttestationConfig(
            waveCount = 1,
            attestationSizeBytes = 240,
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
            latencyWindowParallelism = 8,
            schedule = schedule
        )
        println(report)

    }
}