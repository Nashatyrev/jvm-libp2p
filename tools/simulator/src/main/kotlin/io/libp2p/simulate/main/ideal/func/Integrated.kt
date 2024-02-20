package io.libp2p.simulate.main.ideal.func

import java.util.TreeMap

class Integrated<X: Comparable<X>, Y>(
    val scrFunction: (X) -> Y,
    val minusXFunction: (X, X) -> X,
    val mulXYFunction: (X, Y) -> Double,
    val integrationStep: X,
    val minX: X
) : Func<X, Double> {

    private val cache = TreeMap<X, Double>()
    override operator fun invoke(x: X): Double {
        val cachedVal = cache[x]
        if (cachedVal != null) {
            return cachedVal
        } else {
            val ceilEntry: Map.Entry<X, Double>? = cache.ceilingEntry(x)
            if (ceilEntry != null && ceilEntry.key > x && minusXFunction(ceilEntry.key, integrationStep) < x) {
                return ceilEntry.value
            }
        }
        val (minCachedX, cachedSum) = cache.maxByOrNull { it.key }?.toPair()
            ?: (minusXFunction(minX, integrationStep) to 0.0)
        val missedXs =
            generateSequence(x) { minusXFunction(it, integrationStep) }
                .takeWhile { it > minCachedX }
                .toList()
                .asReversed()

        var sum = cachedSum
        for (x in missedXs) {
            val srcVal = scrFunction(x)
            val intVal = mulXYFunction(integrationStep, srcVal)
            sum += intVal
            cache[x] = sum
        }

        return sum
    }
}