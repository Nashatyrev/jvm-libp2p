package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.schedule.Controllable
import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.core.schedule.impl.NanoMonotonicTimer
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

abstract class AbstractSimPacketBridge : Controllable, NetworkController {

    protected var nanosPassed = AtomicLong(0)
    override val monotonicTimer: MonotonicTimer = NanoMonotonicTimer(nanosPassed::get)

    override fun advance(advanceDuration: Duration) {
        nanosPassed.updateAndGet { it + advanceDuration.inWholeNanoseconds }
        advanceImpl(advanceDuration)
    }

    abstract fun advanceImpl(advanceDuration: Duration)
    fun close() {}
}
