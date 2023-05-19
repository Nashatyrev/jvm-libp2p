package io.libp2p.simulate.util

data class Tuple4<out T1, out T2, out T3, out T4>(
    val v1: T1,
    val v2: T2,
    val v3: T3,
    val v4: T4
)

data class Tuple5<out T1, out T2, out T3, out T4, out T5>(
    val v1: T1,
    val v2: T2,
    val v3: T3,
    val v4: T4,
    val v5: T5
)

data class Tuple6<out T1, out T2, out T3, out T4, out T5, out T6>(
    val v1: T1,
    val v2: T2,
    val v3: T3,
    val v4: T4,
    val v5: T5,
    val v6: T6
)

data class Tuple7<out T1, out T2, out T3, out T4, out T5, out T6, out T7>(
    val v1: T1,
    val v2: T2,
    val v3: T3,
    val v4: T4,
    val v5: T5,
    val v6: T6,
    val v7: T7
)

data class Tuple8<out T1, out T2, out T3, out T4, out T5, out T6, out T7, out T8>(
    val v1: T1,
    val v2: T2,
    val v3: T3,
    val v4: T4,
    val v5: T5,
    val v6: T6,
    val v7: T7,
    val v8: T8
)

data class Tuple9<out T1, out T2, out T3, out T4, out T5, out T6, out T7, out T8, out T9>(
    val v1: T1,
    val v2: T2,
    val v3: T3,
    val v4: T4,
    val v5: T5,
    val v6: T6,
    val v7: T7,
    val v8: T8,
    val v9: T9
)

data class Tuple10<out T1, out T2, out T3, out T4, out T5, out T6, out T7, out T8, out T9, out T10>(
    val v1: T1,
    val v2: T2,
    val v3: T3,
    val v4: T4,
    val v5: T5,
    val v6: T6,
    val v7: T7,
    val v8: T8,
    val v9: T9,
    val v10: T10
)

data class Tuple11<out T1, out T2, out T3, out T4, out T5, out T6, out T7, out T8, out T9, out T10, out T11>(
    val v1: T1,
    val v2: T2,
    val v3: T3,
    val v4: T4,
    val v5: T5,
    val v6: T6,
    val v7: T7,
    val v8: T8,
    val v9: T9,
    val v10: T10,
    val v11: T11
)

data class Tuple12<out T1, out T2, out T3, out T4, out T5, out T6, out T7, out T8, out T9, out T10, out T11, out T12>(
    val v1: T1,
    val v2: T2,
    val v3: T3,
    val v4: T4,
    val v5: T5,
    val v6: T6,
    val v7: T7,
    val v8: T8,
    val v9: T9,
    val v10: T10,
    val v11: T11,
    val v12: T12
)

data class Tuple13<out T1, out T2, out T3, out T4, out T5, out T6, out T7, out T8, out T9, out T10, out T11, out T12, out T13>(
    val v1: T1,
    val v2: T2,
    val v3: T3,
    val v4: T4,
    val v5: T5,
    val v6: T6,
    val v7: T7,
    val v8: T8,
    val v9: T9,
    val v10: T10,
    val v11: T11,
    val v12: T12,
    val v13: T13
)

data class Tuple14<out T1, out T2, out T3, out T4, out T5, out T6, out T7, out T8, out T9, out T10, out T11, out T12, out T13, out T14>(
    val v1: T1,
    val v2: T2,
    val v3: T3,
    val v4: T4,
    val v5: T5,
    val v6: T6,
    val v7: T7,
    val v8: T8,
    val v9: T9,
    val v10: T10,
    val v11: T11,
    val v12: T12,
    val v13: T13,
    val v14: T14
)

data class Tuple15<out T1, out T2, out T3, out T4, out T5, out T6, out T7, out T8, out T9, out T10, out T11, out T12, out T13, out T14, out T15>(
    val v1: T1,
    val v2: T2,
    val v3: T3,
    val v4: T4,
    val v5: T5,
    val v6: T6,
    val v7: T7,
    val v8: T8,
    val v9: T9,
    val v10: T10,
    val v11: T11,
    val v12: T12,
    val v13: T13,
    val v14: T14,
    val v15: T15
)

data class Tuple16<out T1, out T2, out T3, out T4, out T5, out T6, out T7, out T8, out T9, out T10, out T11, out T12, out T13, out T14, out T15, out T16>(
    val v1: T1,
    val v2: T2,
    val v3: T3,
    val v4: T4,
    val v5: T5,
    val v6: T6,
    val v7: T7,
    val v8: T8,
    val v9: T9,
    val v10: T10,
    val v11: T11,
    val v12: T12,
    val v13: T13,
    val v14: T14,
    val v15: T15,
    val v16: T16
)

// src generator
fun main() {
    for (n in 4..16) {
        println("""
data class Tuple${n}<${(1..n).map { "out T$it" }.joinToString(", ")}> (
${
            (1..n)
                .map { i ->
                    "val v$i: T$i"
                }
                .joinToString(",\n")
                .prependIndent("    ")
        }
)
        """.trimIndent())
    }
}