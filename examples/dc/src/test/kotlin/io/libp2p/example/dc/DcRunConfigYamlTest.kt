package io.libp2p.example.dc

import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.defaultDHigh
import io.libp2p.pubsub.gossip.defaultDLow
import io.libp2p.pubsub.gossip.defaultDOut
import io.libp2p.pubsub.gossip.defaultDScore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DcRunConfigYamlTest {

    @Test
    fun `gossip section is absent by default`() {
        val config = DcRunConfigYaml.parse("")

        assertThat(config.gossip).isEqualTo(DcGossipConfig())
        assertThat(config.gossip.describe()).isEqualTo("defaults")
        // an all-default config resolves to the plain Gossip 1.1 defaults
        val params = config.gossip.toGossipParams()
        val defaults = GossipParams()
        assertThat(params.D).isEqualTo(defaults.D)
        assertThat(params.DLow).isEqualTo(defaults.DLow)
        assertThat(params.DHigh).isEqualTo(defaults.DHigh)
        assertThat(params.heartbeatInterval).isEqualTo(defaults.heartbeatInterval)
    }

    @Test
    fun `overriding D alone rescales the whole mesh`() {
        val config = DcRunConfigYaml.parse("gossip:\n  D: 12\n")

        assertThat(config.gossip.D).isEqualTo(12)
        assertThat(config.gossip.DLow).isNull()

        val params = config.gossip.toGossipParams()
        // DLow/DHigh/DScore/DOut were left unset, so GossipParams derives them from D=12
        // rather than staying at the D=6 defaults.
        assertThat(params.D).isEqualTo(12)
        assertThat(params.DLow).isEqualTo(defaultDLow(12))
        assertThat(params.DHigh).isEqualTo(defaultDHigh(12))
        assertThat(params.DScore).isEqualTo(defaultDScore(12))
        assertThat(params.DOut).isEqualTo(defaultDOut(12, defaultDLow(12)))
    }

    @Test
    fun `explicit fields override derivation`() {
        val config = DcRunConfigYaml.parse(
            """
            gossip:
              D: 8
              DLow: 4
              DHigh: 12
              heartbeatIntervalSeconds: 0.5
              gossipFactor: 0.1
            """.trimIndent()
        )

        val params = config.gossip.toGossipParams()
        assertThat(params.D).isEqualTo(8)
        assertThat(params.DLow).isEqualTo(4)
        assertThat(params.DHigh).isEqualTo(12)
        assertThat(params.heartbeatInterval).isEqualTo(java.time.Duration.ofMillis(500))
        assertThat(params.gossipFactor).isEqualTo(0.1)
    }

    @Test
    fun `typos in the gossip section are rejected`() {
        assertThatThrownBy { DcRunConfigYaml.parse("gossip:\n  Dlow: 4\n") }
            .hasMessageContaining("Unknown key(s) [Dlow]")
    }

    @Test
    fun `an invalid combination surfaces when the params are actually built`() {
        // DOut must be < DLow, so this is only caught once toGossipParams() runs GossipParams'
        // own validation - the YAML layer does not duplicate that logic.
        val config = DcRunConfigYaml.parse("gossip:\n  DLow: 2\n  DOut: 5\n")

        assertThatThrownBy { config.gossip.toGossipParams() }
            .hasMessageContaining("DOut")
    }

    @Test
    fun `gossip settings feed into the attestation scenario config`() {
        val config = DcRunConfigYaml.parse("gossip:\n  D: 10\n")
        val scenarioConfig = config.attestation.toScenarioConfig(
            seed = config.run.seed,
            gossipParams = config.gossip.toGossipParams()
        )

        assertThat(scenarioConfig.gossipParams.D).isEqualTo(10)
    }
}
