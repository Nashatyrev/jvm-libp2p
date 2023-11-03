package io.libp2p.simulate.erasure.sketch.a1

interface I2<T1 : I1<T1, T2, T3>, T2: I2<T1, T2, T3>, T3 : I3<T1, T2, T3>> {

    var i1: T1
    var i3: T3
}