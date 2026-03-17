package io.libp2p.quicsim.core.schedule.impl

import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.netty.util.concurrent.Ticker
import java.util.concurrent.TimeUnit

class NettyTicker(
    private val monotonicTimer: MonotonicTimer
) : Ticker {

    private val epochStart = monotonicTimer.time()

    override fun initialNanoTime() = 0L

    override fun nanoTime(): Long =
        (monotonicTimer.time() - epochStart).inWholeNanoseconds

    override fun sleep(delay: Long, unit: TimeUnit?) {
        throw UnsupportedOperationException()
    }
}