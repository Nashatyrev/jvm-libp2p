package io.libp2p.pubsub

import io.libp2p.core.crypto.sha256
import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.Subscriber
import io.libp2p.core.pubsub.Topic
import io.libp2p.core.pubsub.createPubsubApi
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.toProtobuf
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PubsubApiTest {

    @Test
    fun testBatchPublish() {
        val fuzz = DeterministicFuzz()
        val router1 = fuzz.createFloodRouter()
        val api1 = createPubsubApi(router1.router)
        val router2 = fuzz.createFloodRouter()
        val api2 = createPubsubApi(router2.router)
        router1.connectSemiDuplex(router2)

        val receivedMessages = LinkedBlockingQueue<MessageApi>()
        val topic = Topic("myTopic")
        api1.subscribe(Subscriber { }, topic)
        api2.subscribe(Subscriber { receivedMessages += it }, topic)
        fuzz.timeController.addTime(Duration.ofSeconds(10))

        val publishFuture = api1.createPublisher(null).publishBatch(
            listOf("Message-1".toByteArray().toByteBuf(), "Message-2".toByteArray().toByteBuf()),
            topic
        )
        fuzz.timeController.addTime(Duration.ofSeconds(1))

        Assertions.assertTrue(publishFuture.isDone)
        val receivedBodies = listOfNotNull(
            receivedMessages.poll(1, TimeUnit.SECONDS),
            receivedMessages.poll(1, TimeUnit.SECONDS)
        ).map { it.data.toByteArray().toString(StandardCharsets.UTF_8) }
        assertEquals(setOf("Message-1", "Message-2"), receivedBodies.toSet())
    }

    @Test
    fun testNoFromOrSeqNoMessageField() {
        val fuzz = DeterministicFuzz()
        val router1 = fuzz.createFloodRouter()
        val api1 = createPubsubApi(router1.router)
        val router2 = fuzz.createFloodRouter()
        val api2 = createPubsubApi(router2.router)

        router1.connectSemiDuplex(router2)

        val receivedMessages2 = LinkedBlockingQueue<MessageApi>()
        api1.subscribe(Subscriber { println(it) }, Topic("myTopic"))
        api2.subscribe(Subscriber { receivedMessages2 += it }, Topic("myTopic"))

        fuzz.timeController.addTime(Duration.ofSeconds(10))

        // No from, signature or seqNo
        val publisher1 = api1.createPublisher(null) { null }
        val publishFut = publisher1
            .publishExt("Message".toByteArray().toByteBuf(), null, null, Topic("myTopic"))

        fuzz.timeController.addTime(Duration.ofSeconds(1))

        Assertions.assertTrue(publishFut.isDone)
        val recMsg = receivedMessages2.poll(1, TimeUnit.SECONDS)
        Assertions.assertNotNull(recMsg)
        assertEquals(1, recMsg.topics.size)
        assertEquals(Topic("myTopic"), recMsg.topics[0])
        Assertions.assertNull(recMsg.seqId)
        assertEquals("Message", recMsg.data.toByteArray().toString(StandardCharsets.UTF_8))
        Assertions.assertNull(recMsg.from)
    }

    @Test
    fun testNoSenderPrivateKey() {
        val fuzz = DeterministicFuzz()
        val router1 = fuzz.createFloodRouter()
        val api1 = createPubsubApi(router1.router)
        val router2 = fuzz.createFloodRouter()

        router1.connectSemiDuplex(router2)

        api1.subscribe(Subscriber { println(it) }, Topic("myTopic"))
        router2.router.subscribe("myTopic")

        fuzz.timeController.addTime(Duration.ofSeconds(10))

        val publisher1 = api1.createPublisher(null, 777)
        val publishFut = publisher1.publish("Message".toByteArray().toByteBuf(), Topic("myTopic"))

        fuzz.timeController.addTime(Duration.ofSeconds(1))

        Assertions.assertTrue(publishFut.isDone)
        val rawMsg = router2.inboundMessages.poll(1, TimeUnit.SECONDS)!!.protobufMessage
        println(rawMsg)
        assertFalse(rawMsg.hasSignature())
        assertFalse(rawMsg.hasFrom())
        assertEquals("Message", rawMsg.data.toByteArray().toString(StandardCharsets.UTF_8))
    }

    @Test
    fun testPublishExt() {
        val fuzz = DeterministicFuzz()
        val router1 = fuzz.createFloodRouter()
        val api1 = createPubsubApi(router1.router)
        val router2 = fuzz.createFloodRouter()

        router1.connectSemiDuplex(router2)

        api1.subscribe(Subscriber { println(it) }, Topic("myTopic"))
        router2.router.subscribe("myTopic")

        fuzz.timeController.addTime(Duration.ofSeconds(10))

        val publisher1 = api1.createPublisher(null, 777)
        val publishFut =
            publisher1.publishExt("Message".toByteArray().toByteBuf(), byteArrayOf(1, 2, 3), 333, Topic("myTopic"))

        fuzz.timeController.addTime(Duration.ofSeconds(1))

        Assertions.assertTrue(publishFut.isDone)
        val rawMsg = router2.inboundMessages.poll(1, TimeUnit.SECONDS)!!.protobufMessage
        println(rawMsg)
        assertFalse(rawMsg.hasSignature())
        assertFalse(rawMsg.hasSeqno())
        assertFalse(rawMsg.hasFrom())
        assertEquals("Message", rawMsg.data.toByteArray().toString(StandardCharsets.UTF_8))
    }

    @Test
    fun testDefaultPubsubMessageKeepsOnlyDataAndTopics() {
        val rawMsg = Rpc.Message.newBuilder()
            .setFrom(byteArrayOf(1, 2, 3).toProtobuf())
            .setSeqno(byteArrayOf(4, 5, 6).toProtobuf())
            .setSignature(byteArrayOf(7, 8, 9).toProtobuf())
            .setKey(byteArrayOf(10, 11, 12).toProtobuf())
            .setData("Message".toByteArray().toProtobuf())
            .addTopicIDs("myTopic")
            .build()
        val canonicalMsg = Rpc.Message.newBuilder()
            .setData(rawMsg.data)
            .addAllTopicIDs(rawMsg.topicIDsList)
            .build()

        val msg = DefaultPubsubMessage(rawMsg)

        assertEquals(canonicalMsg, msg.protobufMessage)
        assertFalse(msg.protobufMessage.hasFrom())
        assertFalse(msg.protobufMessage.hasSeqno())
        assertFalse(msg.protobufMessage.hasSignature())
        assertFalse(msg.protobufMessage.hasKey())
        assertArrayEquals(
            sha256(canonicalMsg.toByteArray()).copyOf(DEFAULT_PUBSUB_MESSAGE_ID_LENGTH),
            msg.messageId.array
        )
        assertEquals(defaultPubsubMessageId(rawMsg), msg.messageId)
    }
}
