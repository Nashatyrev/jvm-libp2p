package io.libp2p.quicsim.runner.graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class TimedNetworkGraphTest {

    @Test
    fun `creates graph with nodes routers local times and heterogeneous link latencies`() {
        val node0 = TestVertex("node-0", 5.milliseconds)
        val router0 = TestVertex("router-0", 7.milliseconds)
        val node1 = TestVertex("node-1")

        val graph = TimedNetworkGraph(
            vertices = listOf(node0, router0, node1),
            links = listOf(
                TestLink(node0, router0, 10.milliseconds),
                TestLink(router0, node1, 25.milliseconds)
            )
        )

        assertEquals(3, graph.vertices.size)
        assertEquals(2, graph.links.size)
        assertEquals(5.milliseconds, graph.vertex("node-0").time)
        assertEquals(listOf("router-0"), graph.neighbours("node-0").map { it.vertex.id })
        assertEquals(setOf("node-0", "node-1"), graph.neighbours("router-0").map { it.vertex.id }.toSet())
        assertEquals(25.milliseconds, graph.linkBetween("node-1", "router-0")?.latency)
    }

    @Test
    fun `detects vertex time differences greater than link latency when advancing`() {
        val node0 = TestVertex("node-0", 0.milliseconds)
        val router0 = TestVertex("router-0", 20.milliseconds)
        val node1 = TestVertex("node-1", 30.milliseconds)

        val graph = TimedNetworkGraph(
            vertices = listOf(node0, router0, node1),
            links = listOf(
                TestLink(node0, router0, 10.milliseconds),
                TestLink(router0, node1, 15.milliseconds)
            )
        )

        assertFalse(graph.canAdvanceVertex("router-0", 0.milliseconds))
        assertTrue(graph.canAdvanceVertex("node-0", 10.milliseconds))
    }

    @Test
    fun `computes maximum safe advance for a vertex`() {
        val node0 = TestVertex("node-0", 0.milliseconds)
        val router0 = TestVertex("router-0", 10.milliseconds)
        val node1 = TestVertex("node-1", 25.milliseconds)

        val graph = TimedNetworkGraph(
            vertices = listOf(node0, router0, node1),
            links = listOf(
                TestLink(node0, router0, 15.milliseconds),
                TestLink(router0, node1, 20.milliseconds)
            )
        )

        assertEquals(5.milliseconds, graph.maxAdvance("router-0"))
        assertTrue(graph.canAdvanceVertex("router-0", 5.milliseconds))
        assertFalse(graph.canAdvanceVertex("router-0", 6.milliseconds))

        graph.advanceVertexTime("router-0", 5.milliseconds)

        assertEquals(15.milliseconds, graph.vertex("router-0").time)
    }

    @Test
    fun `maximum safe advance is zero when vertex is already at neighbour bound`() {
        val node0 = TestVertex("node-0", 0.milliseconds)
        val router0 = TestVertex("router-0", 20.milliseconds)

        val graph = TimedNetworkGraph(
            vertices = listOf(node0, router0),
            links = listOf(TestLink(node0, router0, 10.milliseconds))
        )

        assertEquals(0.milliseconds, graph.maxAdvance("router-0"))
        assertFalse(graph.canAdvanceVertex("router-0", 0.milliseconds))
    }

    @Test
    fun `isolated vertex cannot calculate maximum advance`() {
        val graph = TimedNetworkGraph<TestVertex, TestLink>(
            vertices = listOf(TestVertex("node-0")),
            links = emptyList()
        )

        assertThrows(NoSuchElementException::class.java) {
            graph.maxAdvance("node-0")
        }
        assertTrue(graph.canAdvanceVertex("node-0", 1.milliseconds))
    }

    @Test
    fun `rejects cycles`() {
        assertThrows(IllegalArgumentException::class.java) {
            val node0 = TestVertex("node-0")
            val router0 = TestVertex("router-0")
            val node1 = TestVertex("node-1")

            TimedNetworkGraph(
                vertices = listOf(node0, router0, node1),
                links = listOf(
                    TestLink(node0, router0, 10.milliseconds),
                    TestLink(router0, node1, 10.milliseconds),
                    TestLink(node1, node0, 10.milliseconds)
                )
            )
        }
    }

    @Test
    fun `rejects duplicate vertex ids duplicate links and unknown endpoints`() {
        assertThrows(IllegalArgumentException::class.java) {
            TimedNetworkGraph<TestVertex, TestLink>(
                vertices = listOf(
                    TestVertex("node-0"),
                    TestVertex("node-0")
                ),
                links = emptyList()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            val node0 = TestVertex("node-0")
            val router0 = TestVertex("router-0")

            TimedNetworkGraph(
                vertices = listOf(node0, router0),
                links = listOf(
                    TestLink(node0, router0, 10.milliseconds),
                    TestLink(router0, node0, 10.milliseconds)
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            val node0 = TestVertex("node-0")
            val router0 = TestVertex("router-0")

            TimedNetworkGraph(
                vertices = listOf(node0),
                links = listOf(TestLink(node0, router0, 10.milliseconds))
            )
        }
    }

    private data class TestVertex(
        override val id: String,
        override var time: Duration = Duration.ZERO
    ) : TimedNetworkVertex {
        override fun advanceTime(delta: Duration) {
            time += delta
        }
    }

    private data class TestLink(
        override val left: TestVertex,
        override val right: TestVertex,
        override val latency: Duration
    ) : TimedNetworkLink<TestVertex>
}
