package io.libp2p.quicsim.core

import java.util.NavigableMap
import java.util.TreeMap
import kotlin.collections.component1
import kotlin.collections.component2

class ValueSortedMap<K, V, TValueKey : Comparable<TValueKey>>(
    map: Map<K, V>,
    private val valueKeyExtractor: (V) -> TValueKey,
) {

    data class SortKey<TValueKey : Comparable<TValueKey>>(
        val sortKey: TValueKey,
        val id: Int,
    ) : Comparable<SortKey<TValueKey>> {
        override fun compareTo(other: SortKey<TValueKey>): Int {
            val s = sortKey.compareTo(other.sortKey)
            return if (s != 0) {
                s
            } else {
                id.compareTo(other.id)
            }
        }
    }

    private fun <T : Comparable<T>> IndexedValue<T>.toSortKey() =
        SortKey(this.value, this.index)

    private val privMap =
        map.entries.mapIndexed { index, entry ->
            entry.key to IndexedValue(index, entry.value)
        }.toMap()

    private val valueSortedMap: NavigableMap<SortKey<TValueKey>, V> = TreeMap<SortKey<TValueKey>, V>()
        .also { sortMap ->
            privMap.values.forEach { v ->
                sortMap[SortKey(valueKeyExtractor(v.value), v.index)] = v.value
            }
        }

    fun updateByKey(key: K, updater: (V) -> Unit) {
        val v = privMap[key]!!
        val oldK = SortKey(valueKeyExtractor(v.value), v.index)
        updater(v.value)
        valueSortedMap -= oldK
        val newK = SortKey(valueKeyExtractor(v.value), v.index)
        valueSortedMap[newK] = v.value
    }

    fun <R> updateFirst(updater: (V) -> R): R {
        val (oldK, v) = valueSortedMap.firstEntry()!!
        val ret = updater(v)
        valueSortedMap -= oldK
        val newK = SortKey(valueKeyExtractor(v), oldK.id)
        valueSortedMap[newK] = v
        return ret
    }

    fun getFirst(): V = valueSortedMap.firstEntry()!!.value

}