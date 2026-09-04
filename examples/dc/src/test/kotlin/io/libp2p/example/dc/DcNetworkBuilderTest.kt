package io.libp2p.example.dc

import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.ContinentRegion
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.ContinentRegion.ASIA
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.ContinentRegion.EUROPE
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.ContinentRegion.US_EAST
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DcNetworkBuilderTest {

    @Test
    fun `generates nodes per region with the requested bandwidth and validators`() {
        val network = DcNetworkBuilder.world()
            .addGroup(count = 4) {
                region = EUROPE
                bandwidth = Bandwidths.DATACENTER
                validators = 500
            }
            .addGroup(count = 2) {
                region = ASIA
                bandwidth = Bandwidths.VPS
                validators = 10
            }
            .build()

        assertThat(network.nodeCount).isEqualTo(6)
        assertThat(network.validatorCount).isEqualTo(4 * 500 + 2 * 10)
        assertThat(network.nodesIn(EUROPE)).hasSize(4)
        assertThat(network.validatorsIn(ASIA)).isEqualTo(20)
        assertThat(network.nodesIn(EUROPE).map { it.bandwidthBytesPerSecond }.distinct())
            .containsExactly(Bandwidths.DATACENTER.bytesPerSecond)
        // sim node ids are dense and index-aligned with the node list
        assertThat(network.nodes.map { it.simNodeId }).isEqualTo((0 until 6).toList())
        assertThat(network.node(0).id).isEqualTo("node-0")
    }

    @Test
    fun `spreads nodes round-robin over regions`() {
        val network = DcNetworkBuilder.world()
            .addGroup(count = 12) {
                spreadOverRegions()
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 1
            }
            .build()

        val regions = ContinentRegion.values().toList()
        assertThat(network.nodeCount).isEqualTo(12)
        assertThat(network.validatorCount).isEqualTo(12)
        regions.forEach { region ->
            assertThat(network.nodesIn(region))
                .describedAs("nodes in $region")
                .hasSize(12 / regions.size)
        }
    }

    @Test
    fun `restricts round-robin to the given regions`() {
        val network = DcNetworkBuilder.world()
            .addGroup(count = 5) {
                spreadOverRegions(EUROPE, US_EAST)
                bandwidth = Bandwidths.VPS
                validators = 2
            }
            .build()

        assertThat(network.nodesIn(EUROPE)).hasSize(3)
        assertThat(network.nodesIn(US_EAST)).hasSize(2)
        assertThat(network.nodesIn(ASIA)).isEmpty()
    }

    @Test
    fun `distributes nodes by region weights without losing any`() {
        val network = DcNetworkBuilder.world()
            .addGroup(count = 100) {
                regionWeights = mapOf(EUROPE to 0.5, US_EAST to 0.3, ASIA to 0.2)
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 1
            }
            .build()

        assertThat(network.nodeCount).isEqualTo(100)
        assertThat(network.nodesIn(EUROPE)).hasSize(50)
        assertThat(network.nodesIn(US_EAST)).hasSize(30)
        assertThat(network.nodesIn(ASIA)).hasSize(20)
    }

    @Test
    fun `weighted distribution still totals the requested count when shares do not divide evenly`() {
        val network = DcNetworkBuilder.world()
            .addGroup(count = 10) {
                regionWeights = mapOf(EUROPE to 1.0, US_EAST to 1.0, ASIA to 1.0)
                bandwidth = Bandwidths.VPS
            }
            .build()

        assertThat(network.nodeCount).isEqualTo(10)
        assertThat(network.nodes.count { it.isValidator }).isZero()
    }

    @Test
    fun `spreads a total validator count across generated nodes`() {
        val network = DcNetworkBuilder.world()
            .addGroup(count = 3) {
                bandwidth = Bandwidths.VPS
                validatorsTotal = 10
            }
            .build()

        assertThat(network.validatorCount).isEqualTo(10)
        assertThat(network.nodes.map { it.validatorCount }).containsExactly(4, 3, 3)
    }

    @Test
    fun `builds a regional topology with an access link pair per node`() {
        val network = DcNetworkBuilder.world()
            .addGroup(count = 3) {
                region = EUROPE
                bandwidth = Bandwidths.DATACENTER
                validators = 1
            }
            .build()

        assertThat(network.topology.hosts.map { it.id })
            .isEqualTo(network.nodes.map { it.id })
        assertThat(network.topology.routers).hasSize(ContinentRegion.values().size)
        network.nodes.forEach { node ->
            assertThat(network.topology.links.filter { it.from == node.id })
                .describedAs("outbound access link of ${node.id}")
                .hasSize(1)
            assertThat(network.topology.links.filter { it.to == node.id })
                .describedAs("inbound access link of ${node.id}")
                .hasSize(1)
        }
        assertThat(network.topology.links.filter { it.from == network.node(0).id }.map { it.bandwidthBytesPerSecond })
            .containsExactly(Bandwidths.DATACENTER.bytesPerSecond)
    }

    @Test
    fun `records peer count and attestation subnets per node`() {
        val network = DcNetworkBuilder.world()
            .addGroup(count = 2) {
                region = EUROPE
                bandwidth = Bandwidths.DATACENTER
                validators = 100
                peers = 5
                subnets = setOf(0, 1, 2)
            }
            .addGroup(count = 4) {
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 1
                peers = 3
                subnets = setOf(2)
            }
            .build()

        assertThat(network.nodes.map { it.peerCount }).containsExactly(5, 5, 3, 3, 3, 3)
        assertThat(network.node(0).attestationSubnetIds).containsExactly(0, 1, 2)
        assertThat(network.node(0).subscribesTo(1)).isTrue()
        assertThat(network.node(2).subscribesTo(1)).isFalse()
        assertThat(network.attestationSubnetIds()).containsExactly(0, 1, 2)
        assertThat(network.nodesSubscribedTo(2)).hasSize(6)
        assertThat(network.nodesSubscribedTo(0)).hasSize(2)
        assertThat(network.subscribersPerSubnet()).isEqualTo(mapOf(0 to 2, 1 to 2, 2 to 6))
    }

    @Test
    fun `assigns subnets per node within a group`() {
        val subnetCount = 4
        val network = DcNetworkBuilder.world()
            .addGroup(count = 8) {
                bandwidth = Bandwidths.VPS
                validators = 1
                subnetsByIndex { index -> setOf(index % subnetCount) }
            }
            .build()

        assertThat(network.attestationSubnetIds()).containsExactly(0, 1, 2, 3)
        assertThat(network.subscribersPerSubnet().values).allMatch { it == 2 }
        assertThat(network.node(0).attestationSubnetIds).containsExactly(0)
        assertThat(network.node(5).attestationSubnetIds).containsExactly(1)
    }

    @Test
    fun `clamps peer count to the number of other nodes`() {
        val network = DcNetworkBuilder.world()
            .addGroup(count = 3) {
                region = EUROPE
                bandwidth = Bandwidths.VPS
                peers = 50
            }
            .build()

        assertThat(network.nodes.map { it.peerCount }).containsExactly(2, 2, 2)
    }

    @Test
    fun `defaults to no subnets and the default peer count`() {
        val network = DcNetworkBuilder.world()
            .addGroup(count = 40) {
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 1
            }
            .build()

        assertThat(network.attestationSubnetIds()).isEmpty()
        assertThat(network.nodes.map { it.peerCount }.distinct())
            .containsExactly(DcNodeGroup.DEFAULT_PEER_COUNT)
    }

    @Test
    fun `adds a single node`() {
        val network = DcNetworkBuilder.world()
            .addNode {
                region = ASIA
                bandwidth = Bandwidths.VPS
                validators = 7
            }
            .build()

        assertThat(network.nodeCount).isEqualTo(1)
        assertThat(network.node(0).region).isEqualTo(ASIA)
        assertThat(network.node(0).validatorCount).isEqualTo(7)
        assertThat(network.node(0).peerCount).isZero()
    }

    @Test
    fun `rejects invalid group configuration`() {
        assertThatThrownBy {
            DcNetworkBuilder.world().addGroup(count = 0) { bandwidth = Bandwidths.VPS }
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            DcNetworkBuilder.world().addGroup(count = 1) { validators = 1 }
        }.hasMessageContaining("bandwidth is required")

        assertThatThrownBy {
            DcNetworkBuilder.world().addGroup(count = 1) {
                bandwidth = Bandwidths.VPS
                validators = -1
            }
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            DcNetworkBuilder.world().addGroup(count = 1) {
                bandwidth = Bandwidths.VPS
                peers = -1
            }
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            DcNetworkBuilder.world().addGroup(count = 1) {
                bandwidth = Bandwidths.VPS
                subnets = setOf(0, -3)
            }
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            DcNetworkBuilder.world().addGroup(count = 5) {
                regionWeights = mapOf(EUROPE to 0.0)
                bandwidth = Bandwidths.VPS
            }
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy { DcNetworkBuilder.world().build() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects conflicting group options`() {
        assertThatThrownBy {
            DcNetworkBuilder.world().addGroup(count = 4) {
                region = EUROPE
                spreadOverRegions()
                bandwidth = Bandwidths.VPS
            }
        }.hasMessageContaining("at most one placement option")

        assertThatThrownBy {
            DcNetworkBuilder.world().addGroup(count = 4) {
                bandwidth = Bandwidths.VPS
                validators = 1
                validatorsTotal = 10
            }
        }.hasMessageContaining("at most one validator allocation option")

        assertThatThrownBy {
            DcNetworkBuilder.world().addGroup(count = 4) {
                bandwidth = Bandwidths.VPS
                subnets = setOf(1)
                subnetsByIndex { setOf(it) }
            }
        }.hasMessageContaining("at most one subnet assignment option")
    }

    @Test
    fun `groups inherit defaults and may override them`() {
        val network = DcNetworkBuilder.world()
            .defaults {
                bandwidth = Bandwidths.RESIDENTIAL
                peers = 12
                region = ASIA
                subnets = setOf(3)
            }
            // groups are big enough that peer counts are not clamped by the network size
            .addGroup(count = 30) { validators = 1 }
            .addGroup(count = 30) {
                // overrides every inherited option
                region = EUROPE
                bandwidth = Bandwidths.DATACENTER
                peers = 40
                subnets = setOf(7, 8)
                validators = 100
            }
            .build()

        val inherited = network.nodes.take(30)
        assertThat(inherited.map { it.region }.distinct()).containsExactly(ASIA)
        assertThat(inherited.map { it.peerCount }.distinct()).containsExactly(12)
        assertThat(inherited.map { it.bandwidthBytesPerSecond }.distinct())
            .containsExactly(Bandwidths.RESIDENTIAL.bytesPerSecond)
        assertThat(inherited.map { it.attestationSubnetIds }.distinct()).containsExactly(setOf(3))

        val overridden = network.nodes.drop(30)
        assertThat(overridden.map { it.region }.distinct()).containsExactly(EUROPE)
        assertThat(overridden.map { it.peerCount }.distinct()).containsExactly(40)
        assertThat(overridden.map { it.validatorCount }.distinct()).containsExactly(100)
        assertThat(overridden.map { it.attestationSubnetIds }.distinct()).containsExactly(setOf(7, 8))
    }

    @Test
    fun `defaults calls layer on top of each other`() {
        val network = DcNetworkBuilder.world()
            .defaults { bandwidth = Bandwidths.VPS; peers = 7 }
            .defaults { validators = 3 }
            .addGroup(count = 20)
            .build()

        assertThat(network.nodes.map { it.peerCount }.distinct()).containsExactly(7)
        assertThat(network.nodes.map { it.validatorCount }.distinct()).containsExactly(3)
        assertThat(network.nodes.map { it.bandwidthBytesPerSecond }.distinct())
            .containsExactly(Bandwidths.VPS.bytesPerSecond)
    }

    @Test
    fun `withDefaults scopes to its block and nests`() {
        val network = DcNetworkBuilder.world()
            .defaults { bandwidth = Bandwidths.RESIDENTIAL; peers = 1 }
            .withDefaults({ peers = 2; region = EUROPE }) {
                addGroup(count = 1) { validators = 1 }
                withDefaults({ peers = 3 }) {
                    addGroup(count = 1) { validators = 2 }
                }
                addGroup(count = 1) { validators = 3 }
            }
            .addGroup(count = 1) { validators = 4 }
            .build()

        // inner scopes override, and the outer defaults come back afterwards
        assertThat(network.nodes.map { it.peerCount }).containsExactly(2, 3, 2, 1)
        assertThat(network.nodes.map { it.validatorCount }).containsExactly(1, 2, 3, 4)
        assertThat(network.nodes.map { it.region }).containsExactly(EUROPE, EUROPE, EUROPE, US_EAST)
    }

    @Test
    fun `draws a random subnet subset per node`() {
        val network = DcNetworkBuilder.world(randomSeed = 42)
            .defaults { bandwidth = Bandwidths.RESIDENTIAL }
            .addGroup(count = 50) { randomSubnets(count = 2, of = 64) }
            .build()

        assertThat(network.nodes.map { it.attestationSubnetIds.size }.distinct()).containsExactly(2)
        assertThat(network.attestationSubnetIds()).allMatch { it in 0 until 64 }
        // a 2-of-64 draw over 50 nodes should not collapse onto one subnet
        assertThat(network.attestationSubnetIds().size).isGreaterThan(10)
    }

    @Test
    fun `random subnet draw is reproducible for a given seed`() {
        fun buildWith(seed: Long) = DcNetworkBuilder.world(randomSeed = seed)
            .addGroup(count = 20) {
                bandwidth = Bandwidths.VPS
                randomSubnets(count = 3, of = 32)
            }
            .build()
            .nodes
            .map { it.attestationSubnetIds }

        assertThat(buildWith(7)).isEqualTo(buildWith(7))
        assertThat(buildWith(7)).isNotEqualTo(buildWith(8))
    }

    @Test
    fun `rejects an impossible random subnet draw`() {
        assertThatThrownBy {
            DcNetworkBuilder.world().addGroup(count = 1) {
                bandwidth = Bandwidths.VPS
                randomSubnets(count = 5, of = 4)
            }
        }.hasMessageContaining("randomSubnets count must be in")
    }

    @Test
    fun `randomSubnets defaults its range to the builder's subnet count`() {
        val network = DcNetworkBuilder.world(randomSeed = 1, subnetCount = 8)
            .addGroup(count = 30) {
                bandwidth = Bandwidths.RESIDENTIAL
                randomSubnets(count = 2)
            }
            .build()

        assertThat(network.attestationSubnetIds()).allMatch { it in 0 until 8 }
        assertThat(network.nodes.map { it.attestationSubnetIds.size }.distinct()).containsExactly(2)
    }

    @Test
    fun `rejects a non-positive subnet count`() {
        assertThatThrownBy { DcNetworkBuilder.world(subnetCount = 0) }
            .hasMessageContaining("subnetCount must be > 0")
    }

    @Test
    fun `subscribes a group to every subnet`() {
        val network = DcNetworkBuilder.world(subnetCount = 12)
            .addGroup(count = 3) {
                bandwidth = Bandwidths.DATACENTER
                allSubnets()
            }
            .build()

        network.nodes.forEach { node ->
            assertThat(node.attestationSubnetIds).isEqualTo((0 until 12).toSet())
        }
    }

    @Test
    fun `allSubnets and subnets are mutually exclusive`() {
        assertThatThrownBy {
            DcNetworkBuilder.world().addGroup(count = 1) {
                bandwidth = Bandwidths.VPS
                allSubnets()
                subnets = setOf(1)
            }
        }.hasMessageContaining("Set at most one subnet assignment option")
    }

    @Test
    fun `summary reports the generated population`() {
        val network = DcNetworkBuilder.world()
            .addGroup(count = 2) {
                region = EUROPE
                bandwidth = Bandwidths.DATACENTER
                validators = 100
            }
            .addGroup(count = 6) {
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 1
            }
            .build()

        println(network.summary())
        assertThat(network.summary())
            .contains("nodes=8 validators=206")
            .contains("EUROPE")
    }
}
