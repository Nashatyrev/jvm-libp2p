package io.libp2p.quicsim.sim.impl

import io.libp2p.etc.types.toCompletableFuture
import io.libp2p.quicsim.core.DispatchingPacketProcessor
import io.libp2p.quicsim.core.schedule.AggregateControllable
import io.libp2p.quicsim.core.schedule.DeterministicScheduler
import io.libp2p.quicsim.core.schedule.impl.NettyTicker
import io.libp2p.quicsim.sim.SimNode
import io.libp2p.quicsim.sim.SimNodeId
import io.libp2p.quicsim.sim.impl.netty.SimDatagramChannel
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.socket.DatagramPacket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration

class SimNodeImpl(
    val nodeId: SimNodeId,
    val ip: String,
    val scheduler: DeterministicScheduler
) : SimNode<DatagramPacket> {

    private var nextClientPort = 35000
    private val channelsByPort =
        mutableMapOf<Int, EmbeddedChannelDatagramPacketProcessor>()
    private val dispatchingPacketProcessor =
        DispatchingPacketProcessor(channelsByPort) { it.recipient().port }
    private val aggregateControllable =
        AggregateControllable(listOf(scheduler, dispatchingPacketProcessor))
    private val nettyTicker = NettyTicker(scheduler)

    fun bindServerParent(bindAddress: SocketAddress,handler: ChannelHandler): CompletableFuture<Channel> =
        bindParent(handler, bindAddress as InetSocketAddress)

    fun bindClientParent(handler: ChannelHandler): CompletableFuture<Channel> =
        bindParent(handler, InetSocketAddress(ip, nextClientPort++))

    private fun bindParent(
        handler: ChannelHandler,
        addr: InetSocketAddress
    ): CompletableFuture<Channel> {
        val channel = SimDatagramChannel("sim-$nodeId-${addr.port}", addr, handler, nettyTicker)
        registerChannel(addr, channel)
        val bindFuture = channel.bind(addr)
        return bindFuture.toCompletableFuture()
    }

    override fun deliver(inboundData: List<DatagramPacket>): List<DatagramPacket> =
        dispatchingPacketProcessor.deliver(inboundData)

    override fun advanceAndExecuteAll(advanceDuration: Duration) {
        aggregateControllable.advanceAndExecuteAll(advanceDuration)
    }

    override fun nextTaskDuration(): Duration? =
        aggregateControllable.nextTaskDuration()


    private fun registerChannel(address: InetSocketAddress, channel: SimDatagramChannel) {
        channelsByPort[address.port] = EmbeddedChannelDatagramPacketProcessor(channel)
        channel.closeFuture().addListener {
            channelsByPort.remove(address.port)
        }
    }
}
