package io.libp2p.quicsim.runner.graph

import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

class TimedNetworkVertex(
    val id: String,
    initialTime: Duration = ZERO
) {
    var time: Duration = initialTime
        set(value) {
            require(!value.isNegative()) { "vertex time must not be negative" }
            field = value
        }

    init {
        require(id.isNotBlank()) { "vertex id must not be blank" }
        require(!initialTime.isNegative()) { "vertex time must not be negative" }
    }

    override fun toString(): String =
        "TimedNetworkVertex(id=$id, time=$time)"
}
