package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.ControllablePacketPump
import io.libp2p.quicsim.sim.SimNet
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkEngine
import io.netty.channel.socket.DatagramPacket
import kotlin.time.Duration

class SimpleSimPacketBridge(simNet: SimNet<DatagramPacket>,
                            udpNet: UdpSimNetworkEngine, idAndIp: Collection<IdMapEntry>
) : AbstractSimPacketBridge(udpNet, idAndIp) {

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