package io.libp2p.quicsim.core.schedule

import jdk.nashorn.internal.runtime.BitVector
import kotlin.time.Duration

open class AggregateControllable2(
    private val controllables: List<Controllable>
) : Controllable {

    override fun advance(advanceDuration: Duration) {
        TODO()
    }

    override fun executePending() {
        executePendingAndReport()
    }

    /**
     * Returns bitvector of indexes of controllables which were due to execution()
     */
    fun executePendingAndReport(): BitVector {
        controllables.forEach { it.executePending() }
        TODO()
    }

    /**
     * notifies that nextTaskDuration might be changed for this delegate
     */
    fun update(delegateIndex: Int) {
        TODO()
    }

    override fun nextTaskDuration(): Duration? = TODO()


}
