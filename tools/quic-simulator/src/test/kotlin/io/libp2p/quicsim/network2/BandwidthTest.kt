package io.libp2p.quicsim.network2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.nanoseconds

class BandwidthTest {

    @Test
    fun `formats human readable bandwidth`() {
        assertEquals("999 B/s", Bandwidth(999).toString())
        assertEquals("1 KiB/s", Bandwidth(1024).toString())
        assertEquals("1.5 KiB/s", Bandwidth(1536).toString())
        assertEquals("2 MiB/s", Bandwidth(2L * 1024 * 1024).toString())
    }

    @Test
    fun `calculates transfer duration with nanosecond precision`() {
        assertEquals(333_333_334.nanoseconds, Bandwidth(3).durationToTransfer(1))
        assertEquals(1.nanoseconds, Bandwidth(Long.MAX_VALUE).durationToTransfer(1))
        assertEquals(kotlin.time.Duration.ZERO, Bandwidth(1).durationToTransfer(0))
    }
}
