package io.libp2p.quicsim.core.schedule

import kotlin.time.Duration

interface TimePoint {

    operator fun minus(other: TimePoint): Duration
}
