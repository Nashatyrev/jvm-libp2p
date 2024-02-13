package io.libp2p.simulate.main.ideal

class Integrated<X: Comparable<X>, Y>(
    val scrFunction: (X) -> Y,
    val minusXFunction: (X, X) -> X,
    val mulXYFunction: (X, Y) -> Double,
    val integrationStep: X,
    val minX: X
) : DisseminationFunction.Func<X, Double> {

    private val cache = mutableMapOf<X, Double>()
    override operator fun invoke(x: X): Double {
        val cachedVal = cache[x]
        if (cachedVal != null) {
            return cachedVal
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