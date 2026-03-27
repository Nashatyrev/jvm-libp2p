package io.libp2p.quicsim.runner

import io.libp2p.core.Host
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.HostBuilder
import io.libp2p.quicsim.core.schedule.impl.NanoMonotonicTimer
import io.libp2p.quicsim.core.schedule.impl.toSimpleScheduler
import io.libp2p.quicsim.program.NodeProgram
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.program.SampleGossipNodeProgram
import io.libp2p.quicsim.sim.NetworkContext
import io.libp2p.quicsim.sim.SimContext
import io.libp2p.transport.quic.QuicTransport
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalRealRunner(
    val nodeFactory: NodeProgramFactory,
    val nodeCount: Int,
    val listenIP: String = "127.0.0.1",
    val listenPortStartRange: Int = 17000
) {
    lateinit var nodePrograms: List<NodeProgram>
    lateinit var hosts: List<Host>
    lateinit var simContexts: List<SimContext>
    lateinit var networkContexts: List<NetworkContext>

    fun createHosts() {
        nodePrograms = (0 until nodeCount).map {
            nodeFactory.createNode(it)
        }

        simContexts = (0 until nodeCount).map {
            val scheduler = Executors.newSingleThreadScheduledExecutor().toSimpleScheduler()
            SimContext(scheduler, NanoMonotonicTimer.Companion.CPU)
        }

        hosts = (0 until nodeCount).map { idx ->
            createHost(nodePrograms[idx], simContexts[idx])
        }
    }

    fun createHost(nodeProgram: NodeProgram, simContext: SimContext): Host {

        val protocols = nodeProgram.createProtocols(simContext)
        val port = listenPortStartRange + nodeProgram.simNodeId

        return HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport.Companion::ECDSA)
            .protocol(*(protocols.toTypedArray()))
            .listen("/ip4/$listenIP/udp/$port/quic-v1")
            .builderModifier { nodeProgram.modifyBuilder(simContext, it) }
            .build()

    }

    fun startHosts() {
        val allStartFuts = hosts.map { it.start() }
        CompletableFuture.allOf(*allStartFuts.toTypedArray()).get(60, TimeUnit.SECONDS)
    }

    fun startPrograms(): CompletableFuture<Void> {
        val allListenAddresses = hosts.indices.associateWith {
            hosts[it].listenAddresses().first()
        }
        networkContexts = hosts.map { NetworkContext(it, allListenAddresses) }

        val futures = nodePrograms.indices.map { i ->
            nodePrograms[i].start(simContexts[i], networkContexts[i])
        }

        return CompletableFuture.allOf(*futures.toTypedArray())
    }

    fun run() {
        val startedAt = System.currentTimeMillis()
        fun log(msg: String) = println("[+${System.currentTimeMillis() - startedAt}ms] $msg")

        log("Creating hosts...")
        createHosts()
        log("Starting hosts...")
        startHosts()
        log("Starting programs...")
        val startFuture = startPrograms()
        log("Waiting all programs to start...")
        startFuture.get(60, TimeUnit.SECONDS)
        log("Waiting all programs to complete...")
        var ticks = 0
        var prevCompleteCount = -1
        val prevRemotesByNode = mutableMapOf<Int, Set<String>>()
        while (true) {
            Thread.sleep(1000)
            ticks += 1
            val completeCount = nodePrograms.count { it.isComplete() }
            if (completeCount == nodeCount) {
                break
            }
            hosts.indices.forEach { idx ->
                val nodeId = nodePrograms[idx].simNodeId
                val currentRemotes = hosts[idx].network.connections.map { "${it.remoteAddress()}" }.toSet()
                val prevRemotes = prevRemotesByNode[nodeId] ?: emptySet()
                val disconnectedRemotes = prevRemotes - currentRemotes
                val connectedRemotes = currentRemotes - prevRemotes
                disconnectedRemotes.forEach {
                    log("disconnect node=$nodeId remote=$it")
                }
                connectedRemotes.forEach {
                    log("connect node=$nodeId remote=$it")
                }
                prevRemotesByNode[nodeId] = currentRemotes
            }
            if (completeCount != prevCompleteCount || ticks % 5 == 0) {
                log("$completeCount of ${nodePrograms.size} completed so far")
                hosts.indices.forEach { idx ->
                    val program = nodePrograms[idx]
                    val nodeConnections = hosts[idx].network.connections
                    val connections = nodeConnections.size
                    val connectionDetails = nodeConnections.joinToString(
                        prefix = "[",
                        postfix = "]"
                    ) { conn ->
                        "${conn.remoteAddress()}"
                    }
                    val status = if (program is SampleGossipNodeProgram) {
                        program.debugState()
                    } else {
                        "complete=${program.isComplete()}"
                    }
                    log("node=${program.simNodeId} connections=$connections remotes=$connectionDetails $status")
                }
                prevCompleteCount = completeCount
            }
        }
        log("All complete...")
    }
}