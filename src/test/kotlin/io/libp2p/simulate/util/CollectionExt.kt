package io.libp2p.simulate.util

fun <K, V> Collection<Map.Entry<K, V>>.toMap() = this.map { it.key to it.value }.toMap()