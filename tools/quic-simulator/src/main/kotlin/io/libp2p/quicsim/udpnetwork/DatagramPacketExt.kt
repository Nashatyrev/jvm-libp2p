package io.libp2p.quicsim.udpnetwork

import io.netty.channel.socket.DatagramPacket

fun DatagramPacket.udpSimBytes(): Int =
    content().readableBytes()

fun DatagramPacket.udpSimSourceNodeId(): String =
    sender().hostString

fun DatagramPacket.udpSimDestinationNodeId(): String =
    recipient().hostString

fun DatagramPacket.udpSimFlowKey(): String =
    "${udpSimSourceNodeId()}->${udpSimDestinationNodeId()}"
