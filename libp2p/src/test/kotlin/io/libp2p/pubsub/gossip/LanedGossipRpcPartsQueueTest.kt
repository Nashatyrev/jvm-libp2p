package io.libp2p.pubsub.gossip

import io.libp2p.etc.types.toProtobuf
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.Topic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

class LanedGossipRpcPartsQueueTest {

    private val lanes = GossipStreamLanes(
        priorities = listOf(StreamPriority(0), StreamPriority(1), StreamPriority(2)),
        controlLane = CONTROL_LANE,
        laneOfTopic = { if (it == BULK_TOPIC) BULK_LANE else 0 }
    )
    private val queue = LanedGossipRpcPartsQueue(GossipParams(), lanes)

    @Test
    fun `publishes partials and IHAVEs follow their topic`() {
        queue.addPublish(message(URGENT_TOPIC, 1))
        queue.addPublish(message(BULK_TOPIC, 2))
        queue.addIHave(messageId(3), BULK_TOPIC)
        queue.addPartialMessage(Rpc.PartialMessagesExtension.newBuilder().setTopicID(BULK_TOPIC).build())

        assertThat(queue.laneCount).isEqualTo(3)
        assertThat(queue.isEmpty(CONTROL_LANE)).isTrue()

        val urgent = queue.takeLane(0)
        assertThat(urgent.flatMap { it.publishList }.flatMap { it.topicIDsList }).containsExactly(URGENT_TOPIC)

        val bulk = queue.takeLane(BULK_LANE)
        assertThat(bulk.flatMap { it.publishList }.flatMap { it.topicIDsList }).containsExactly(BULK_TOPIC)
        assertThat(bulk.flatMap { it.control.ihaveList }.map { it.topicID }).containsExactly(BULK_TOPIC)
        assertThat(bulk.filter { it.hasPartial() }.map { it.partial.topicID }).containsExactly(BULK_TOPIC)

        assertThat(queue.isEmpty()).isTrue()
    }

    @Test
    fun `control goes to the control lane, subscriptions and extensions to the main lane`() {
        queue.addSubscribe(BULK_TOPIC)
        queue.addControlExtensions(Rpc.ControlExtensions.newBuilder().setPartialMessages(true).build())
        queue.addGraft(BULK_TOPIC)
        queue.addPrune(URGENT_TOPIC)
        queue.addIWant(messageId(1))

        val main = queue.takeLane(0)
        assertThat(main.flatMap { it.subscriptionsList }.map { it.topicid }).containsExactly(BULK_TOPIC)
        assertThat(main.filter { it.control.hasExtensions() }).hasSize(1)
        assertThat(queue.isEmpty(BULK_LANE)).isTrue()

        val control = queue.takeLane(CONTROL_LANE).map { it.control }
        assertThat(control.flatMap { it.graftList }.map { it.topicID }).containsExactly(BULK_TOPIC)
        assertThat(control.flatMap { it.pruneList }.map { it.topicID }).containsExactly(URGENT_TOPIC)
        assertThat(control.flatMap { it.iwantList }.flatMap { it.messageIDsList }).hasSize(1)
    }

    @Test
    fun `removePublishes looks in every lane`() {
        queue.addPublish(message(URGENT_TOPIC, 1), messageId(1))
        queue.addPublish(message(BULK_TOPIC, 2), messageId(2))
        queue.addPublish(message(BULK_TOPIC, 3), messageId(3))

        assertThat(queue.removePublishes(setOf(messageId(1), messageId(2)))).isEqualTo(2)

        assertThat(queue.isEmpty(0)).isTrue()
        assertThat(queue.takeLane(BULK_LANE).flatMap { it.publishList }.map { it.seqno })
            .containsExactly(seqNo(3))
    }

    private fun LanedGossipRpcPartsQueue.takeLane(lane: Int): List<Rpc.RPC> =
        generateSequence { popMerged(lane) }.toList()

    private fun message(topic: Topic, seq: Int): Rpc.Message =
        Rpc.Message.newBuilder()
            .addTopicIDs(topic)
            .setSeqno(seqNo(seq))
            .setData(byteArrayOf(seq.toByte()).toProtobuf())
            .build()

    private fun seqNo(seq: Int) = byteArrayOf(seq.toByte()).toProtobuf()

    private fun messageId(seq: Int) = byteArrayOf(0x42, seq.toByte()).toWBytes()

    companion object {
        private const val BULK_LANE = 1
        private const val CONTROL_LANE = 2
        private const val URGENT_TOPIC = "urgent"
        private const val BULK_TOPIC = "bulk"
    }
}
