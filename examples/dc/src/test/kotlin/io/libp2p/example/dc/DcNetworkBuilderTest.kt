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
            .addNodes(count = 4, region = EUROPE, bandwidth = Bandwidths.DATACENTER, validatorsPerNode = 500)
            .addNodes(count = 2, region = ASIA, bandwidth = Bandwidths.VPS, validatorsPerNode = 10)
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
            .addNodes(count = 12, bandwidth = Bandwidths.RESIDENTIAL, validatorsPerNode = 1)
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
            .addNodes(
                count = 5,
                bandwidth = Bandwidths.VPS,
                validatorsPerNode = 2,
                regions = listOf(EUROPE, US_EAST)
            )
            .build()

        assertThat(network.nodesIn(EUROPE)).hasSize(3)
        assertThat(network.nodesIn(US_EAST)).hasSize(2)
        assertThat(network.nodesIn(ASIA)).isEmpty()
    }

    @Test
    fun `distributes nodes by region weights without losing any`() {
        val network = DcNetworkBuilder.world()
            .addNodes(
                count = 100,
                regionWeights = mapOf(EUROPE to 0.5, US_EAST to 0.3, ASIA to 0.2),
                bandwidth = Bandwidths.RESIDENTIAL,
                validatorsPerNode = 1
            )
            .build()

        assertThat(network.nodeCount).isEqualTo(100)
        assertThat(network.nodesIn(EUROPE)).hasSize(50)
        assertThat(network.nodesIn(US_EAST)).hasSize(30)
        assertThat(network.nodesIn(ASIA)).hasSize(20)
    }

    @Test
    fun `weighted distribution still totals the requested count when shares do not divide evenly`() {
        val network = DcNetworkBuilder.world()
            .addNodes(
                count = 10,
                regionWeights = mapOf(EUROPE to 1.0, US_EAST to 1.0, ASIA to 1.0),
                bandwidth = Bandwidths.VPS
            )
            .build()

        assertThat(network.nodeCount).isEqualTo(10)
        assertThat(network.nodes.count { it.isValidator }).isZero()
    }

    @Test
    fun `spreads a total validator count across generated nodes`() {
        val network = DcNetworkBuilder.world()
            .addNodesWithTotalValidators(count = 3, bandwidth = Bandwidths.VPS, validatorsTotal = 10)
            .build()

        assertThat(network.validatorCount).isEqualTo(10)
        assertThat(network.nodes.map { it.validatorCount }).containsExactly(4, 3, 3)
    }

    @Test
    fun `builds a regional topology with an access link pair per node`() {
        val network = DcNetworkBuilder.world()
            .addNodes(count = 3, region = EUROPE, bandwidth = Bandwidths.DATACENTER, validatorsPerNode = 1)
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
    fun `rejects invalid input`() {
        assertThatThrownBy {
            DcNetworkBuilder.world().addNodes(count = 0, region = EUROPE, bandwidth = Bandwidths.VPS)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            DcNetworkBuilder.world().addNode(region = EUROPE, bandwidth = Bandwidths.VPS, validators = -1)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            DcNetworkBuilder.world().addNode(region = EUROPE, bandwidth = Bandwidths.VPS, peers = -1)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            DcNetworkBuilder.world()
                .addNode(region = EUROPE, bandwidth = Bandwidths.VPS, attestationSubnetIds = setOf(0, -3))
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            DcNetworkBuilder.world()
                .addNodes(count = 5, regionWeights = mapOf(EUROPE to 0.0), bandwidth = Bandwidths.VPS)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy { DcNetworkBuilder.world().build() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `summary reports the generated population`() {
        val network = DcNetworkBuilder.world()
            .addNodes(count = 2, region = EUROPE, bandwidth = Bandwidths.DATACENTER, validatorsPerNode = 100)
            .addNodes(count = 6, bandwidth = Bandwidths.RESIDENTIAL, validatorsPerNode = 1)
            .build()

        println(network.summary())
        assertThat(network.summary())
            .contains("nodes=8 validators=206")
            .contains("EUROPE")
    }

    @Test
    fun `records peer count and attestation subnets per node`() {
        val network = DcNetworkBuilder.world()
            .addNodes(
                count = 2,
                region = EUROPE,
                bandwidth = Bandwidths.DATACENTER,
                validatorsPerNode = 100,
                peers = 5,
                attestationSubnetIds = setOf(0, 1, 2)
            )
            .addNodes(
                count = 4,
                bandwidth = Bandwidths.RESIDENTIAL,
                validatorsPerNode = 1,
                peers = 3,
                attestationSubnetIds = setOf(2)
            )
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
            .addNodesWithSubnets(
                count = 8,
                bandwidth = Bandwidths.VPS,
                subnetIdsOf = { index -> setOf(index % subnetCount) },
                validatorsPerNode = 1
            )
            .build()

        assertThat(network.attestationSubnetIds()).containsExactly(0, 1, 2, 3)
        assertThat(network.subscribersPerSubnet().values).allMatch { it == 2 }
        assertThat(network.node(0).attestationSubnetIds).containsExactly(0)
        assertThat(network.node(5).attestationSubnetIds).containsExactly(1)
    }

    @Test
    fun `clamps peer count to the number of other nodes`() {
        val network = DcNetworkBuilder.world()
            .addNodes(count = 3, region = EUROPE, bandwidth = Bandwidths.VPS, peers = 50)
            .build()

        assertThat(network.nodes.map { it.peerCount }).containsExactly(2, 2, 2)
    }

    @Test
    fun `defaults to no subnets and the default peer count`() {
        val network = DcNetworkBuilder.world()
            .addNodes(count = 40, bandwidth = Bandwidths.RESIDENTIAL, validatorsPerNode = 1)
            .build()

        assertThat(network.attestationSubnetIds()).isEmpty()
        assertThat(network.nodes.map { it.peerCount }.distinct())
            .containsExactly(DcNetworkBuilder.DEFAULT_PEER_COUNT)
    }
}
