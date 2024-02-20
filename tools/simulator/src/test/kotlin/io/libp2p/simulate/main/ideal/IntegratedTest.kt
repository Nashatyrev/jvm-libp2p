package io.libp2p.simulate.main.ideal

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.main.ideal.func.Integrated
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class IntegratedTest {

    @Test
    fun sanityTest() {
        val integrated = Integrated<Int, Int>(
            { if (it > 0) 10 else 0 },
            { i1, i2 -> i1 - i2 },
            { i1, i2 -> (i1 * i2).toDouble() },
            1,
            0
        )

        val i1 = integrated(10)
        println(i1)

        val band = Bandwidth(1024 * 1024)

        val size = band.getTransmitSize(1.milliseconds)

        println(size * 1000 / 1024)
        println(List(1000 ) { size }.sum() / 1024)
    }
}