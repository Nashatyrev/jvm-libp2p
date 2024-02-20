package io.libp2p.simulate.main.ideal.func

fun interface Func<X, Y> {
    operator fun invoke(x: X): Y
}

class CachedFunc<K, V>(
    private val f: Func<K, V>
) : Func<K, V> {
    private val cache = mutableMapOf<K, V>()

    override operator fun invoke(x: K): V {
        val maybeCached = cache[x]
        return when(maybeCached) {
            null -> {
                val v = f(x)
                cache[x] = v
                v
            }
            else -> maybeCached
        }
    }
}