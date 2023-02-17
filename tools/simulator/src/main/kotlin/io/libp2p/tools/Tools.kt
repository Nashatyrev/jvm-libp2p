package io.libp2p.tools

import java.text.SimpleDateFormat
import java.time.Duration
import java.util.*
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.reflect.full.memberProperties

private val LOG_TIME_FORMAT = SimpleDateFormat("hh:mm:ss.SSS")

fun log(s: String) {
    println("[${LOG_TIME_FORMAT.format(Date())}] $s")
}

operator fun <T> List<T>.get(subIndexes: IntRange) = subList(subIndexes.first, subIndexes.last + 1)

val Int.millis: Duration
    get() = Duration.ofMillis(this.toLong())
val Int.seconds: Duration
    get() = Duration.ofSeconds(this.toLong())
val Int.minutes: Duration
    get() = Duration.ofMinutes(this.toLong())

fun <K, V> Map<K, V>.setKeys(f: (K) -> K): Map<K, V> = asSequence().map { f(it.key) to it.value }.toMap()

operator fun <K, V> Map<K, V>.plus(other: Map<K, V>): Map<K, V> =
    (asSequence() + other.asSequence()).map { it.key to it.value }.toMap()

fun <K, V> List<Map<K, V>>.transpose(): Map<K, List<V>> = flatMap { it.asIterable() }.groupBy({ it.key }, { it.value })
fun <K, V> Map<K, List<V>>.transpose(): List<Map<K, V>> {
    val list = asSequence()
        .toList()
        .flatMap { kv ->
            kv.value.mapIndexed { i, v ->
                kv.key to (i to v)
            }
        }
    val indexedMap = list.groupBy { it.second.first }
    val ret = indexedMap.map { it.value.associate { it.first to it.second.second } }
    return ret
}

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

fun String.align(width: Int, alignLeft: Boolean = true, fillChar: Char = ' '): String {
    val n = max(1, width - length)
    return if (alignLeft) this + fillChar.toString().repeat(n) else fillChar.toString().repeat(n) + this
}

fun String.formatTable(firstLineHeaders: Boolean = true, separator: String = "\t", alignLeft: Boolean = true): String {
    val list = this.split("\n").map { it.split(separator) }
    require(list.map { it.size }.minOrNull() == list.map { it.size }.maxOrNull()) { "Different number of columns" }
    val colSizes = list[0].indices.map { col -> list.map { it[col].length + 1 }.maxOrNull() }
    val strings = list.map { raw ->
        raw.indices.map { raw[it].align(colSizes[it]!!, alignLeft) }
            .joinToString("")
    }.toMutableList()

    if (firstLineHeaders) {
        strings.add(1, colSizes.map { "-".repeat(it!! - 1) + " " }.joinToString(""))
    }
    return strings.joinToString("\n")
}

fun Any.propertiesAsMap() = javaClass.kotlin.memberProperties.map { it.name to it.get(this) }.toMap()
