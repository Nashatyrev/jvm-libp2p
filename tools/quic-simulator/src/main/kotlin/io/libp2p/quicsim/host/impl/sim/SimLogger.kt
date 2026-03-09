package io.libp2p.quicsim.host.impl.sim

import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.core.schedule.impl.NanoMonotonicTimer

class SimLogger(
    private val simTimer: MonotonicTimer,
    private val realTimer: MonotonicTimer = NanoMonotonicTimer.CPU
) {
    private val simEpochStart = simTimer.time()
    private val realEpochStart = realTimer.time()

    fun log(msg: String) {
        val realTime = realTimer.time() - realEpochStart
        val simTime = simTimer.time() - simEpochStart

        println("[$realTime][$simTime] $msg")
    }
}