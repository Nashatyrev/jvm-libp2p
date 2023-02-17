package io.libp2p.tools

import java.text.SimpleDateFormat
import java.time.Duration
import java.util.*
import kotlin.math.roundToLong

private val LOG_TIME_FORMAT = SimpleDateFormat("hh:mm:ss.SSS")

fun log(s: String) {
    println("[${LOG_TIME_FORMAT.format(Date())}] $s")
}

val Int.millis: Duration
    get() = Duration.ofMillis(this.toLong())
val Int.seconds: Duration
    get() = Duration.ofSeconds(this.toLong())
val Int.minutes: Duration
    get() = Duration.ofMinutes(this.toLong())

fun Int.pow(n: Int): Long {
    var t = 1L
    for (i in 0 until n) t *= this
    return t
}

fun Double.smartRound(meaningCount: Int = 3): Double {
    val d: Double = this
//    if (d.isNaN()) return this
    if (this <= 0.0) return this

    var cnt = 0
    var n = this
    val t = 10.pow(meaningCount)

    if (n < t) {
        while (n < t) {
            n *= 10
            cnt++
        }
        return n.roundToLong().toDouble() / 10.pow(cnt)
    } else {
        while (n > t * 10) {
            n /= 10
            cnt++
        }
        return n.roundToLong().toDouble() * 10.pow(cnt)
    }
}

operator fun <T> List<T>.get(subIndexes: IntRange) = subList(subIndexes.first, subIndexes.last + 1)
