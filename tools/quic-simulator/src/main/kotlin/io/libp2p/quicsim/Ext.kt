package io.libp2p.quicsim

fun <T : Comparable<T>> minOrNUll(that: T?, other: T?): T? =
    when {
        that == null -> other
        other == null -> that
        that <= other -> that
        else -> other
    }