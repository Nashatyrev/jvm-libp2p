package io.libp2p.simulate.util

fun <K, V> Collection<Map.Entry<K, V>>.toMap() = this.map { it.key to it.value }.toMap()

fun <T> Collection<T>.countValues(): Map<T, Int> = countValuesBy { it }

fun <T, K> Collection<T>.countValuesBy(keyExtractor: (T) -> K): Map<K, Int> =
    this.groupBy { keyExtractor(it) }.mapValues { (_, list) -> list.size }