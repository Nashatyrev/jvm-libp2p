package io.libp2p.tools.gossipcompression

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random

class Lz77Test {

    /** Messages that repeat chunks of earlier messages at various distances, plus random noise. */
    private fun messages(rnd: Random, count: Int): List<ByteArray> {
        val pool = List(50) { rnd.nextBytes(rnd.nextInt(1, 3000)) }
        return List(count) {
            val parts = List(rnd.nextInt(1, 6)) {
                when (rnd.nextInt(3)) {
                    0 -> rnd.nextBytes(rnd.nextInt(0, 500))
                    1 -> ByteArray(rnd.nextInt(0, 2000)) { 7 }
                    else -> pool[rnd.nextInt(pool.size)]
                }
            }
            parts.fold(ByteArray(0)) { a, b -> a + b }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 100, 4096, 65535, 1 shl 20])
    fun `round trip across messages`(history: Int) {
        val rnd = Random(history)
        for (depth in listOf(1, 16)) {
            val enc = LzHistoryEncoder(history, depth)
            val dec = LzHistoryDecoder(history, 1 shl 20)
            var raw = 0L
            var compressed = 0L
            for (m in messages(rnd, 400) + listOf(ByteArray(0), ByteArray(300_000) { (it % 251).toByte() })) {
                enc.encode(m)
                assertThat(dec.decode(enc.lastOutput, enc.lastSize)).isEqualTo(m)
                raw += m.size
                compressed += enc.lastSize
            }
            if (history >= 65535) assertThat(compressed).isLessThan(raw / 2)
        }
    }

    @Test
    fun `decoder rejects garbage without crashing and never exceeds the cap`() {
        val rnd = Random(1)
        val valid = LzHistoryEncoder(4096, 16).let { enc ->
            messages(rnd, 50).map {
                enc.encode(it)
                enc.lastOutput.copyOf(enc.lastSize)
            }
        }
        repeat(200_000) { iter ->
            val dec = LzHistoryDecoder(4096, 10_000)
            val input = if (iter % 2 == 0) {
                rnd.nextBytes(rnd.nextInt(0, 64))
            } else {
                valid[rnd.nextInt(valid.size)].copyOf().also { b -> if (b.isNotEmpty()) repeat(3) { b[rnd.nextInt(b.size)] = rnd.nextInt().toByte() } }
            }
            try {
                assertThat(dec.decode(input, input.size).size).isLessThanOrEqualTo(10_000)
            } catch (e: LzFormatException) {
                // expected for most inputs
            }
        }
    }
}
