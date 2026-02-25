package io.libp2p.quicsim

import io.libp2p.core.Connection
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class QuicSimulationHarnessTest {

    @Test
    fun `two simulated nodes exchange ping and disconnect on idle timeout`() {
        println("=== TEST START: two simulated nodes exchange ping and disconnect on idle timeout ===")
        val harness = QuicSimulationHarness()
        val pingBinding = Ping()
        val serverListenAddr = Multiaddr("/ip4/127.0.0.1/udp/41101/quic-v1")
        println("Created harness and ping binding. serverListenAddr=$serverListenAddr")

        val serverConnFuture = CompletableFuture<Connection>()
        val server = harness.createNode(
            listenAddress = serverListenAddr,
            protocols = listOf(pingBinding),
            onIncomingConnection = serverConnFuture::complete
        )
        val client = harness.createNode(protocols = listOf(pingBinding))
        println("Created server/client nodes. serverPeer=${server.host.peerId} clientPeer=${client.host.peerId}")

        try {
            println("Starting server node")
            server.start()
            println("Server started")
            println("Starting client node")
            client.start()
            println("Client started")

            println("Connecting client to server")
            val clientConn = client.connect(server, serverListenAddr)
            harness.runUntil({ serverConnFuture.isDone })
            val serverConn = serverConnFuture.get(1, TimeUnit.SECONDS)
            println("Connected")
            println("clientConn local=${clientConn.localAddress()} remote=${clientConn.remoteAddress()}")
            println("serverConn local=${serverConn.localAddress()} remote=${serverConn.remoteAddress()}")

            println("Creating ping stream")
            val streamPromise = clientConn.muxerSession().createStream(pingBinding)
            harness.runUntil({ streamPromise.stream.isDone && streamPromise.controller.isDone })
            val stream = streamPromise.stream.get(1, TimeUnit.SECONDS)
            println("Stream created. initiator=${stream.isInitiator}")

            val pingController = streamPromise.controller.get(1, TimeUnit.SECONDS) as PingController
            println("Sending ping")
            val pingFuture = pingController.ping()
            harness.runUntil({ pingFuture.isDone })
            val rttMs = pingFuture.get(1, TimeUnit.SECONDS)
            println("Ping completed. rttMs=$rttMs")
            assertTrue(rttMs >= 0, "Expected ping to complete successfully")

            println("Advancing simulated time to trigger idle timeout")
            var elapsedSeconds = 0
            while (elapsedSeconds < 180 && (!clientConn.closeFuture().isDone || !serverConn.closeFuture().isDone)) {
                harness.advanceTimeBy(Duration.ofSeconds(10), postAdvanceSteps = 300)
                elapsedSeconds += 10
                println(
                    "t=${elapsedSeconds}s clientClosed=${clientConn.closeFuture().isDone} " +
                        "serverClosed=${serverConn.closeFuture().isDone}"
                )
            }

            println(
                "Final close state at t=${elapsedSeconds}s: " +
                    "clientClosed=${clientConn.closeFuture().isDone}, serverClosed=${serverConn.closeFuture().isDone}"
            )
            assertTrue(clientConn.closeFuture().isDone, "Client connection should close after idle timeout")
            assertTrue(serverConn.closeFuture().isDone, "Server connection should close after idle timeout")
        } finally {
            println("Stopping client node")
            client.stop()
            println("Client stopped")
            println("Stopping server node")
            server.stop()
            println("Server stopped")
            println("=== TEST END ===")
        }
    }
}
