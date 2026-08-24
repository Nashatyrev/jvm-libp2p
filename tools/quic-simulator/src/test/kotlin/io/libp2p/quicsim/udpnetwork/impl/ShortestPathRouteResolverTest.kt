package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.TestNetworkBuilder
import io.libp2p.quicsim.udpnetwork.fifoUdpSimQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

class ShortestPathRouteResolverTest {

    @Test
    fun `resolves next hop through intermediate routers`() {
        val networkBuilder = TestNetworkBuilder()
        val source = networkBuilder.node("source")
        val router1 = networkBuilder.router("router-1")
        val router2 = networkBuilder.router("router-2")
        val destination = networkBuilder.node("destination")
        val qdiscFactory = { latency: kotlin.time.Duration, _: Boolean ->
            fifoUdpSimQueue(Bandwidth(Bandwidth.INFINITE), latency)
        }
        networkBuilder
            .linkBiDir(source, router1, 1.milliseconds, qdiscFactory)
            .linkBiDir(router1, router2, 1.milliseconds, qdiscFactory)
            .linkBiDir(router2, destination, 1.milliseconds, qdiscFactory)
        val resolver = ShortestPathRouteResolver(networkBuilder.build())

        assertEquals(router1, resolver.findNextHop(source, destination))
        assertEquals(router2, resolver.findNextHop(router1, destination))
        assertEquals(destination, resolver.findNextHop(router2, destination))
    }

    @Test
    fun `resolves routes safely from concurrent callers`() {
        val networkBuilder = TestNetworkBuilder()
        val source = networkBuilder.node("source")
        val router = networkBuilder.router("router")
        val destinations = (0 until 32).map { index ->
            networkBuilder.node("destination-$index")
        }
        val qdiscFactory = { latency: kotlin.time.Duration, _: Boolean ->
            fifoUdpSimQueue(Bandwidth(Bandwidth.INFINITE), latency)
        }
        networkBuilder.linkBiDir(source, router, 1.milliseconds, qdiscFactory)
        destinations.forEach { destination ->
            networkBuilder.linkBiDir(router, destination, 1.milliseconds, qdiscFactory)
        }
        val resolver = ShortestPathRouteResolver(networkBuilder.build())
        val executor = Executors.newFixedThreadPool(8)

        try {
            val tasks = (0 until 1_000).map { index ->
                java.util.concurrent.Callable {
                    resolver.findNextHop(source, destinations[index % destinations.size])
                }
            }
            executor.invokeAll(tasks).forEach { future ->
                assertEquals(router, future.get())
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
