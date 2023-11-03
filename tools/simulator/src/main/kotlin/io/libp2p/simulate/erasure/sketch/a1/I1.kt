package io.libp2p.simulate.erasure.sketch.a1

interface I1<T1 : I1<T1, T2, T3>, T2: I2<T1, T2, T3>, T3 : I3<T1, T2, T3>> {
//interface I1<T1 : TI1, T2: TI2, T3 : TI3> {

    var i2: T2
    var i3: T3
}