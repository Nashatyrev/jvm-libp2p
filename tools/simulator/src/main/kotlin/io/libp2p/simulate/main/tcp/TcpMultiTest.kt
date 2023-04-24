package io.libp2p.simulate.main.tcp

import io.libp2p.etc.types.toByteBuf


fun main() {
    val test = TcpMultiTest()
    test.run()
}

class TcpMultiTest(
    val destHost: String = "localhost", // "45.79.117.81",
    val destPort: Int = 7777,
//    val srcPort: Int = 8888,
    val msgSize: Int = 512 * 1024,
    val clientCount: Int = 8
) {

    lateinit var server: ServerTcpNode
    val clients = mutableListOf<ClientTcpNode>()

    fun run() {
        startServer()
        createClients()
        connect()
        runInbound()
    }

    fun startServer() {
        server = DefaultTcpServerNode(destPort, destHost, logEachConnection = false)
    }

    fun createClients() {
        clients +=
            (0 until clientCount)
                .map {
                    DefaultTcpClientNode(it)
                }
    }

    fun connect() {
        clients.forEach {
            it.connect(server)
        }
        Thread.sleep(1000)
    }

    fun runInbound() {
        while (true) {
            resetTcpNodeLoggers()

            clients
                .map {
                    val data = ByteArray(msgSize)
                    it.connections[0].writeAndFlush(data.toByteBuf())
                }
                .forEach { it.sync() }

            Thread.sleep(10000)
        }
    }

    fun runOutbound() {
        while (true) {
            resetTcpNodeLoggers()

            server.connections
                .map {
                    val data = ByteArray(msgSize)
                    it.writeAndFlush(data.toByteBuf())
                }
                .forEach { it.sync() }

            Thread.sleep(10000)
        }
    }
}

