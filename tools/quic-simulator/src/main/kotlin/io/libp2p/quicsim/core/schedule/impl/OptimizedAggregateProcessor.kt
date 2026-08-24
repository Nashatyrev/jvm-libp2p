package io.libp2p.quicsim.core.schedule.impl

import io.libp2p.quicsim.core.NotifyingPacketEmitter
import io.libp2p.quicsim.core.PacketProcessor
import io.libp2p.quicsim.core.schedule.AggregateProcessor
import java.util.SortedMap
import java.util.TreeMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration

open class OptimizedAggregateProcessor<TProcessor, TPacket>(
    processors: List<TProcessor>
) : AggregateProcessor<TPacket> where TProcessor : PacketProcessor<TPacket>, TProcessor : NotifyingPacketEmitter<TPacket> {

    private inner class RouteController(
        val index: Int,
        val delegate: TProcessor,
    ) {
        var curTime: Duration = Duration.Companion.ZERO
        var nextTaskAbsolute: Duration? = null
        var pendingEmitPackets: List<TPacket> = emptyList()

        init {
            delegate.addPacketAddedListener { onTaskAdded() }
            updateNextTaskTime()
        }

        fun onTaskAdded() {
            taskAddedRoutes += this
        }

        fun updateNextTaskTime() {
            val newNextTask = delegate.nextTaskDuration()?.let { it + curTime }
            if (newNextTask != nextTaskAbsolute) {
                nextTaskAbsolute?.let { routeDeactivated(this, it) }
                nextTaskAbsolute = newNextTask
                newNextTask?.let {
                    routeActivated(this, it)
                }
            }
        }

        fun advanceTillAbsolute(absoluteTime: Duration) {
            if (absoluteTime == curTime) {
                return
            }
            val relativeAdvance = absoluteTime - curTime
            delegate.advance(relativeAdvance)
            curTime = absoluteTime
        }

        fun executePendingAndUpdateNextTaskTime() {
            delegate.executePending()
            val emittedPackets = delegate.emitPackets()
            if (emittedPackets.isNotEmpty()) {
                pendingEmitPackets += emittedPackets
                pendingEmitRoutes += index
            }
            updateNextTaskTime()
        }

        fun drainEmitPackets(): List<TPacket> {
            val ret = pendingEmitPackets
            if (ret.isNotEmpty()) {
                pendingEmitPackets = emptyList()
            }
            return ret
        }

        fun receivePackets(packets: List<TPacket>) {
            if (packets.isNotEmpty()) {
                advanceTillAbsolute(currentAbsoluteTime)
                delegate.receivePackets(packets)
                updateNextTaskTime()
            }
        }
    }

    private var currentAbsoluteTime: Duration = Duration.Companion.ZERO
    private val sortedRoutes: SortedMap<Duration, MutableList<RouteController>> = TreeMap()
    private val pendingEmitRoutes = mutableSetOf<Int>()
    private val taskAddedRoutes = ConcurrentLinkedQueue<RouteController>()
    private val allRoutes =
        processors.mapIndexed { index, processor ->
            RouteController(index, processor)
        }

    // invoked when controllable having no tasks before got a task
    // !!! Can be called from another thread
    @Synchronized
    private fun routeActivated(route: RouteController, taskTime: Duration) {
        sortedRoutes.computeIfAbsent(taskTime) { mutableListOf() } += route
    }

    @Synchronized
    private fun routeDeactivated(route: RouteController, taskTime: Duration) {
        val routes = sortedRoutes[taskTime] ?: return
        routes.remove(route)
        if (routes.isEmpty()) {
            sortedRoutes.remove(taskTime)
        }
    }

    @Synchronized
    override fun emitPackets(idx: Int): List<TPacket> {
        return allRoutes[idx].drainEmitPackets()
    }

    @Synchronized
    fun drainPendingEmitRoutes(): List<Int> {
        flushTaskAddedRoutes()
        val ret = pendingEmitRoutes.toList()
        pendingEmitRoutes.clear()
        return ret
    }

    @Synchronized
    override fun receivePackets(idx: Int, packets: List<TPacket>) {
        if (packets.isNotEmpty()) {
            flushTaskAddedRoutes()
            allRoutes[idx].receivePackets(packets)
            flushTaskAddedRoutes()
        }
    }

    @Synchronized
    override fun advance(advanceDuration: Duration) {
        flushTaskAddedRoutes()
        val targetTime = currentAbsoluteTime + advanceDuration
        val nowRoutes = sortedRoutes.remove(targetTime) ?: emptyList()
        nowRoutes.forEach { route ->
            route.advanceTillAbsolute(targetTime)
            route.executePendingAndUpdateNextTaskTime()
        }
        currentAbsoluteTime = targetTime
        flushTaskAddedRoutes()
    }

    @Synchronized
    override fun executePending() {
        // do nothing
    }


    @Synchronized
    override fun nextTaskDuration(): Duration? =
        run {
            flushTaskAddedRoutes()
            if (sortedRoutes.isEmpty()) null
            else (sortedRoutes.firstKey() - currentAbsoluteTime)
        }

    private fun flushTaskAddedRoutes() {
        generateSequence { taskAddedRoutes.poll() }
            .forEach { route ->
                route.updateNextTaskTime()
            }
    }
}
