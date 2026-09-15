package io.libp2p.pubsub

import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.types.toProtobuf
import io.libp2p.pubsub.flood.FloodRouter
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.concurrent.Executor

/**
 * A semi-duplex peer is reachable in one direction before the other: its stream to us is live while
 * ours to it is still being opened. In that window
 * [io.libp2p.etc.util.P2PServiceSemiDuplex.SDPeerHandler] answers `enqueueWrite` with an
 * already-failed future and, crucially, without consuming the messages it was handed — so the
 * router's queue for that peer is still full when the failure arrives.
 *
 * The peer can still reach us meanwhile, so it can announce a subscription and have us queue a
 * broadcast for it: [AbstractRouter.flushAllPending] walks every peer holding queued parts, not only
 * the writable ones.
 *
 * [AbstractRouter.flushPending] must not answer the failure by flushing again immediately. The retry
 * meets the same full queue and the same missing stream, and because the future is already complete
 * the cycle runs inline on the event thread — an unbounded loop that allocates a
 * `SemiDuplexNoOutboundStreamException` per turn and never yields, so the outbound stream it waits
 * for can never finish opening.
 */
class SemiDuplexFlushPendingTest {

    @Test
    fun `a peer with no outbound stream yet does not spin the event loop`() {
        val timeController = TimeControllerImpl()
        var eventLoopTasks = 0
        // Caps the event loop so a livelock fails this test rather than hanging it. Throwing rather
        // than dropping: TimeControllerImpl.setTime drains its queue without catching, so this
        // unwinds the whole drain and returns control here, and a dropped task would instead leave
        // its future uncompleted and deadlock the drain.
        val cappedExecutor = Executor { task ->
            if (eventLoopTasks++ >= TASK_CAP) throw EventLoopCapExceeded()
            task.run()
        }
        val underTestExecutor = ControlledExecutorServiceImpl(cappedExecutor)
            .also { it.setTimeController(timeController) }
        val peerExecutor = ControlledExecutorServiceImpl()
            .also { it.setTimeController(timeController) }

        val underTest = TestRouter("under-test", FloodRouter(underTestExecutor))
            .apply { testExecutor = underTestExecutor }
        val peer = TestRouter("peer", MockRouter(peerExecutor))
            .apply { testExecutor = peerExecutor }

        // One direction only. `connect` makes the caller the initiator, so the router under test
        // holds the accepted stream and has no outbound one: the semi-duplex window itself.
        peer.connect(underTest)

        // The peer's own stream works, so it can still tell us what it is subscribed to...
        (peer.router as MockRouter).sendToSingle(subscribe(TOPIC))
        // ...which is enough for a publish to queue parts for a peer we cannot yet write to.
        try {
            underTest.router.publish(newMessage(TOPIC, 0L, "hello".toByteArray()))
        } catch (e: EventLoopCapExceeded) {
            // The cap fired: fall through and let the assertion report how far it ran.
        }

        assertThat(eventLoopTasks)
            .describedAs("event-loop tasks run while the peer had no outbound stream")
            .isLessThan(TASK_CAP)
    }

    private class EventLoopCapExceeded : RuntimeException("event loop did not settle")

    private fun subscribe(topic: String): Rpc.RPC =
        Rpc.RPC.newBuilder()
            .addSubscriptions(Rpc.RPC.SubOpts.newBuilder().setSubscribe(true).setTopicid(topic))
            .build()

    private fun newMessage(topic: String, seqNo: Long, data: ByteArray) =
        DefaultPubsubMessage(
            Rpc.Message.newBuilder()
                .addTopicIDs(topic)
                .setSeqno(seqNo.toBytesBigEndian().toProtobuf())
                .setData(data.toProtobuf())
                .build()
        )

    companion object {
        private const val TASK_CAP = 10_000
        private const val TOPIC = "test-topic"
    }
}
