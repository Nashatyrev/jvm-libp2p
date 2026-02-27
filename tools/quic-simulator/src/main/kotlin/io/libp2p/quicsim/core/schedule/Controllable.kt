package io.libp2p.quicsim.core.schedule

import kotlin.time.Duration

interface Controllable {

    fun advanceAndExecuteAll(advanceDuration: Duration)

    fun nextTaskDuration(): Duration?
}
