package io.libp2p.simulate.erasure.sketch2

interface Boxer<T, TBox : Box<T>> {

    fun unbox(box: TBox): T
    fun box(v: T): TBox

    companion object {
        fun <T, TBox : Box<T>>create(): Boxer<T, TBox> = TODO()
    }
}

//interface BoxedList<TBox: Box<*>> : List<TBox> {
//
//}

class IntBox : BoxImpl<Int>()
class StringBox : BoxImpl<String>()

val l: List<Boxer<Int, IntBox>> = TODO()

fun f() {
    val v0 = l[0]
    val r0 = v0.unbox(IntBox())

    val b: Boxer<Int, Box<Int>>

    val c = Boxer.create<Int, Box<Int>>()

}


interface A1<T : Number> {
    var t: T
}
interface A2<out T> {
    val rt: T
//    var wt: T  // ERROR
}
interface A3<in T> {
//    var t: T  // ERROR
//    val t: T  // ERROR
    fun setT(t: T)
}

lateinit var a1_1: A1<Number>
//var a1_2: A1<Int> = a1_1  // ERROR
//var a1_3: A1<Any> = a1_1  // ERROR
fun <T : Number> f1_1(a: A1<T>) {
    a.t = a.t
    f1_1(a)
    f1_2(a)
    f1_3(a)
    f1_4(a)
}
fun <T : Number> f1_2(a: A1<out T>) {
//    a.t = 1 // error
    val e = a.t
    f1_1(a)
    f1_2(a)
    f1_3(a)
    f1_4(a)
}
fun <T : Number> f1_3(a: A1<in T>) {
//    a.t = 1 // error
    val e = a.t
    f1_1(a)
    f1_2(a)
    f1_3(a)
    f1_4(a)
}
fun f1_4(a: A1<*>) {
    f1_1(a)
    f1_2(a)
    f1_3(a)
    f1_4(a)
}

lateinit var a2_1: A2<Number>
//var a2_2: A2<Int> = a2_1  // ERROR
var a2_3: A2<Any> = a2_1
fun <T> f2_1(a: A2<T>) {
    f2_1(a)
    f2_2(a)
}
fun <T> f2_2(a: A2<out T>) {
    f2_1(a)
    f2_2(a)
}
// fun <T> f2_3(a: A2<in T>)  // ERROR
fun f2_4(a: A2<*>) {
    f2_1(a)
    f2_2(a)
}

lateinit var a3_1: A3<Number>
var a3_2: A3<Int> = a3_1  // ERROR
//var a3_3: A3<Any> = a3_1  // ERROR
