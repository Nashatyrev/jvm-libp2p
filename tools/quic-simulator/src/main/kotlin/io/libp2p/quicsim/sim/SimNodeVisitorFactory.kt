package io.libp2p.quicsim.sim

import io.libp2p.quicsim.core.PacketProcessorVisitor

fun interface SimNodeVisitorFactory<T> {

    fun create(ip: String): PacketProcessorVisitor<T>
}