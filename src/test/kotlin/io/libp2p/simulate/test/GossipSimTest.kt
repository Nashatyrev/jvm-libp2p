package io.libp2p.simulate.test

import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.toByteBuf
import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.simulate.gossip.GossipSimPeer
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import io.libp2p.tools.transpose
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.function.Consumer

class GossipSimTest {

    @Test
    fun simplest1() {
        val timeController = TimeControllerImpl()

        val createPeer = {
            val peer = GossipSimPeer(listOf(Topic("aaa")), "1", Random())
            peer.routerBuilder = GossipRouterBuilder().also {
                it.serialize = true
            }

            peer.pubsubLogs = { true }
            peer.simExecutor = ControlledExecutorServiceImpl(timeController)
            peer.currentTime = { timeController.time }
            peer
        }

        val p1 = createPeer()
        val p2 = createPeer()

        val c1 = p1.connect(p2).get()
        var gotIt = false
        p2.api.subscribe(Consumer { gotIt = true }, Topic("a"))
        val p1Pub = p1.api.createPublisher(p1.keyPair.first, 0)
        val m1 = p1Pub.publish("Hello".toByteArray().toByteBuf(), Topic("a"))

        Assertions.assertTrue(gotIt)
        val c1_1 = c1.dialerStat
        val c1_2 = c1.listenerStat

        println("$c1_1, $c1_2")
    }

    @Test
    fun regroupTest() {
        val t1 = listOf(
            mapOf(
                "a" to 11,
                "b" to 12,
                "c" to 13
            ),
            mapOf(
                "a" to 21,
                "b" to 22,
                "c" to 23
            )
        )

        val t2 = t1.transpose()

        Assertions.assertEquals(2, t2["a"]!!.size)
        Assertions.assertEquals(11, t2["a"]!![0])
        Assertions.assertEquals(21, t2["a"]!![1])

        val t3 = t2.transpose()
        Assertions.assertEquals(t1, t3)
    }
}