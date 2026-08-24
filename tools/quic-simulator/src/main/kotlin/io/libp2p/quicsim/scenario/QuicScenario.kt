package io.libp2p.quicsim.scenario

import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkDefaults
import kotlin.time.Duration

data class QuicScenario<out F : NodeProgramFactory>(
    val name: String,
    val network: QuicNetworkTopology,
    val maxRunDuration: Duration,
    val createNodeProgramFactory: () -> F
) {
    val nodeCount: Int
        get() = network.hosts.size
}

data class QuicScenarioResult<F : NodeProgramFactory>(
    val scenarioName: String,
    val runnerName: String,
    val nodeProgramFactory: F,
    val events: List<QuicScenarioEvent> = emptyList()
)

interface QuicScenarioRunner {
    fun <F : NodeProgramFactory> run(scenario: QuicScenario<F>): QuicScenarioResult<F>
}

data class QuicNetworkTopology(
    val hosts: List<QuicNetworkHost>,
    val routers: List<QuicNetworkRouter> = emptyList(),
    val links: List<QuicNetworkLink>
) {
    init {
        require(hosts.isNotEmpty()) { "Scenario network must contain at least one host" }
        val nodeIds = (hosts.map { it.id } + routers.map { it.id })
        require(nodeIds.size == nodeIds.toSet().size) { "Network node ids must be unique: $nodeIds" }
        val knownIds = nodeIds.toSet()
        links.forEach { link ->
            require(link.from in knownIds) { "Link source ${link.from} is not a known network node" }
            require(link.to in knownIds) { "Link target ${link.to} is not a known network node" }
        }
    }

    companion object {
        fun <R> regional(
            descriptor: RegionalNetworkDescriptor<R>,
            hostRegions: List<R>,
            bandwidthBytesPerSecond: Long,
            hostId: (Int) -> String = { "node-$it" },
            maxQueueWaitTime: Duration = UdpSimNetworkDefaults.MAX_QUEUE_WAIT_TIME
        ): QuicNetworkTopology =
            RegionalNetworkTopologyBuilder(descriptor, maxQueueWaitTime).also { builder ->
                hostRegions.forEachIndexed { index, region ->
                    builder.addHost(hostId(index), region, bandwidthBytesPerSecond)
                }
            }.build()

        fun star(
            hostCount: Int,
            latency: Duration,
            bandwidthBytesPerSecond: Long,
            routerId: String = "router-0",
            hostId: (Int) -> String = { "node-$it" },
            maxQueueWaitTime: Duration = UdpSimNetworkDefaults.MAX_QUEUE_WAIT_TIME
        ): QuicNetworkTopology {
            require(hostCount > 0) { "hostCount must be positive" }
            val hosts = (0 until hostCount).map { QuicNetworkHost(hostId(it)) }
            val links = hosts.flatMap { host ->
                listOf(
                    QuicNetworkLink(
                        from = host.id,
                        to = routerId,
                        latency = latency,
                        bandwidthBytesPerSecond = bandwidthBytesPerSecond,
                        maxQueueWaitTime = maxQueueWaitTime
                    ),
                    QuicNetworkLink(
                        from = routerId,
                        to = host.id,
                        latency = latency,
                        bandwidthBytesPerSecond = bandwidthBytesPerSecond,
                        maxQueueWaitTime = maxQueueWaitTime
                    )
                )
            }
            return QuicNetworkTopology(
                hosts = hosts,
                routers = listOf(QuicNetworkRouter(routerId)),
                links = links
            )
        }
    }
}

data class QuicNetworkHost(val id: String)

data class QuicNetworkRouter(val id: String)

data class QuicNetworkLink(
    val from: String,
    val to: String,
    val latency: Duration,
    val bandwidthBytesPerSecond: Long,
    val maxQueueWaitTime: Duration = UdpSimNetworkDefaults.MAX_QUEUE_WAIT_TIME
) {
    init {
        require(!latency.isNegative()) { "latency must not be negative" }
        require(bandwidthBytesPerSecond > 0) { "bandwidthBytesPerSecond must be positive" }
        require(!maxQueueWaitTime.isNegative()) { "maxQueueWaitTime must not be negative" }
    }
}
