package io.libp2p.example.dc

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DcStreamLanesTest {

    @Test
    fun `parses lanes and maps every topic of a type to its lane`() {
        val lanes = DcStreamLanes.parse("block, ffg-vote|payload-c|blob", controlLane = 1)

        assertThat(lanes.lanes).containsExactly(
            setOf(DcSlotMessageType.BLOCK, DcSlotMessageType.FFG_ATTESTATION),
            setOf(DcSlotMessageType.PAYLOAD_CHUNK),
            setOf(DcSlotMessageType.BLOB_COLUMN)
        )
        val gossipLanes = lanes.gossipStreamLanes
        assertThat(gossipLanes.priorities.map { it.urgency }).containsExactly(0, 1, 2)
        assertThat(gossipLanes.controlLane).isEqualTo(1)

        fun laneOf(type: DcSlotMessageType, topics: DcSlotMessageTopics, subnet: Int?) =
            gossipLanes.laneOf(topics.topic(type, subnet).topic)
        assertThat(laneOf(DcSlotMessageType.BLOCK, DcSlotMessageTopics.Global, null)).isEqualTo(0)
        assertThat(laneOf(DcSlotMessageType.FFG_ATTESTATION, DcSlotMessageTopics.Subnets(64), 17)).isEqualTo(0)
        assertThat(laneOf(DcSlotMessageType.PAYLOAD_CHUNK, DcSlotMessageTopics.Subnets(64), 3)).isEqualTo(1)
        assertThat(laneOf(DcSlotMessageType.BLOB_COLUMN, DcSlotMessageTopics.Subnets(128), 100)).isEqualTo(2)
        // Not listed, and not one of our topics at all: lane 0
        assertThat(laneOf(DcSlotMessageType.AGGREGATES, DcSlotMessageTopics.Global, null)).isEqualTo(0)
        assertThat(gossipLanes.laneOf("some/other/topic")).isEqualTo(0)
    }

    @Test
    fun `rejects unknown and repeated message types`() {
        assertThatThrownBy { DcStreamLanes.parse("block|nonsense") }.hasMessageContaining("nonsense")
        assertThatThrownBy { DcStreamLanes.parse("block|blob,block") }.hasMessageContaining("one lane only")
    }
}
