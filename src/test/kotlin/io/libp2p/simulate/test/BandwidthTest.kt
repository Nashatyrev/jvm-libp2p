package io.libp2p.simulate.test

import io.libp2p.core.pubsub.Subscriber
import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.SimpleBandwidthTracker
import io.libp2p.simulate.gossip.GossipSimPeer
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import io.libp2p.tools.seconds
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class BandwidthTest {

    @Test
    fun test1() {
        val timeController = TimeControllerImpl()
        val executor = ControlledExecutorServiceImpl(timeController)
        val id = AtomicInteger()

        val createPeer = {
            val peer = GossipSimPeer(Topic("aaa"), "${id.incrementAndGet()}", Random())
            peer.routerBuilder = GossipRouterBuilder().also {
                it.serialize = true
            }

            peer.pubsubLogs = { true }
            peer.simExecutor = executor
            peer.currentTime = { timeController.time }
            peer
        }

        val p1 = createPeer().also {
            it.outboundBandwidth = SimpleBandwidthTracker(Bandwidth(1000), executor)
        }
        val p2 = createPeer().also {
            it.inboundBandwidth = SimpleBandwidthTracker(Bandwidth(2000), executor)
        }

        val con = p1.connect(p2).join()

        val topic = Topic("topic-1")
        p1.api.subscribe(Subscriber { }, topic)

        p2.api.subscribe(Subscriber {
            println("Received ${it.data.readableBytes()} bytes at ${timeController.time}")
        }, topic)
        timeController.addTime(10.seconds)

        p1.apiPublisher.publish(Unpooled.wrappedBuffer(ByteArray(2000)))
        timeController.addTime(10.seconds)
    }
}