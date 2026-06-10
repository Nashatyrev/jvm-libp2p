package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.schedule.Controllable
import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.core.schedule.impl.NanoMonotonicTimer
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import io.netty.buffer.ByteBuf
import io.netty.channel.socket.DatagramPacket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

abstract class AbstractSimPacketBridge(
    idAndIp: Collection<IdMapEntry>,
) : Controllable {

    data class IdMapEntry(
        val nodeId: String,
        val ip: String
    )

    protected var nanosPassed = AtomicLong(0)
    val monotonicTimer: MonotonicTimer = NanoMonotonicTimer(nanosPassed::get)

    protected val idToIp = idAndIp.associate { it.nodeId to it.ip }
    protected val ipToId = idAndIp.associate { it.ip to it.nodeId }

    private val packetIdCounter = AtomicLong()
    protected val nettyDatagramToSimUdpPacketConverter: (DatagramPacket) -> UdpSimPacket =
        { datagramPacket ->
            UdpSimPacket(
                id = packetIdCounter.incrementAndGet(),
                bytes = datagramPacket.content().readableBytes(),
                srcNodeId = ipToId[datagramPacket.sender().hostString]!!,
                dstNodeId = ipToId[datagramPacket.recipient().hostString]!!,
                srcPort = datagramPacket.sender().port,
                dstPort = datagramPacket.recipient().port,
                payloadRef = datagramPacket.content()
            )
        }
    protected val simUdpPacketToNettyDatagramConverter: (UdpSimPacket) -> DatagramPacket =
        { simPacket ->
            DatagramPacket(
                simPacket.payloadRef as ByteBuf,
                InetSocketAddress(idToIp[simPacket.dstNodeId]!!, simPacket.dstPort),
                InetSocketAddress(idToIp[simPacket.srcNodeId]!!, simPacket.srcPort),
            )
        }

    override fun advance(advanceDuration: Duration) {
        nanosPassed.updateAndGet { it + advanceDuration.inWholeNanoseconds }
        advanceImpl(advanceDuration)
    }

    abstract fun advanceImpl(advanceDuration: Duration)
    fun close() {}
}
