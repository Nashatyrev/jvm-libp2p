package io.libp2p.quicsim.runner.shadow

import io.libp2p.quicsim.program.NodeProgram
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.scenario.NetworkRegion
import io.libp2p.quicsim.scenario.QuicNetworkTopology
import io.libp2p.quicsim.scenario.QuicScenario
import io.libp2p.quicsim.scenario.QuicScenarios
import io.libp2p.quicsim.sim.SimNodeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.seconds

class ShadowConfigBuilderTest {
    @Test
    fun `builds shadow config for slow start scenario`() {
        val config = ShadowConfigBuilder(
            scenario = QuicScenarios.slowStart(),
            javaPath = Path("/usr/bin/java"),
            classpath = "/tmp/classes:/tmp/libs/lib.jar",
            eventsDir = Path("/tmp/events"),
            listenPortStartRange = 17000
        ).build()

        assertThat(config).contains("stop_time: 100000000000 ns")
        assertThat(config).contains("type: gml")
        assertThat(config).contains("network_node_id: 0")
        assertThat(config).contains("network_node_id: 1")
        assertThat(config).contains("ip_addr: \"11.0.0.1\"")
        assertThat(config).contains("ip_addr: \"11.0.0.2\"")
        assertThat(config).contains("latency \"100000000 ns\"")
        assertThat(config).contains("io.libp2p.quicsim.runner.shadow.ShadowScenarioNode")
        assertThat(config).contains("--events-file")
    }

    @Test
    fun `infinite regional bandwidth does not overflow shadow bits`() {
        val scenario = QuicScenario(
            name = "regional",
            network = QuicNetworkTopology.regional(
                listOf(NetworkRegion.US_EAST, NetworkRegion.US_WEST)
            ),
            maxRunDuration = 1.seconds,
            createNodeProgramFactory = {
                object : NodeProgramFactory {
                    override fun createNode(id: SimNodeId): NodeProgram = error("not used")
                }
            }
        )

        val config = ShadowConfigBuilder(
            scenario = scenario,
            javaPath = Path("/usr/bin/java"),
            classpath = "/tmp/classes",
            eventsDir = Path("/tmp/events"),
            listenPortStartRange = 17000
        ).build()

        assertThat(config).contains("host_bandwidth_down \"${Long.MAX_VALUE} bit\"")
        assertThat(config).doesNotContain("host_bandwidth_down \"-8 bit\"")
    }
}
