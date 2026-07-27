package io.libp2p.quicsim.runner.graph

import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

interface TimedNetworkVertex {
    val id: String
    var time: Duration
}
