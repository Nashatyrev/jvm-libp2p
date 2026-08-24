package io.libp2p.quicsim.runner.graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds

class IncrementalTimeAdvanceSchedulerTest {

    @Test
    fun `prioritizes an advanceable router over an endpoint with a larger advance`() {
        val router = TestVertex("router", isRouter = true)
        val shortLinkEndpoint = TestVertex("short-link-endpoint", isRouter = false)
        val longLinkEndpoint = TestVertex("long-link-endpoint", isRouter = false)
        val graph = TimedNetworkGraph(
            vertices = listOf(router, shortLinkEndpoint, longLinkEndpoint),
            links = listOf(
                TestLink(router, shortLinkEndpoint, 10.milliseconds),
                TestLink(router, longLinkEndpoint, 100.milliseconds),
            ),
        )
        val scheduler = IncrementalTimeAdvanceScheduler(graph) { it.isRouter }

        val routerAdvance = scheduler.reserveNext()

        assertEquals(router, routerAdvance?.vertex)
        assertEquals(10.milliseconds, routerAdvance?.duration)
        complete(graph, scheduler, routerAdvance!!)

        val endpointAdvance = scheduler.reserveNext()
        assertEquals(longLinkEndpoint, endpointAdvance?.vertex)
        assertEquals(110.milliseconds, endpointAdvance?.duration)
    }

    @Test
    fun `refreshes the router after all of its neighbours advance`() {
        val router = TestVertex("router", isRouter = true)
        val endpoint1 = TestVertex("endpoint-1", isRouter = false)
        val endpoint2 = TestVertex("endpoint-2", isRouter = false)
        val graph = TimedNetworkGraph(
            vertices = listOf(router, endpoint1, endpoint2),
            links = listOf(
                TestLink(router, endpoint1, 10.milliseconds),
                TestLink(router, endpoint2, 10.milliseconds),
            ),
        )
        val scheduler = IncrementalTimeAdvanceScheduler(graph) { it.isRouter }

        val routerAdvance = scheduler.reserveNext()!!
        assertEquals(router, routerAdvance.vertex)
        complete(graph, scheduler, routerAdvance)

        val endpoint1Advance = scheduler.reserveNext()!!
        assertEquals(endpoint1, endpoint1Advance.vertex)
        complete(graph, scheduler, endpoint1Advance)

        val endpoint2Advance = scheduler.reserveNext()!!
        assertEquals(endpoint2, endpoint2Advance.vertex)
        complete(graph, scheduler, endpoint2Advance)

        val nextAdvance = scheduler.reserveNext()

        assertEquals(router, nextAdvance?.vertex)
        assertEquals(20.milliseconds, nextAdvance?.duration)
    }

    @Test
    fun `does not reserve adjacent vertices concurrently`() {
        val router = TestVertex("router", isRouter = true)
        val endpoint1 = TestVertex("endpoint-1", isRouter = false)
        val endpoint2 = TestVertex("endpoint-2", isRouter = false)
        val graph = TimedNetworkGraph(
            vertices = listOf(router, endpoint1, endpoint2),
            links = listOf(
                TestLink(router, endpoint1, 10.milliseconds),
                TestLink(router, endpoint2, 10.milliseconds),
            ),
        )
        val scheduler = IncrementalTimeAdvanceScheduler(graph) { it.isRouter }

        val routerAdvance = scheduler.reserveNext()!!

        assertEquals(router, routerAdvance.vertex)
        assertNull(scheduler.reserveNext())

        complete(graph, scheduler, routerAdvance)

        val endpointAdvance = scheduler.reserveNext()
        assertEquals(endpoint1, endpointAdvance?.vertex)
    }

    private fun complete(
        graph: TimedNetworkGraph<TestVertex, TestLink>,
        scheduler: IncrementalTimeAdvanceScheduler<TestVertex, TestLink>,
        advance: IncrementalTimeAdvanceScheduler.VertexAdvance<TestVertex>,
    ) {
        graph.advanceVertexTime(advance.vertex.id, advance.duration)
        scheduler.complete(advance)
    }

    private data class TestVertex(
        override val id: String,
        val isRouter: Boolean,
        override var time: Duration = ZERO,
    ) : TimedNetworkVertex {
        override fun advanceTime(delta: Duration) {
            time += delta
        }
    }

    private data class TestLink(
        override val left: TestVertex,
        override val right: TestVertex,
        override val latency: Duration,
    ) : TimedNetworkLink<TestVertex>
}
