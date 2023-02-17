package io.libp2p.simulate

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TopologyTest {

    @Test
    fun simpleTest() {
        val g = TopologyGraph(
            0 to 1
        )

        assertThat(g.calcMaxDistanceFrom(0)).isEqualTo(1)
        assertThat(g.calcMaxDistanceFrom(1)).isEqualTo(1)
    }

    @Test
    fun test1() {
        val g = TopologyGraph(
            0 to 1,
            1 to 2
        )

        assertThat(g.calcMaxDistanceFrom(0)).isEqualTo(2)
        assertThat(g.calcMaxDistanceFrom(1)).isEqualTo(1)
        assertThat(g.calcMaxDistanceFrom(2)).isEqualTo(2)
    }

    @Test
    fun test2() {
        val g = TopologyGraph(
            0 to 1,
            1 to 2,
            2 to 0
        )

        assertThat(g.calcMaxDistanceFrom(0)).isEqualTo(1)
        assertThat(g.calcMaxDistanceFrom(1)).isEqualTo(1)
        assertThat(g.calcMaxDistanceFrom(2)).isEqualTo(1)
    }
}