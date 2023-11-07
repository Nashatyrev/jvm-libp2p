package io.libp2p.simulate.delay.latency

import io.libp2p.simulate.*
import java.util.Random
import kotlin.time.Duration

fun interface LatencyDistribution {

    fun getLatency(connection: SimConnection, rnd: Random): RandomValue<Duration>

    companion object {

        /**
         * Constant latency for every connection and over time
         */
        fun createConst(latency: Duration): LatencyDistribution =
            createRandomConst(RandomDistribution.const(latency))

        /**
         * Assigns a latency to a connection chosen at random which is then constant over time
         */
        fun createUniformConst(from: Duration, to: Duration): LatencyDistribution =
            createRandomConst(
                RandomDistribution
                    .uniform(from.inWholeMilliseconds, to.inWholeMilliseconds)
                    .milliseconds()
                    .named("[$from, $to)")
            )

        /**
         * Assigns a latency to a connection chosen at random from [distrib] which is then constant over time
         */
        fun createRandomConst(distrib: RandomDistribution<Duration>): LatencyDistribution =
            RandomConstLatencyDistribution(distrib)
    }
}

fun LatencyDistribution.named(name: String): LatencyDistribution =
    object : LatencyDistribution {
        override fun getLatency(connection: SimConnection, rnd: Random): RandomValue<Duration> =
            this@named.getLatency(connection, rnd)

        override fun toString(): String {
            return name
        }
    }