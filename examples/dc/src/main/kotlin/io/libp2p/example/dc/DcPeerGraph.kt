package io.libp2p.example.dc

import io.libp2p.quicsim.sim.SimNodeId
import kotlin.random.Random

/**
 * Who is connected to whom. Edges are undirected: if `a` is in `peersOf(b)` then `b` is in
 * `peersOf(a)`.
 */
data class DcPeerGraph<R>(
    val network: DcNetwork<R>,
    private val adjacency: List<Set<SimNodeId>>,
    /** Subnet peers each subscriber was asked to have. */
    val minPeersPerSubnet: Int
) {
    val nodeCount: Int get() = adjacency.size

    val edgeCount: Int get() = adjacency.sumOf { it.size } / 2

    fun peersOf(simNodeId: SimNodeId): Set<SimNodeId> = adjacency[simNodeId]

    fun degree(simNodeId: SimNodeId): Int = adjacency[simNodeId].size

    /** Peers of [simNodeId] that also subscribe to [subnetId]. */
    fun subnetPeersOf(simNodeId: SimNodeId, subnetId: Int): Set<SimNodeId> =
        adjacency[simNodeId].filterTo(mutableSetOf()) { network.node(it).subscribesTo(subnetId) }

    /**
     * Full adjacency, both directions. Suitable when every node should know all of its peers.
     */
    fun adjacency(): Map<SimNodeId, List<SimNodeId>> =
        adjacency.indices.associateWith { adjacency[it].sorted() }

    /**
     * Each edge assigned to exactly one of its two ends, so that feeding this to node programs
     * dials every connection once instead of twice.
     */
    fun dialTargets(): Map<SimNodeId, List<SimNodeId>> =
        adjacency.indices.associateWith { from -> adjacency[from].filter { it > from }.sorted() }

    /** Subscriptions that ended up with fewer subnet peers than [minPeersPerSubnet]. */
    fun subnetDeficiencies(): List<SubnetDeficiency> =
        network.nodes.flatMap { node ->
            node.attestationSubnetIds.mapNotNull { subnetId ->
                val actual = subnetPeersOf(node.simNodeId, subnetId).size
                if (actual >= minPeersPerSubnet) {
                    null
                } else {
                    SubnetDeficiency(node.simNodeId, subnetId, actual, minPeersPerSubnet)
                }
            }
        }

    /** Nodes reachable from node 0, i.e. whether the graph is a single component. */
    fun isConnected(): Boolean = componentOf(0).size == nodeCount

    fun components(): List<Set<SimNodeId>> {
        val unvisited = adjacency.indices.toMutableSet()
        val result = mutableListOf<Set<SimNodeId>>()
        while (unvisited.isNotEmpty()) {
            val component = componentOf(unvisited.first())
            result += component
            unvisited -= component
        }
        return result
    }

    private fun componentOf(start: SimNodeId): Set<SimNodeId> {
        val seen = mutableSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            adjacency[queue.removeFirst()].forEach { peer ->
                if (seen.add(peer)) queue.addLast(peer)
            }
        }
        return seen
    }

    fun summary(): String {
        val degrees = adjacency.map { it.size }
        val deficiencies = subnetDeficiencies()
        return buildString {
            appendLine("edges=$edgeCount nodes=$nodeCount connected=${isConnected()}")
            appendLine(
                "degree: min=${degrees.minOrNull()} " +
                    "avg=${"%.1f".format(degrees.average())} max=${degrees.maxOrNull()}"
            )
            appendLine(
                if (deficiencies.isEmpty()) {
                    "every subscription has >= $minPeersPerSubnet subnet peers"
                } else {
                    "under-covered subscriptions: ${deficiencies.size} (e.g. ${deficiencies.first()})"
                }
            )
        }
    }

    /** A node subscribed to a subnet without enough peers on that same subnet. */
    data class SubnetDeficiency(
        val simNodeId: SimNodeId,
        val subnetId: Int,
        val actualPeers: Int,
        val requiredPeers: Int
    )
}

/**
 * Wires up the connection graph for this population.
 *
 * Three passes, in order of how hard the constraint is:
 * 1. **Subnet coverage.** Subscribers of each subnet are arranged in a shuffled ring (a circulant
 *    graph for [minPeersPerSubnet] above 2), giving every subscriber at least [minPeersPerSubnet]
 *    peers on that subnet. This runs first because it is the only requirement that cannot be
 *    satisfied by adding arbitrary edges later.
 * 2. **Degree fill.** Nodes still below their [DcNode.peerCount] are paired up at random with other
 *    nodes that are also below theirs.
 * 3. **Connectivity.** If [ensureConnected], separate components are chained together so the
 *    gossip network is not split in two.
 *
 * Subnet coverage takes precedence over [DcNode.peerCount]: a node subscribed to many subnets can
 * finish above its target degree. Where coverage is impossible — a subnet with fewer than
 * `minPeersPerSubnet + 1` subscribers — the graph reports it through
 * [DcPeerGraph.subnetDeficiencies] rather than failing, since that is a property of the population
 * rather than of the wiring.
 *
 * Everything is driven by a seeded RNG, so a given population and seed always produce the same
 * graph.
 */
fun <R> DcNetwork<R>.peerGraph(
    minPeersPerSubnet: Int = 2,
    ensureConnected: Boolean = true,
    randomSeed: Long = 0
): DcPeerGraph<R> {
    require(minPeersPerSubnet >= 0) { "minPeersPerSubnet must be >= 0, got $minPeersPerSubnet" }
    val random = Random(randomSeed)
    val adjacency = List(nodeCount) { mutableSetOf<SimNodeId>() }

    fun connect(a: SimNodeId, b: SimNodeId): Boolean {
        if (a == b || b in adjacency[a]) return false
        adjacency[a] += b
        adjacency[b] += a
        return true
    }

    fun disconnect(a: SimNodeId, b: SimNodeId) {
        adjacency[a] -= b
        adjacency[b] -= a
    }

    // Edges that subnet coverage relies on. The degree repair below rewires existing edges, and
    // must not undo the guarantee established here.
    val subnetEdges = mutableSetOf<Pair<SimNodeId, SimNodeId>>()
    fun protect(a: SimNodeId, b: SimNodeId) {
        subnetEdges += if (a < b) a to b else b to a
    }

    // 1. subnet coverage
    if (minPeersPerSubnet > 0) {
        attestationSubnetIds().forEach { subnetId ->
            val subscribers = nodesSubscribedTo(subnetId).map { it.simNodeId }.shuffled(random)
            if (subscribers.size < 2) return@forEach
            // Connecting each subscriber to the next `span` around the ring gives it 2*span subnet
            // peers, capped by how many subscribers there are.
            val span = ((minPeersPerSubnet + 1) / 2).coerceIn(1, (subscribers.size - 1).coerceAtLeast(1))
            subscribers.indices.forEach { index ->
                (1..span).forEach { step ->
                    val peer = subscribers[(index + step) % subscribers.size]
                    connect(subscribers[index], peer)
                    protect(subscribers[index], peer)
                }
            }
        }
    }

    // 2. fill up to the requested peer counts
    fun wants(simNodeId: SimNodeId) = node(simNodeId).peerCount - adjacency[simNodeId].size
    val pending = ArrayDeque(nodes.map { it.simNodeId }.filter { wants(it) > 0 }.shuffled(random))
    while (pending.isNotEmpty()) {
        val from = pending.removeFirst()
        if (wants(from) <= 0) continue
        val candidates = nodes.asSequence()
            .map { it.simNodeId }
            .filter { it != from && it !in adjacency[from] && wants(it) > 0 }
            .toList()
        if (candidates.isEmpty()) continue
        val to = candidates[random.nextInt(candidates.size)]
        connect(from, to)
        if (wants(from) > 0) pending.addLast(from)
    }

    // 2b. repair what greedy pairing could not place. Left alone, a handful of nodes end up one or
    // two peers short simply because the others still wanting peers are already connected to them.
    // Rewiring an unrelated edge onto the short node fixes that without disturbing anyone's degree.
    fun edgesShuffled(): List<Pair<SimNodeId, SimNodeId>> =
        adjacency.indices
            .flatMap { from -> adjacency[from].filter { it > from }.map { from to it } }
            .filterNot { it in subnetEdges }
            .shuffled(random)

    var repaired = true
    while (repaired) {
        repaired = false
        nodes.map { it.simNodeId }.filter { wants(it) > 0 }.forEach { short ->
            val need = wants(short)
            if (need <= 0) return@forEach
            val usable = { other: SimNodeId -> other != short && other !in adjacency[short] }
            if (need >= 2) {
                // steal a whole edge: both ends become peers of `short`, their degrees are unchanged
                val edge = edgesShuffled().firstOrNull { (x, y) -> usable(x) && usable(y) }
                    ?: return@forEach
                disconnect(edge.first, edge.second)
                connect(short, edge.first)
                connect(short, edge.second)
            } else {
                // Only room for one. Preferably take an edge whose far end is above its own target
                // anyway, so dropping it brings that node back down instead of leaving a new hole.
                val overshoot = edgesShuffled().firstNotNullOfOrNull { (x, y) ->
                    when {
                        usable(x) && wants(y) < 0 -> Triple(x, y, x)
                        usable(y) && wants(x) < 0 -> Triple(x, y, y)
                        else -> null
                    }
                }
                if (overshoot != null) {
                    disconnect(overshoot.first, overshoot.second)
                    connect(short, overshoot.third)
                } else {
                    // Otherwise pair up with another node that is also short — typically the two are
                    // one peer apart and already connected, which is why greedy could not help them.
                    // Splitting one stolen edge between them tops up both at no cost to anyone else.
                    val partner = nodes.map { it.simNodeId }
                        .firstOrNull { it != short && wants(it) > 0 } ?: return@forEach
                    val partnerUsable = { other: SimNodeId -> other != partner && other !in adjacency[partner] }
                    val split = edgesShuffled().firstNotNullOfOrNull { (x, y) ->
                        when {
                            usable(x) && partnerUsable(y) -> Triple(x, y, true)
                            usable(y) && partnerUsable(x) -> Triple(x, y, false)
                            else -> null
                        }
                    } ?: return@forEach
                    val (x, y, forward) = split
                    disconnect(x, y)
                    connect(short, if (forward) x else y)
                    connect(partner, if (forward) y else x)
                }
            }
            repaired = true
        }
    }

    // 3. one component
    if (ensureConnected && nodeCount > 1) {
        val graph = DcPeerGraph(this, adjacency.map { it.toSet() }, minPeersPerSubnet)
        val components = graph.components()
        components.zipWithNext { left, right ->
            connect(left.random(random), right.random(random))
        }
    }

    return DcPeerGraph(this, adjacency.map { it.toSet() }, minPeersPerSubnet)
}
