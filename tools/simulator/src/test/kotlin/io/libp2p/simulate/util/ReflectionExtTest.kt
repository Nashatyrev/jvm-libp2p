package io.libp2p.simulate.util

import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReflectionExtTest {

    @Test
    fun propertiesAsMap_recursive() {

        data class A(
            val iA: Int,
            val sA: String
        )

        data class B(
            val iB: Int,

            @InlineProperties
            val a: A,

            val sB: String
        )

        val b = B(111, A(222, "aaa"), "bbb")

        val map = b.propertiesAsMap()

        assertThat(map).isEqualTo(
            mapOf(
                "iB" to 111,
                "iA" to 222,
                "sA" to "aaa",
                "sB" to "bbb"
            )
        )

    }
}