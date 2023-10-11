package io.libp2p.etc.types

class Union<T1, T2> {

    private var v1: T1? = null
        set(value) {
            requireNotNull(value)
            field = value
            v2 = null
        }
    private var v2: T2? = null
        set(value) {
            requireNotNull(value)
            field = value
            v1 = null
        }

    fun set(v: T1) = apply { v1 = v }
    fun set(v: T2) = apply { v2 = v }

    fun <R> map(t1Mapper: (T1) -> R, t2Mapper: (T2) -> R): R =
        if (v1 != null) t1Mapper(v1!!) else t2Mapper(v2!!)

    fun apply1(consumer: (T1) -> Unit) {
        requireNotNull(v1)
        consumer(v1!!)
    }

    fun apply2(consumer: (T2) -> Unit) {
        requireNotNull(v2)
        consumer(v2!!)
    }
}