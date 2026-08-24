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

    private fun TimedNetworkLink<*>.key(): Pair<String, String> =
        if (left.id < right.id) left.id to right.id else right.id to left.id
}
