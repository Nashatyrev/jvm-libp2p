package io.libp2p.simulate.erasure.sketch2

interface Box<T> {

    val v: T
}

open class BoxImpl<T> : Box<T> {
    override val v: T = TODO()
}