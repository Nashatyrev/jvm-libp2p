package io.libp2p.quicsim.core.schedule

import io.libp2p.quicsim.core.NotifyingPacketEmitter
import io.libp2p.quicsim.core.PacketProcessor
import java.util.SortedMap
import java.util.TreeMap
import kotlin.collections.isNotEmpty
import kotlin.time.Duration

open class AggregateProcessor2<TControllable, TPacket>(
    processors: List<TControllable>
) : Controllable where TControllable : PacketProcessor<TPacket>, TControllable : NotifyingPacketEmitter<TPacket> {

    private inner class RouteController(
        val controllable: TControllable,
    ) {
        var curTime: Duration = Duration.ZERO
        var nextTaskAbsolute: Duration? = null
        var pendingEmitPackets: List<TPacket> = emptyList()

        init {
            controllable.addPacketAddedListener { onTaskAdded() }
            updateNextTaskTime()
        }

        fun onTaskAdded() {
            synchronized(this@AggregateProcessor2) {
                if (nextTaskAbsolute == null) {
                    updateNextTaskTime()
                }
            }
        }

        fun updateNextTaskTime() {
            val newNextTask = controllable.nextTaskDuration()?.let { it + curTime }
            if (newNextTask != nextTaskAbsolute) {
                nextTaskAbsolute = controllable.nextTaskDuration()?.let { it + curTime }
                if (nextTaskAbsolute != null) {
                    routeActivated(this)
                }
            }
        }

        fun advanceTillAbsolute(absoluteTime: Duration) {
            if (absoluteTime == currentAbsoluteTime) {
                return
            }
            val relativeAdvance = absoluteTime - curTime
            controllable.advance(relativeAdvance)
            curTime = absoluteTime
        }

        fun executePendingAndUpdateNextTaskTime() {
            controllable.executePending()
            pendingEmitPackets = controllable.emitPackets()
            updateNextTaskTime()
        }

        fun drainEmitPackets(): List<TPacket> {
            val ret = pendingEmitPackets
            pendingEmitPackets = emptyList()
            return ret
        }

        fun receivePackets(packets: List<TPacket>) {
            if (packets.isNotEmpty()) {
                advanceTillAbsolute(currentAbsoluteTime)
                controllable.receivePackets(packets)
                updateNextTaskTime()
            }
        }
    }

    private var currentAbsoluteTime: Duration = Duration.ZERO
    private val sortedRoutes: SortedMap<Duration, MutableList<RouteController>> = TreeMap()
    private val allRoutes =
        processors.mapIndexed { index, controllable ->
            RouteController(controllable)
        }

    // invoked when controllable having no tasks before got a task
    // !!! Can be called from another thread
    @Synchronized
    private fun routeActivated(route: RouteController) {
        sortedRoutes.computeIfAbsent(route.nextTaskAbsolute) { mutableListOf() } += route
    }

    @Synchronized
    fun advanceDelegateToCurrent(delegateIndex: Int) {
    }

    @Synchronized
    fun emitPackets(idx: Int): List<TPacket> {
        return allRoutes[idx].drainEmitPackets()
    }

    @Synchronized
    fun receivePackets(idx: Int, packets: List<TPacket>) {
        if (packets.isNotEmpty()) {
            allRoutes[idx].receivePackets(packets)
        }
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
