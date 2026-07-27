package io.libp2p.quicsim.runner.graph

internal class TimedNetworkGraphValidator(
    private val vertices: List<TimedNetworkVertex>,
    private val links: List<TimedNetworkLink<*>>,
    private val verticesById: Map<String, TimedNetworkVertex>
) {
    fun validate() {
        require(vertices.isNotEmpty()) { "network graph must contain at least one vertex" }
        requireUniqueVertexIds()
        requireKnownLinkEndpoints()
        requireNoDuplicateLinks()
        requireAcyclic()
    }

    private fun requireUniqueVertexIds() {
        val duplicateIds = vertices
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateIds.isEmpty()) { "Network vertex ids must be unique: $duplicateIds" }
    }

    private fun requireKnownLinkEndpoints() {
        links.forEach { link ->
            require(verticesById[link.left.id] === link.left) {
                "Link left endpoint ${link.left.id} is not a known network vertex"
            }
            require(verticesById[link.right.id] === link.right) {
                "Link right endpoint ${link.right.id} is not a known network vertex"
            }
        }
    }

    private fun requireNoDuplicateLinks() {
        val duplicateLinks = links
            .groupingBy { it.key() }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateLinks.isEmpty()) { "Network links must be unique: $duplicateLinks" }
    }

    private fun requireAcyclic() {
        val parent = verticesById.keys.associateWith { it }.toMutableMap()

        fun find(vertexId: String): String {
            val parentId = parent.getValue(vertexId)
            if (parentId == vertexId) {
                return vertexId
            }
            val root = find(parentId)
            parent[vertexId] = root
            return root
        }

        fun union(left: String, right: String): Boolean {
            val leftRoot = find(left)
            val rightRoot = find(right)
            if (leftRoot == rightRoot) {
                return false
            }
            parent[leftRoot] = rightRoot
            return true
        }

        val cycleClosingLink = links.firstOrNull { !union(it.left.id, it.right.id) }
        require(cycleClosingLink == null) {
            "Network graph must be acyclic; cycle closes at $cycleClosingLink"
        }
    }

    private fun TimedNetworkLink<*>.key(): Pair<String, String> =
        if (left.id < right.id) left.id to right.id else right.id to left.id
}
