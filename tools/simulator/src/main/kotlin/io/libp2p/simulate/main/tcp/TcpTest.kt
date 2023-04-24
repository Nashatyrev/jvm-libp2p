package io.libp2p.simulate.main.tcp

import io.libp2p.etc.types.toByteBuf


fun main() {
    TcpTest().runOneOnOne()
}

class TcpTest(
    val destHost: String = "localhost", // "45.79.117.81",
    val destPort: Int = 7777,
    val srcPort: Int = 8888,
    val msgSize: Int = 512 * 1024,
) {

    fun runOneOnOne() {
        val serverNode = DefaultTcpServerNode(destPort, destHost)
        val clientNode = DefaultTcpClientNode(0, srcPort)

        val clientChannel = clientNode.connect(serverNode)

        while (true) {
            resetTcpNodeLoggers()

            val data = ByteArray(msgSize)
            clientChannel.writeAndFlush(data.toByteBuf()).sync()

            Thread.sleep(10000)
        }
    }
}

