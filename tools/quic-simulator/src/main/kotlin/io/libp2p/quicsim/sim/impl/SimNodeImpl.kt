package io.libp2p.quicsim.sim.impl

import io.libp2p.etc.types.toCompletableFuture
import io.libp2p.quicsim.core.DispatchingPacketProcessor
import io.libp2p.quicsim.core.PacketProcessorVisitor
import io.libp2p.quicsim.core.schedule.AggregateControllable
import io.libp2p.quicsim.core.schedule.DeterministicScheduler
import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.core.schedule.impl.NettyTicker
import io.libp2p.quicsim.sim.SimNode
import io.libp2p.quicsim.sim.SimNodeId
import io.libp2p.quicsim.sim.impl.netty.SimDatagramChannel
import io.netty.buffer.ByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.socket.DatagramPacket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration

class SimNodeImpl(
    val nodeId: SimNodeId,
    override val ip: String,
    val scheduler: DeterministicScheduler,
    val nodeVisitor: PacketProcessorVisitor<DatagramPacket> = PacketProcessorVisitor.none()
) : SimNode<DatagramPacket> {

    private var nextClientPort = 35000
    private val channelsByPort =
        mutableMapOf<Int, EmbeddedChannelDatagramPacketProcessor>()
    private val dispatchingPacketProcessor =
        DispatchingPacketProcessor(channelsByPort) { it.recipient().port }
    private val aggregateControllable =
        AggregateControllable(listOf(scheduler, dispatchingPacketProcessor))
    private var cachedNextTaskDuration: Duration? = null

    fun bindServerParent(
        bindAddress: SocketAddress,
        handler: ChannelHandler,
        allocator: ByteBufAllocator? = null
    ): CompletableFuture<Channel> =
        bindParent(handler, bindAddress as InetSocketAddress, allocator)

    fun bindClientParent(
        handler: ChannelHandler,
        allocator: ByteBufAllocator? = null
    ): CompletableFuture<Channel> =
        bindParent(handler, InetSocketAddress(ip, nextClientPort++), allocator)

    private fun bindParent(
        handler: ChannelHandler,
        addr: InetSocketAddress,
        allocator: ByteBufAllocator?
    ): CompletableFuture<Channel> {
        val channel = SimDatagramChannel("sim-$nodeId-${addr.port}", addr, handler, allocator)
        registerChannel(addr, channel)
        val bindFuture = channel.bind(addr)
        return bindFuture.toCompletableFuture()
    }

    override fun receivePackets(packets: List<DatagramPacket>) {
        cachedNextTaskDuration = null
        packets.forEach { nodeVisitor.onDeliverInbound(it) }
        dispatchingPacketProcessor.receivePackets(packets)
    }

    override fun emitPackets(): List<DatagramPacket> {
        val packets = dispatchingPacketProcessor.emitPackets()
        packets.forEach { nodeVisitor.onDeliverOutbound(it) }
        return packets
    }

    override fun advance(advanceDuration: Duration) {
        cachedNextTaskDuration = null
        aggregateControllable.advance(advanceDuration)
        nodeVisitor.onAdvance(advanceDuration)
    }

    override fun executePending() {
        cachedNextTaskDuration = null
        aggregateControllable.executePending()
        nodeVisitor.onExecutePending()
    }

    override fun nextTaskDuration(): Duration? {
        if (cachedNextTaskDuration == null) {
            cachedNextTaskDuration = aggregateControllable.nextTaskDuration()
        }
        nodeVisitor.onNextTaskDuration(cachedNextTaskDuration)
        return cachedNextTaskDuration
    }

    private fun registerChannel(address: InetSocketAddress, channel: SimDatagramChannel) {
        channelsByPort[address.port] = EmbeddedChannelDatagramPacketProcessor(channel)
        channel.closeFuture().addListener {
            channelsByPort.remove(address.port)
        }
    }

    override val nodeTime: MonotonicTimer get() = scheduler
}
