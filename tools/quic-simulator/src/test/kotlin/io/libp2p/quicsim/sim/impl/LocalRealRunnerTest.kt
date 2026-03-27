package io.libp2p.quicsim.sim.impl

import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.quicsim.core.schedule.impl.toScheduledExecutorService
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.program.SampleGossipNodeProgram
import io.libp2p.quicsim.runner.LocalRealRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class LocalRealRunnerTest {

    @Test
    @Timeout(30)
    fun `local real runner completes 5-node ring with sample gossip`() {
        val nodeCount = 5
        val nodePrograms = mutableListOf<SampleGossipNodeProgram>()
        val runner = LocalRealRunner(
            nodeFactory = object : NodeProgramFactory {
                override fun createNode(id: Int) =
                    SampleGossipNodeProgram(
                        simNodeId = id,
                        connectToNodeIds = listOf((id + 1) % nodeCount),
                        publishersCount = nodeCount,
                        params = GossipParams(),
                        randomSeed = id.toLong(),
                        messageSizeBytes = 1024,
                        // Keep publish strictly after initial gossip heartbeat/mesh formation.
                        // At 1s delay this test is flaky because publishes can race mesh setup.
                        initialPublishDelay = 2.seconds
                    ).also { nodePrograms += it }
            },
            nodeCount = nodeCount,
            listenPortStartRange = 27000
        )

        val runFuture = CompletableFuture.runAsync {
            runner.run()
        }
        try {
            runFuture.get()
        } finally {
            runCatching { runner.hosts }
                .getOrNull()
                ?.also { hosts ->
                    CompletableFuture.allOf(*hosts.map { it.stop() }.toTypedArray()).get(30, TimeUnit.SECONDS)
                }
            runCatching { runner.simContexts }
                .getOrNull()
                ?.forEach {
                    it.scheduler.toScheduledExecutorService().shutdownNow()
                }
            if (!runFuture.isDone) {
                runFuture.cancel(true)
            }
        }

        assertTrue(
            nodePrograms.all { it.isComplete() },
            "Expected all sample gossip node programs to complete"
        )
    }
}
