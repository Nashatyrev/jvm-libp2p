package io.libp2p.pubsub.gossip

import com.google.protobuf.ByteString
import io.libp2p.etc.types.millis
import io.libp2p.etc.types.seconds
import io.libp2p.etc.types.toProtobuf
import io.libp2p.pubsub.MockRouter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

/**
 * [IWantPolicy.DISCIPLINED]: an IHAVE for an unknown message is followed up with one IWANT at a
 * time, not one per announcer. The tests drive the router's clock past
 * [GossipParams.pullRetryTimeout] and [GossipParams.pullBrokenPromiseTimeout] and read which peers
 * were asked.
 */
class GossipDisciplinedPullsTest : GossipTestsBase() {

    private val params = GossipParams(
        D = 3,
        DLow = 3,
        DHigh = 3,
        DOut = 0,
        iWantPolicy = IWantPolicy.DISCIPLINED
    )

    private fun subscribedAndConnected(mockRouterCount: Int): ManyRoutersTest {
        val test = ManyRoutersTest(mockRouterCount = mockRouterCount, params = params)
        test.gossipRouter.subscribe("topic1")
        test.routers.forEach { it.router.subscribe("topic1") }
        test.connectAll()
        test.fuzz.timeController.addTime(2.seconds)
        test.mockRouters.forEach { it.inboundMessages.clear() }
        return test
    }

    private fun MockRouter.announce(messageId: ByteString) = sendToSingle(
        Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().addIhave(
                Rpc.ControlIHave.newBuilder().setTopicID("topic1").addMessageIDs(messageId)
            )
        ).build()
    )

    private fun MockRouter.requestedIds(): List<ByteString> =
        inboundMessages
            .filter { it.hasControl() }
            .flatMap { it.control.iwantList }
            .flatMap { it.messageIDsList }

    @Test
    fun `asks one announcer at a time and moves on after the retry timeout`() {
        val test = subscribedAndConnected(mockRouterCount = 3)
        val id = "message-1".toByteArray().toProtobuf()

        test.mockRouters.forEach { it.announce(id) }
        test.fuzz.timeController.addTime(10.millis)

        // three offers, one request: the first announcer
        assertThat(test.mockRouters.map { it.requestedIds() }).containsExactly(listOf(id), emptyList(), emptyList())

        // no delivery within the retry timeout: the next announcer is asked, the first is not re-asked
        test.fuzz.timeController.addTime(params.pullRetryTimeout.toMillis().millis)
        assertThat(test.mockRouters.map { it.requestedIds() }).containsExactly(listOf(id), listOf(id), emptyList())

        // and the third after another timeout -- never more than one request per peer
        test.fuzz.timeController.addTime(params.pullRetryTimeout.toMillis().millis)
        assertThat(test.mockRouters.map { it.requestedIds() }).containsExactly(listOf(id), listOf(id), listOf(id))
    }

    @Test
    fun `a delivered message ends the pull`() {
        val test = subscribedAndConnected(mockRouterCount = 2)
        val message = newProtoMessage("topic1", 0L, "Hello".toByteArray())
        val id = getMessageId(message).toProtobuf()

        test.mockRouters.forEach { it.announce(id) }
        test.fuzz.timeController.addTime(10.millis)
        assertThat(test.mockRouters[0].requestedIds()).containsExactly(id)

        // the first announcer answers well inside the retry timeout
        test.mockRouters[0].sendToSingle(Rpc.RPC.newBuilder().addPublish(message).build())
        test.fuzz.timeController.addTime(1.seconds)

        assertThat(test.mockRouters[1].requestedIds()).describedAs("second announcer is never asked").isEmpty()
    }

    @Test
    fun `a peer that breaks its promise is parked and its later offers are ignored`() {
        val test = subscribedAndConnected(mockRouterCount = 2)
        val first = "message-1".toByteArray().toProtobuf()
        val second = "message-2".toByteArray().toProtobuf()

        test.mockRouters.forEach { it.announce(first) }
        test.fuzz.timeController.addTime(10.millis)
        assertThat(test.mockRouters[0].requestedIds()).containsExactly(first)

        // the first announcer never delivers: past the broken-promise timeout it is parked
        test.fuzz.timeController.addTime(params.pullBrokenPromiseTimeout.toMillis().millis)

        // a new message it announces first is fetched from someone else
        test.mockRouters[0].announce(second)
        test.mockRouters[1].announce(second)
        test.fuzz.timeController.addTime(10.millis)

        assertThat(test.mockRouters[0].requestedIds()).describedAs("parked peer").containsExactly(first)
        assertThat(test.mockRouters[1].requestedIds()).containsExactly(first, second)
    }

    @Test
    fun `all announcers policy still asks everyone`() {
        val test = ManyRoutersTest(mockRouterCount = 3, params = params.copy(iWantPolicy = IWantPolicy.ALL_ANNOUNCERS))
        test.gossipRouter.subscribe("topic1")
        test.routers.forEach { it.router.subscribe("topic1") }
        test.connectAll()
        test.fuzz.timeController.addTime(2.seconds)
        test.mockRouters.forEach { it.inboundMessages.clear() }
        val id = "message-1".toByteArray().toProtobuf()

        test.mockRouters.forEach { it.announce(id) }
        test.fuzz.timeController.addTime(10.millis)

        assertThat(test.mockRouters.map { it.requestedIds() }).containsExactly(listOf(id), listOf(id), listOf(id))
    }
}
