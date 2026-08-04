package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.schedule.MonotonicTimer

interface NetworkController {

    val monotonicTimer: MonotonicTimer

    fun advanceWhile(predicate: () -> Boolean)
}