package io.libp2p.quicsim.core.schedule.impl

import io.netty.util.concurrent.Ticker
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

class SimpleNettyTicker : Ticker {

    var time: Duration = Duration.ZERO

    override fun initialNanoTime(): Long  = 0

    override fun nanoTime(): Long = time.inWholeNanoseconds

    override fun sleep(p0: Long, p1: TimeUnit?) = TODO("Not supported")
}