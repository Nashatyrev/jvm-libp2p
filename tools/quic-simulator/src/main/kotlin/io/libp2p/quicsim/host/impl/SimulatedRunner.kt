package io.libp2p.quicsim.host.impl

import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.dsl.HostBuilder
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.transport.Transport
import io.libp2p.quicsim.core.Orchestrator
import io.libp2p.quicsim.core.SimCoreNet
import io.libp2p.quicsim.core.SimCoreNode
import io.libp2p.quicsim.core.SimCorePacket
import io.libp2p.quicsim.core.schedule.DeterministicScheduler
import io.libp2p.quicsim.host.NetworkContext
import io.libp2p.quicsim.host.NodeFactory
import io.libp2p.quicsim.host.NodeProgram
import io.libp2p.quicsim.host.SimContext
import io.libp2p.quicsim.host.SimNodeId
import io.libp2p.quicsim.network.SimNetworkEngine
import io.libp2p.quicsim.network.SimPacket
import io.libp2p.transport.quic.QuicTransport
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.AddressedEnvelope
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelId
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.socket.DatagramPacket
import io.netty.util.ReferenceCountUtil
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class SimulatedRunner(
    val nodeFactory: NodeFactory,
    val nodeCount: Int,
    val networkEngine: SimNetworkEngine,
    val listenIP: String = "127.0.0.1",
    val listenPortStartRange: Int = 17000,
    val nodeIdToNetworkNodeId: (SimNodeId) -> String = { "node-$it" },
) {
    private data class BoundChannel(
        val ownerNodeId: SimNodeId,
        val channel: SimDatagramChannel
    )

    private data class DatagramEnvelope(
        val sender: InetSocketAddress,
        val recipient: InetSocketAddress,
        val bytes: ByteArray
    )

    private data class UdpCorePacket(
        val srcNodeId: String,
        val dstNodeId: String,
        val envelope: DatagramEnvelope
    ) : SimCorePacket

    private class SimDatagramChannel(
        id: String,
        private val local: InetSocketAddress,
        handler: ChannelHandler
    ) : EmbeddedChannel(SimChannelId(id), handler) {
        override fun localAddress(): SocketAddress = local
        override fun remoteAddress(): SocketAddress? = null
    }

    private class SimChannelId(private val id: String) : ChannelId {
        override fun asShortText(): String = id
        override fun asLongText(): String = id
        override fun compareTo(other: ChannelId): Int = asLongText().compareTo(other.asLongText())
    }

    private inner class EmbeddedNode(
        val nodeId: SimNodeId,
        val networkNodeId: String,
        val scheduler: DeterministicScheduler
    ) : SimCoreNode {

        private var nextClientPort = 35000 + nodeId * 1_000
        private val channelsByPort = linkedMapOf<Int, SimDatagramChannel>()
        private val pendingOutbound = ArrayDeque<UdpCorePacket>()

        val listenAddress = InetSocketAddress(listenIP, listenPortStartRange + nodeId)

        fun bindServerParent(
            @Suppress("UNUSED_PARAMETER") bootstrap: Bootstrap,
            bindAddress: SocketAddress,
            handler: ChannelHandler
        ): CompletableFuture<Channel> {
            val local = bindAddress as InetSocketAddress
            val channel = SimDatagramChannel("sim-server-$nodeId-${local.port}", local, handler)
            registerChannel(local, channel)
            channel.bind(local).syncUninterruptibly()
            return CompletableFuture.completedFuture(channel)
        }

        fun bindClientParent(
            @Suppress("UNUSED_PARAMETER") bootstrap: Bootstrap,
            handler: ChannelHandler
        ): CompletableFuture<Channel> {
            val local = InetSocketAddress(listenIP, nextClientPort++)
            val channel = SimDatagramChannel("sim-client-$nodeId-${local.port}", local, handler)
            registerChannel(local, channel)
            channel.bind(local).syncUninterruptibly()
            return CompletableFuture.completedFuture(channel)
        }

        override fun deliver(inboundData: List<SimCorePacket>): List<SimCorePacket> {
            val inboundPackets = inboundData.mapNotNull { it as? UdpCorePacket }
            for (packet in inboundPackets) {
                val inboundChannel = channelsByPort[packet.envelope.recipient.port] ?: continue
                inboundChannel.writeInbound(
                    DatagramPacket(
                        Unpooled.wrappedBuffer(packet.envelope.bytes),
                        packet.envelope.recipient,
                        packet.envelope.sender
                    )
                )
            }
            runEmbeddedTasksAtCurrentTime()
            val outbound = ArrayList<SimCorePacket>(pendingOutbound.size)
            while (pendingOutbound.isNotEmpty()) {
                outbound += pendingOutbound.removeFirst()
            }
            return outbound
        }

        override fun advanceAndExecuteAll(advanceDuration: Duration) {
            require(!advanceDuration.isNegative()) { "advanceDuration must be non-negative" }
            scheduler.advanceAndExecuteAll(advanceDuration)
            advanceChannelsBy(advanceDuration)
            runEmbeddedTasksAtCurrentTime()
        }

        override fun nextTaskDuration(): Duration? {
            val candidates = mutableListOf<Duration>()
            scheduler.nextTaskDuration()?.also { candidates += it }
            channelsByPort.values.forEach { channel ->
                val nanos = channel.runScheduledPendingTasks()
                if (nanos >= 0) {
                    candidates += nanos.nanoseconds
                }
            }
            return candidates.minOrNull()
        }

        private fun registerChannel(address: InetSocketAddress, channel: SimDatagramChannel) {
            channelsByPort[address.port] = channel
            channelsByPortGlobal[address.port] = BoundChannel(nodeId, channel)
            channel.closeFuture().addListener {
                channelsByPort.remove(address.port)
                channelsByPortGlobal.remove(address.port)
            }
        }

        private fun advanceChannelsBy(duration: Duration) {
            val nanos = duration.inWholeNanoseconds
            channelsByPort.values.forEach {
                it.advanceTimeBy(nanos, TimeUnit.NANOSECONDS)
            }
        }

        private fun runEmbeddedTasksAtCurrentTime() {
            channelsByPort.values.forEach {
                it.runPendingTasks()
                it.runScheduledPendingTasks()
            }
            drainOutbound()
        }

        private fun drainOutbound() {
            channelsByPort.values.forEach { channel ->
                while (true) {
                    val msg = channel.readOutbound<Any>() ?: break
                    val sender = channel.localAddress() as InetSocketAddress
                    val recipient: InetSocketAddress
                    val bytes: ByteArray

                    when (msg) {
                        is DatagramPacket -> {
                            recipient = msg.recipient()
                            bytes = ByteArray(msg.content().readableBytes())
                            msg.content().getBytes(msg.content().readerIndex(), bytes)
                        }

                        is AddressedEnvelope<*, *> -> {
                            val envelopeRecipient = msg.recipient() as? InetSocketAddress
                            val content = msg.content() as? io.netty.buffer.ByteBuf
                            if (envelopeRecipient == null || content == null) {
                                ReferenceCountUtil.release(msg)
                                continue
                            }
                            recipient = envelopeRecipient
                            bytes = ByteArray(content.readableBytes())
                            content.getBytes(content.readerIndex(), bytes)
                        }

                        else -> {
                            ReferenceCountUtil.release(msg)
                            continue
                        }
                    }

                    val dstNode = channelsByPortGlobal[recipient.port]?.ownerNodeId
                    val dstNodeId = if (dstNode == null) {
                        ReferenceCountUtil.release(msg)
                        continue
                    } else {
                        nodeIdToNetworkNodeId(dstNode)
                    }
                    ReferenceCountUtil.release(msg)

                    pendingOutbound += UdpCorePacket(
                        srcNodeId = networkNodeId,
                        dstNodeId = dstNodeId,
                        envelope = DatagramEnvelope(sender, recipient, bytes)
                    )
                }
            }
        }
    }

    private inner class EngineCoreNet(
        override val allNodes: List<EmbeddedNode>
    ) : SimCoreNet {

        private val nodesById = allNodes.associateBy { it.networkNodeId }
        private var packetCounter = 0L

        override fun deliver(inboundData: List<SimCorePacket>): List<SimCorePacket> {
            val outbound = networkEngine.deliver(
                inboundData
                    .mapNotNull { it as? UdpCorePacket }
                    .map {
                        SimPacket(
                            id = ++packetCounter,
                            bytes = it.envelope.bytes.size,
                            srcNodeId = it.srcNodeId,
                            dstNodeId = it.dstNodeId,
                            payloadRef = it
                        )
                    }
            )
            return outbound
                .mapNotNull { it.payloadRef as? UdpCorePacket }
        }

        override fun getDestinationNode(packet: SimCorePacket): SimCoreNode {
            val udpPacket = packet as UdpCorePacket
            return nodesById[udpPacket.dstNodeId]
                ?: throw IllegalStateException("Unknown destination node: ${udpPacket.dstNodeId}")
        }

        override fun advanceAndExecuteAll(advanceDuration: Duration) {
            networkEngine.advanceAndExecuteAll(advanceDuration)
        }

        override fun nextTaskDuration(): Duration? = networkEngine.nextTaskDuration()
    }

    lateinit var nodePrograms: List<NodeProgram>
    lateinit var hosts: List<Host>
    lateinit var simContexts: List<SimContext>
    lateinit var networkContexts: List<NetworkContext>

    private lateinit var embeddedNodes: List<EmbeddedNode>
    private lateinit var orchestrator: Orchestrator
    private val channelsByPortGlobal = linkedMapOf<Int, BoundChannel>()

    fun createHosts() {
        nodePrograms = (0 until nodeCount).map(nodeFactory::createNode)

        embeddedNodes = nodePrograms.map { program ->
            val scheduler = DeterministicScheduler()
            EmbeddedNode(
                nodeId = program.simNodeId,
                networkNodeId = nodeIdToNetworkNodeId(program.simNodeId),
                scheduler = scheduler
            )
        }

        simContexts = embeddedNodes.map {
            SimContext(it.scheduler, it.scheduler)
        }

        hosts = embeddedNodes.indices.map { idx ->
            createHost(nodePrograms[idx], simContexts[idx], embeddedNodes[idx])
        }

        orchestrator = Orchestrator(EngineCoreNet(embeddedNodes))
    }

    private fun createHost(nodeProgram: NodeProgram, simContext: SimContext, node: EmbeddedNode): Host {
        val protocols = nodeProgram.createProtocols(simContext)
        val port = listenPortStartRange + nodeProgram.simNodeId

        val transportFactory = BiFunction<PrivKey, List<ProtocolBinding<*>>, Transport> { key, selectedProtocols ->
            QuicTransport(
                key,
                "ECDSA",
                selectedProtocols,
                node::bindClientParent,
                node::bindServerParent
            )
        }

        return HostBuilder(HostBuilder.DefaultMode.None)
            .keyType(KeyType.ED25519)
            .secureTransport(transportFactory)
            .protocol(*(protocols.toTypedArray()))
            .listen("/ip4/$listenIP/udp/$port/quic-v1")
            .builderModifier { nodeProgram.modifyBuilder(simContext, it) }
            .build()
    }

    fun startHosts() {
        val startFutures = hosts.map { it.start() }
        runUntil({ startFutures.all { it.isDone } }, 60.seconds)
        startFutures.forEach { it.get(1, TimeUnit.SECONDS) }
    }

    fun startPrograms() {
        val allListenAddresses = hosts.indices.associateWith { idx ->
            hosts[idx].listenAddresses().firstOrNull()
                ?: Multiaddr("/ip4/$listenIP/udp/${listenPortStartRange + idx}/quic-v1")
        }
        networkContexts = hosts.map { NetworkContext(it, allListenAddresses) }

        nodePrograms.indices.forEach { i ->
            nodePrograms[i].start(simContexts[i], networkContexts[i])
        }
    }

    fun run() {
        val startedAt = System.currentTimeMillis()
        fun log(msg: String) = println("[+${System.currentTimeMillis() - startedAt}ms] $msg")

        log("Creating hosts...")
        createHosts()
        log("Starting hosts...")
        startHosts()
        log("Starting programs...")
        startPrograms()
        log("Running simulated event loop...")

        var ticks = 0
        while (true) {
            orchestrator.pumpPackets()
            val completeCount = nodePrograms.count { it.isComplete() }
            if (completeCount == nodePrograms.size) {
                break
            }

            val nextDelay = orchestrator.nextTaskDuration()
            if (nextDelay == null) {
                throw IllegalStateException("Simulation stalled: no pending tasks but only $completeCount/${nodePrograms.size} programs completed")
            }
            val step = if (nextDelay == ZERO) 1.milliseconds else nextDelay
            orchestrator.advanceAndExecuteAll(step)

            ticks++
            if (ticks % 100 == 0) {
                log("$completeCount of ${nodePrograms.size} completed")
            }
        }

        log("All complete...")
    }

    private fun runUntil(done: () -> Boolean, timeout: Duration) {
        var elapsed = ZERO
        while (!done()) {
            if (elapsed > timeout) {
                throw IllegalStateException("Condition not reached in simulated loop within $timeout")
            }

            orchestrator.pumpPackets()
            val nextDelay = orchestrator.nextTaskDuration()
            if (nextDelay == null) {
                break
            }
            val step = if (nextDelay == ZERO) 1.milliseconds else nextDelay
            orchestrator.advanceAndExecuteAll(step)
            elapsed += step
        }
        check(done()) { "Condition was not reached in simulated protocol loop" }
    }
}
