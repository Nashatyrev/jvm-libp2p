package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.ControllablePacketPump
import io.libp2p.quicsim.core.MappingPacketProcessor.Companion.map
import io.libp2p.quicsim.core.PacketProcessor
import io.libp2p.quicsim.sim.SimNet
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkEngine
import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl2
import io.netty.channel.socket.DatagramPacket
import kotlin.time.Duration

class SimpleSimPacketBridge(
    simNet: SimNet<DatagramPacket>,
    udpNet: UdpSimNetwork,
    idAndIp: Collection<IdMapEntry>
) : AbstractSimPacketBridge(idAndIp) {

    private val udpNetworkEngine = UdpSimNetworkEngineImpl2(udpNet)
    private val udpNetConverted: PacketProcessor<DatagramPacket> = udpNetworkEngine
        .map(
            mapToInner = nettyDatagramToSimUdpPacketConverter,
            mapToOuter = simUdpPacketToNettyDatagramConverter
        )

    val pump = ControllablePacketPump(simNet, udpNetConverted)

    override fun advanceImpl(advanceDuration: Duration) {
        pump.advance(advanceDuration)
    }

    override fun executePending() {
        pump.executePending()
    }

    override fun nextTaskDuration(): Duration? =
        pump.nextTaskDuration()
}