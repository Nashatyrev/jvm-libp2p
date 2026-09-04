package io.libp2p.example.dc

import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.ContinentRegion.EUROPE
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DcPeerGraphTest {

    private fun population(nodeCount: Int, peers: Int, subnetsPerNode: Int, subnetCount: Int) =
        DcNetworkBuilder.world(randomSeed = 1)
            .addGroup(count = nodeCount) {
                bandwidth = Bandwidths.RESIDENTIAL
                validators = 1
                this.peers = peers
                randomSubnets(count = subnetsPerNode, of = subnetCount)
            }
            .build()

    @Test
    fun `every subscription gets at least two peers on the same subnet`() {
        val graph = population(nodeCount = 100, peers = 20, subnetsPerNode = 2, subnetCount = 16)
            .peerGraph(minPeersPerSubnet = 2, randomSeed = 5)

        println(graph.summary())
        assertThat(graph.subnetDeficiencies()).isEmpty()
        graph.network.nodes.forEach { node ->
            node.attestationSubnetIds.forEach { subnetId ->
                assertThat(graph.subnetPeersOf(node.simNodeId, subnetId))
                    .describedAs("subnet $subnetId peers of node ${node.simNodeId}")
                    .hasSizeGreaterThanOrEqualTo(2)
            }
        }
    }

    @Test
    fun `honours a higher subnet peer requirement`() {
        val graph = population(nodeCount = 120, peers = 25, subnetsPerNode = 2, subnetCount = 8)
            .peerGraph(minPeersPerSubnet = 4, randomSeed = 5)

        assertThat(graph.subnetDeficiencies()).isEmpty()
        graph.network.nodes.forEach { node ->
            node.attestationSubnetIds.forEach { subnetId ->
                assertThat(graph.subnetPeersOf(node.simNodeId, subnetId))
                    .hasSizeGreaterThanOrEqualTo(4)
            }
        }
    }

    @Test
    fun `edges are symmetric and free of self loops`() {
        val graph = population(nodeCount = 60, peers = 10, subnetsPerNode = 2, subnetCount = 8)
            .peerGraph(randomSeed = 3)

        graph.network.nodes.forEach { node ->
            assertThat(graph.peersOf(node.simNodeId)).doesNotContain(node.simNodeId)
            graph.peersOf(node.simNodeId).forEach { peer ->
                // Asserted on the boolean rather than with contains(): AssertJ's iterable asserts are
                // recursively generic, and Kotlin loses the element type through describedAs(),
                // inferring Nothing for the expected value.
                assertThat(node.simNodeId in graph.peersOf(peer))
                    .describedAs("$peer should know ${node.simNodeId}")
                    .isTrue()
            }
        }
    }

    @Test
    fun `fills up to the requested peer count`() {
        val network = DcNetworkBuilder.world()
            .addGroup(count = 50) {
                bandwidth = Bandwidths.RESIDENTIAL
                peers = 8
            }
            .build()
        val graph = network.peerGraph(randomSeed = 11)

        // no subnets here, so degrees are driven purely by the requested peer counts
        assertThat(graph.network.nodes.map { graph.degree(it.simNodeId) }.distinct())
            .containsExactly(8)
    }

    @Test
    fun `subnet coverage may push a node past its requested peer count`() {
        val network = DcNetworkBuilder.world()
            .addGroup(count = 40) {
                bandwidth = Bandwidths.RESIDENTIAL
                peers = 2
                randomSubnets(count = 4, of = 8)
            }
            .build()
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 2)

        assertThat(graph.subnetDeficiencies()).isEmpty()
        // 4 subnets x 2 peers cannot fit into a 2-peer budget, so degrees exceed it
        assertThat(graph.network.nodes.map { graph.degree(it.simNodeId) }).allMatch { it > 2 }
    }

    @Test
    fun `reports subscriptions that cannot be covered`() {
        val network = DcNetworkBuilder.world()
            .addGroup(count = 1) {
                region = EUROPE
                bandwidth = Bandwidths.VPS
                subnets = setOf(9)
            }
            .addGroup(count = 10) {
                bandwidth = Bandwidths.RESIDENTIAL
                subnets = setOf(1)
            }
            .build()
        val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = 4)

        val deficiencies = graph.subnetDeficiencies()
        assertThat(deficiencies).hasSize(1)
        assertThat(deficiencies.single().simNodeId).isEqualTo(0)
        assertThat(deficiencies.single().subnetId).isEqualTo(9)
        assertThat(deficiencies.single().actualPeers).isZero()
    }

    @Test
    fun `produces a single connected component`() {
        val network = DcNetworkBuilder.world()
            .addGroup(count = 30) {
                region = EUROPE
                bandwidth = Bandwidths.VPS
                peers = 2
                subnets = setOf(1)
            }
            .addGroup(count = 30) {
                bandwidth = Bandwidths.RESIDENTIAL
                peers = 2
                subnets = setOf(2)
            }
            .build()

        assertThat(network.peerGraph(ensureConnected = true, randomSeed = 6).isConnected()).isTrue()
        // the two subnet rings are otherwise disjoint
        assertThat(network.peerGraph(ensureConnected = false, randomSeed = 6).components())
            .hasSizeGreaterThan(1)
    }

    @Test
    fun `dial targets cover every edge exactly once`() {
        val graph = population(nodeCount = 40, peers = 6, subnetsPerNode = 2, subnetCount = 8)
            .peerGraph(randomSeed = 8)

        val dialled = graph.dialTargets().entries.sumOf { it.value.size }
        assertThat(dialled).isEqualTo(graph.edgeCount)
        graph.dialTargets().forEach { (from, targets) ->
            targets.forEach { to -> assertThat(to).isGreaterThan(from) }
        }
    }

    @Test
    fun `holds subnet coverage and peer counts together across seeds`() {
        // The degree fill rewires existing edges, so it must not undo subnet coverage; that only
        // shows up on some seeds, hence the sweep.
        (1L..10L).forEach { seed ->
            val network = population(nodeCount = 80, peers = 12, subnetsPerNode = 2, subnetCount = 12)
            val graph = network.peerGraph(minPeersPerSubnet = 2, randomSeed = seed)

            assertThat(graph.subnetDeficiencies()).describedAs("deficiencies at seed %s", seed).isEmpty()
            assertThat(network.nodes.map { graph.degree(it.simNodeId) })
                .describedAs("degrees at seed %s", seed)
                .allMatch { it >= 12 }
        }
    }

    @Test
    fun `is reproducible for a given seed`() {
        val network = population(nodeCount = 40, peers = 6, subnetsPerNode = 2, subnetCount = 8)

        assertThat(network.peerGraph(randomSeed = 9).adjacency())
            .isEqualTo(network.peerGraph(randomSeed = 9).adjacency())
        assertThat(network.peerGraph(randomSeed = 9).adjacency())
            .isNotEqualTo(network.peerGraph(randomSeed = 10).adjacency())
    }
}
