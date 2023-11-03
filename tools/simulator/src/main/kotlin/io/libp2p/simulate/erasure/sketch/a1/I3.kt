package io.libp2p.simulate.erasure.sketch.a1

interface I3<T1 : I1<T1, T2, T3>, T2: I2<T1, T2, T3>, T3 : I3<T1, T2, T3>/*, T4: I4<T4>*/> {

    var i1: T1
    var i2: T2
//    var i4: T4
}