package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.ControllablePacketPump
import io.libp2p.quicsim.core.MappingPacketProcessor
import io.libp2p.quicsim.core.schedule.Controllable
import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.core.schedule.impl.NanoMonotonicTimer
import io.libp2p.quicsim.sim.SimNet
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkEngine
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import io.netty.buffer.ByteBuf
import io.netty.channel.socket.DatagramPacket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

class SimPacketPump(
    simNet: SimNet<DatagramPacket>,
    udpNet: UdpSimNetworkEngine,
    idAndIp: Collection<IdMapEntry>,
) : Controllable {

    private var nanosPassed = AtomicLong(0)
    val monotonicTimer: MonotonicTimer = NanoMonotonicTimer(nanosPassed::get)

    data class IdMapEntry(
        val nodeId: String,
        val ip: String
    )

    val idToIp = idAndIp.associate { it.nodeId to it.ip }
    val ipToId = idAndIp.associate { it.ip to it.nodeId }

    val packetIdCounter = AtomicLong()
    val udpNetConverted =
        MappingPacketProcessor<DatagramPacket, UdpSimPacket>(
            udpNet,
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
            },
            { simPacket ->
                DatagramPacket(
                    simPacket.payloadRef as ByteBuf,
                    InetSocketAddress(idToIp[simPacket.dstNodeId]!!, simPacket.dstPort),
                    InetSocketAddress(idToIp[simPacket.srcNodeId]!!, simPacket.srcPort),
                )
            })

    val pump = ControllablePacketPump(simNet, udpNetConverted)

    override fun advanceAndExecuteAll(advanceDuration: Duration) {
        nanosPassed.updateAndGet { it + advanceDuration.inWholeNanoseconds }
        pump.advanceAndExecuteAll(advanceDuration)
    }

    override fun nextTaskDuration(): Duration? =
        pump.nextTaskDuration()
}