package io.libp2p.simulate.erasure.sketch.a1

//typealias TI1 = I1<TI1, TI2, TI3>
//typealias TI2 = I2<TI1, TI2, TI3>
//typealias TI3 = I3<TI1, TI2, TI3>
//
//interface I5<T3: TI3> {
//
//    var i3: T3
//}

interface I5_<
        T3 : I3<TI1__, TI2__, T3>,
        TI1__ : I1<TI1__, TI2__, T3>,
        TI2__ : I2<TI1__, TI2__, T3>
    > {

    var i3: T3
}

interface I5_1_<
        out T3 : I3<TI1__, TI2__, out T3>,
        TI1__ : I1<TI1__, TI2__, out T3>,
        TI2__ : I2<TI1__, TI2__, out T3>
        > {

    val i3: T3
}
