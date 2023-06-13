package io.libp2p.simulate.main

import io.libp2p.tools.LOG_TIME_FORMAT
import io.libp2p.tools.log
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.time.Duration.Companion.milliseconds

typealias SimulationLogger = (String) -> Unit
typealias SimulationSingleRunner<TParams, TResult> = (TParams, SimulationLogger) -> TResult

class SimulationRunner<TParams : Any, TResult : Any>(
    val threadCount: Int = Runtime.getRuntime().availableProcessors() / 2,
    val globalLogger: SimulationLogger = { log(it) },
    val printLocalLogging: Boolean = false,
    val runner: SimulationSingleRunner<TParams, TResult>,
) {


    fun runAll(paramsSet: List<TParams>): List<TResult> {
        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            return paramsSet
                .withIndex()
                .map { (index, params) ->
                    val idx1 = index + 1
                    val localLogger = when {
                        !printLocalLogging -> NoopLogger()
                        threadCount == 1 -> ImmediateLogger(globalLogger)
                        else -> BufferedLogger(idx1)
                    }
                    val future = executor.submit(Callable {
                        globalLogger("Started $idx1 of ${paramsSet.size}: $params")
                        try {
                            val t1 = System.currentTimeMillis()
                            val res = runner(params) { localLogger.log(it) }
                            val t2 = System.currentTimeMillis()
                            globalLogger("Completed $idx1 of ${paramsSet.size} in " + (t2 - t1).milliseconds)
                            if (printLocalLogging) {
                                localLogger.flush(globalLogger)
                            }
                            res
                        } catch (e: Throwable) {
                            globalLogger("Error running task $idx1: ")
                            e.printStackTrace()
                            globalLogger("Logs from the task: ")
                            localLogger.flush(globalLogger)
                            throw e
                        }
                    })
                    RunTask(index, future)
                }
                .map {
                    it.resultPromise.get()
                }
        } finally {
            executor.shutdownNow()
        }
    }

    inner class RunTask(
        val index: Int,
        val resultPromise: Future<TResult>
    )

    interface LocalLogger {
        fun log(s: String)
        fun flush(dest: SimulationLogger)
    }

    class NoopLogger : LocalLogger {
        override fun log(s: String) {}
        override fun flush(dest: SimulationLogger) {}
    }

    class ImmediateLogger(val dest: SimulationLogger) : LocalLogger {
        override fun log(s: String) {
            dest("  [${LOG_TIME_FORMAT.format(Date())}] $s")
        }

        override fun flush(dest: SimulationLogger) {
        }
    }

    class BufferedLogger(val idx: Int) : LocalLogger {
        val entries = mutableListOf<Pair<Long, String>>()
        override fun log(s: String) {
            entries += System.currentTimeMillis() to s
        }

        override fun flush(dest: SimulationLogger) {
            entries.forEach {
                dest(" {$idx} [${LOG_TIME_FORMAT.format(Date(it.first))}] ${it.second}")
            }
        }
    }
}