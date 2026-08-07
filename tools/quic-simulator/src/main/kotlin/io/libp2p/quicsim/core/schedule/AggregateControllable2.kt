package io.libp2p.quicsim.core.schedule

import io.libp2p.quicsim.core.NotifyingPacketEmitter
import java.util.SortedMap
import java.util.TreeMap
import kotlin.time.Duration

open class AggregateControllable2<TControllable>(
    controllables: List<TControllable>
) : Controllable where TControllable : Controllable, TControllable : NotifyingPacketEmitter<*> {

    private inner class RouteController(
        val controllable: TControllable,
    ) {
        var curTime: Duration = Duration.ZERO
        var nextTaskAbsolute: Duration? = null

        init {
            controllable.addPacketAddedListener { onTaskAdded() }
            updateNextTaskTime()
        }

        fun onTaskAdded() {
            synchronized(this@AggregateControllable2) {
                if (nextTaskAbsolute == null) {
                    updateNextTaskTime()
                }
            }
        }

        private fun updateNextTaskTime() {
            nextTaskAbsolute = controllable.nextTaskDuration()?.let { it + curTime }
            if (nextTaskAbsolute != null) {
                routeActivated(this)
            }
        }

        fun advanceTillAbsolute(absoluteTime: Duration) {
            val relativeAdvance = absoluteTime - curTime
            controllable.advance(relativeAdvance)
            curTime = absoluteTime
        }

        fun executePendingAndUpdateNextTaskTime() {
            controllable.executePending()
            updateNextTaskTime()
        }
    }

    private var currentAbsoluteTime: Duration = Duration.ZERO
    private val sortedRoutes: SortedMap<Duration, MutableList<RouteController>> = TreeMap()
    private val allRoutes =
        controllables.mapIndexed { index, controllable ->
            RouteController(controllable)
        }

    // invoked when controllable having no tasks before got a task
    // !!! Can be called from another thread
    @Synchronized
    private fun routeActivated(route: RouteController) {
        sortedRoutes.computeIfAbsent(route.nextTaskAbsolute) { mutableListOf() } += route
    }

    @Synchronized
    override fun advance(advanceDuration: Duration) {
        val targetTime = currentAbsoluteTime + advanceDuration
        val nowRoutes = sortedRoutes.remove(targetTime) ?: emptyList()
        nowRoutes.forEach { route ->
            route.advanceTillAbsolute(targetTime)
            route.executePendingAndUpdateNextTaskTime()
        }
        currentAbsoluteTime = targetTime
    }

    @Synchronized
    override fun executePending() {
        // do nothing
    }


    @Synchronized
    override fun nextTaskDuration(): Duration? =
        if (sortedRoutes.isEmpty()) null
        else (sortedRoutes.firstKey() - currentAbsoluteTime)
}
