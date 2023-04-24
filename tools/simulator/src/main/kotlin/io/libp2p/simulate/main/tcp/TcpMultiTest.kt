package io.libp2p.simulate.main.tcp

import io.libp2p.etc.types.toByteBuf


fun main() {
    TcpMultiTest().runInbound()
}

class TcpMultiTest(
    val destHost: String = "localhost", // "45.79.117.81",
    val destPort: Int = 7777,
//    val srcPort: Int = 8888,
    val msgSize: Int = 512 * 1024,
    val clientCount: Int = 4
) {

    fun runInbound() {
        val serverNode = DefaultTcpServerNode(destPort, destHost)
        val clientNodes =
            (0 until clientCount)
                .map {
                    DefaultTcpClientNode(it)
                }
                .map {
                    it.connect(serverNode)
                }

        Thread.sleep(1000)
        while (true) {
            resetTcpNodeLoggers()

            clientNodes
                .map {
                    val data = ByteArray(msgSize)
                    it.writeAndFlush(data.toByteBuf())
                }
                .forEach { it.sync() }

            Thread.sleep(10000)
        }
    }
}

