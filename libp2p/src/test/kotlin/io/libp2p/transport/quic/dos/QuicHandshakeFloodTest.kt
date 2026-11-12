package io.libp2p.transport.quic.dos

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.transport.quic.DatagramChannelFactory
import io.libp2p.transport.quic.DefaultDatagramChannelFactory
import io.libp2p.transport.quic.QuicTransport
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.lang.management.ManagementFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Handshake flood against our QUIC listener: forged client Initials, each with a fresh destination
 * connection id, every one of which makes the server run a full TLS server handshake.
 *
 * The experiment is opt-in:
 * ```
 * ./gradlew :libp2p:test --tests 'io.libp2p.transport.quic.dos.QuicHandshakeFloodTest' -DquicDos.enabled=true \
 *     -DquicDos.rates=500,2000,8000,0 -DquicDos.phaseSeconds=15 -DquicDos.senderThreads=2
 * ```
 */
class QuicHandshakeFloodTest {

    @Test
    fun `a forged Initial with a replayed ClientHello makes the server start a handshake`() {
        val forger = QuicInitialForger.fromCapturedInitials(captureClientInitials())
        val victim = Victim().start()
        try {
            QuicHandshakeFlooder(victim.address, { forger }, ratePps = 200).start().use { flooder ->
                waitUntil(5_000) { flooder.handshakesStarted.get() >= 50 }
                assertThat(flooder.handshakesStarted.get())
                    .describedAs("every forged attempt with a fresh DCID gets its own server handshake")
                    .isGreaterThanOrEqualTo(50)
            }
        } finally {
            victim.close()
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "quicDos.enabled", matches = "true")
    fun `handshake flood experiment`() {
        val rates = System.getProperty("quicDos.rates", "500,2000,8000,0").split(",").map { it.trim().toInt() }
        val phaseSeconds = System.getProperty("quicDos.phaseSeconds", "15").toInt()
        val senderThreads = System.getProperty("quicDos.senderThreads", "2").toInt()

        val captured = captureClientInitials()
        val forger = QuicInitialForger.fromCapturedInitials(captured)
        println("Captured ${captured.size} client Initial datagram(s); max heap ${Runtime.getRuntime().maxMemory() shr 20} MiB")
        measureForgeCost(forger)

        val victim = Victim().start()
        val legit = LegitPeer(victim).start()
        val phases = mutableListOf<PhaseSummary>()
        try {
            phases += runPhase("baseline", null, phaseSeconds, senderThreads, captured, victim, legit)
            for (rate in rates) {
                phases += runPhase(if (rate == 0) "flood max" else "flood $rate/s", rate, phaseSeconds, senderThreads, captured, victim, legit)
            }
            phases += runPhase("recovery", null, phaseSeconds, senderThreads, captured, victim, legit)
        } finally {
            legit.close()
            victim.close()
        }

        println()
        println("=== Summary (victim = one QuicTransport listener; CPU is its event-loop thread, 100% = one core) ===")
        println(PhaseSummary.HEADER)
        phases.forEach { println(it.row()) }
    }

    /**
     * What one forged attempt gets back over the half-open connection's whole life:
     * RFC 9000 §8.1 caps an unvalidated server at 3x the bytes it received.
     */
    @Test
    @EnabledIfSystemProperty(named = "quicDos.enabled", matches = "true")
    fun `reply profile of a single forged attempt`() {
        val forger = QuicInitialForger.fromCapturedInitials(captureClientInitials())
        val victim = Victim().start()
        try {
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { sock ->
                sock.soTimeout = 1_000
                val sent = forger.forge(Random.nextBytes(16))
                val t0 = System.nanoTime()
                sent.forEach { sock.send(DatagramPacket(it, it.size, victim.address)) }
                var total = 0
                val deadline = t0 + 70_000_000_000L
                while (System.nanoTime() < deadline) {
                    val p = DatagramPacket(ByteArray(2048), 2048)
                    try {
                        sock.receive(p)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    total += p.length
                    val types = generateSequence(0) { pos ->
                        val next = QuicInitialForger.parseLongHeader(p.data.copyOf(p.length), pos).end
                        next.takeIf { it < p.length && p.data[it].toInt() and 0x80 != 0 }
                    }.map { listOf("Initial", "0-RTT", "Handshake", "Retry")[(p.data[it].toInt() shr 4) and 3] }.toList()
                    println("%7.3f s  %5d bytes  %s".format((System.nanoTime() - t0) / 1e9, p.length, types))
                }
                val sentBytes = sent.sumOf { it.size }
                println("sent $sentBytes bytes, received $total bytes back: %.2fx; live conns now ${victim.liveQuicChannels()}".format(total.toDouble() / sentBytes))
            }
        } finally {
            victim.close()
        }
    }

    private fun measureForgeCost(forger: QuicInitialForger) {
        val dcid = ByteArray(16)
        val bean = ManagementFactory.getThreadMXBean()
        repeat(20_000) {
            Random.nextBytes(dcid)
            forger.forge(dcid)
        } // warm-up
        val n = 100_000
        val cpu0 = bean.currentThreadCpuTime
        repeat(n) { i ->
            dcid[0] = i.toByte()
            dcid[1] = (i shr 8).toByte()
            dcid[2] = (i shr 16).toByte()
            forger.forge(dcid)
        }
        val perAttemptMicros = (bean.currentThreadCpuTime - cpu0) / 1e3 / n
        println("Attacker forging cost: %.2f us CPU per attempt (~%,d attempts/s per core)".format(perAttemptMicros, (1e6 / perAttemptMicros).toLong()))
    }

    private fun runPhase(
        name: String,
        rate: Int?,
        seconds: Int,
        senderThreads: Int,
        captured: List<ByteArray>,
        victim: Victim,
        legit: LegitPeer
    ): PhaseSummary {
        println()
        println("--- phase: $name ---")
        println("  t | attempts/s | srv hs/s | srv replies/s | victim cpu | gc ms | heap MiB | live quic conns | ping p50/p99/max ms | ping timeouts | rss MiB")
        // the forger is not thread safe: each sender thread builds its own
        val flooder = rate?.let {
            QuicHandshakeFlooder(victim.address, { QuicInitialForger.fromCapturedInitials(captured) }, it, senderThreads).start()
        }
        val summary = PhaseSummary(name)
        var dialProbe: CompletableFuture<Long>? = null
        var prevCpu = victim.eventLoopCpuNanos()
        var prevGc = gcMillis()
        var prevAttempts = 0L
        var prevHs = 0L
        var prevReplies = 0L
        val phaseStart = System.nanoTime()
        legit.drain()
        for (s in 1..seconds) {
            sleepUntil(phaseStart + s * 1_000_000_000L)
            if (s == seconds / 2) dialProbe = legit.probeNewDial()
            val cpu = victim.eventLoopCpuNanos()
            val attempts = flooder?.attemptsSent?.get() ?: 0
            val hs = flooder?.handshakesStarted?.get() ?: 0
            val replies = flooder?.repliesReceived?.get() ?: 0
            val pings = legit.drain()
            val live = victim.liveQuicChannels()
            val cpuPct = (cpu - prevCpu) / 1e7
            val gc = gcMillis()
            val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used shr 20
            val row = Second(attempts - prevAttempts, hs - prevHs, replies - prevReplies, cpuPct, gc - prevGc, live, pings, rssMiB())
            summary.seconds += row
            println(
                "%3d | %10d | %8d | %13d | %9.0f%% | %5d | %8d | %15d | %19s | %13d | %7d".format(
                    s, row.attempts, row.handshakes, row.replies, row.cpuPct, row.gcMillis, heap, row.live, row.pings.pct(), row.pings.timeouts, row.rssMiB
                )
            )
            prevCpu = cpu
            prevGc = gc
            prevAttempts = attempts
            prevHs = hs
            prevReplies = replies
        }
        flooder?.close()
        if (flooder != null) {
            summary.amplification = flooder.replyBytes.get().toDouble() / flooder.bytesSent.get().coerceAtLeast(1)
            summary.attackMbps = flooder.bytesSent.get() * 8 / 1e6 / seconds
        }
        summary.dialMillis = try {
            dialProbe?.get(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            -1
        }
        return summary
    }

    /** Records the first client Initials of a real libp2p QUIC dial against a socket that never answers. */
    private fun captureClientInitials(): List<ByteArray> {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { sock ->
            sock.soTimeout = 300
            val client = QuicTransport(generateKeyPair(KeyType.ED25519).first, "ECDSA", listOf(Ping()))
            val nobody = PeerId.fromPubKey(generateKeyPair(KeyType.ED25519).second)
            client.dial(Multiaddr("/ip4/127.0.0.1/udp/${sock.localPort}/quic-v1/p2p/$nobody"), ConnectionHandler { })
            val datagrams = mutableListOf<ByteArray>()
            val deadline = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < deadline) {
                val p = DatagramPacket(ByteArray(2048), 2048)
                try {
                    sock.receive(p)
                    datagrams += p.data.copyOf(p.length)
                } catch (e: SocketTimeoutException) {
                    if (datagrams.isNotEmpty()) break
                }
            }
            client.close().get(5, TimeUnit.SECONDS)
            check(datagrams.isNotEmpty()) { "The QUIC client sent nothing" }
            return datagrams
        }
    }

    class Victim {
        private val port = 20_000 + Random.nextInt(20_000)
        val address = InetSocketAddress(InetAddress.getLoopbackAddress(), port)
        val peerId: PeerId
        private val serverChannel = CompletableFuture<Channel>()
        private val transport: QuicTransport
        private var eventLoopThreadId = -1L
        val inbound = ConcurrentLinkedQueue<Connection>()

        init {
            val key = generateKeyPair(KeyType.ED25519).first
            peerId = PeerId.fromPubKey(key.publicKey())
            val delegate = DefaultDatagramChannelFactory()
            val capturing = object : DatagramChannelFactory by delegate {
                override fun createServerChannel(bindAddress: SocketAddress, handler: ChannelHandler) =
                    delegate.createServerChannel(bindAddress, handler).thenApply {
                        serverChannel.complete(it)
                        it
                    }
            }
            transport = QuicTransport(key, "ECDSA", listOf(Ping()), datagramChannelFactory = capturing)
        }

        val listenAddr get() = Multiaddr("/ip4/127.0.0.1/udp/$port/quic-v1/p2p/$peerId")

        fun start(): Victim {
            transport.listen(Multiaddr("/ip4/127.0.0.1/udp/$port/quic-v1"), ConnectionHandler { inbound += it })
                .get(5, TimeUnit.SECONDS)
            val ch = serverChannel.get(5, TimeUnit.SECONDS)
            eventLoopThreadId = ch.eventLoop().submit(Callable { Thread.currentThread().id }).get()
            return this
        }

        fun eventLoopCpuNanos(): Long = ManagementFactory.getThreadMXBean().getThreadCpuTime(eventLoopThreadId)

        /** Connections (handshaking or established) the Netty QUIC server codec currently holds. */
        fun liveQuicChannels(): Int {
            val ch = serverChannel.get()
            return try {
                ch.eventLoop().submit(
                    Callable {
                        ch.pipeline().toMap().values.firstNotNullOfOrNull { h ->
                            generateSequence<Class<*>>(h.javaClass) { it.superclass }
                                .firstOrNull { it.name == "io.netty.incubator.codec.quic.QuicheQuicCodec" }
                                ?.getDeclaredField("channels")
                                ?.apply { isAccessible = true }
                                ?.let { (it.get(h) as Set<*>).size }
                        } ?: -1
                    }
                ).get(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                -2 // event loop too busy to answer within 5 s
            }
        }

        fun close() {
            transport.close().get(10, TimeUnit.SECONDS)
        }
    }

    /** An honest peer: one established connection pinging every 20 ms, plus fresh-dial probes. */
    class LegitPeer(private val victim: Victim) {
        private val transport = QuicTransport(generateKeyPair(KeyType.ED25519).first, "ECDSA", listOf(Ping()))
        private val samples = ConcurrentLinkedQueue<Long>()

            // ping RTT in micros, -1 = timeout
        @Volatile
        private var running = true
        private lateinit var pinger: Thread

        fun start(): LegitPeer {
            val conn = transport.dial(victim.listenAddr, ConnectionHandler { }).get(10, TimeUnit.SECONDS)
            val ctrl = conn.muxerSession().createStream(Ping()).controller.get(10, TimeUnit.SECONDS) as PingController
            pinger = Thread({
                while (running) {
                    val t0 = System.nanoTime()
                    try {
                        ctrl.ping().get(2, TimeUnit.SECONDS)
                        samples += (System.nanoTime() - t0) / 1000
                    } catch (e: Exception) {
                        samples += -1
                    }
                    Thread.sleep(20)
                }
            }, "legit-pinger").apply {
                isDaemon = true
                start()
            }
            return this
        }

        fun drain(): Pings {
            val list = mutableListOf<Long>()
            while (true) list += samples.poll() ?: break
            return Pings(list.filter { it >= 0 }.sorted(), list.count { it < 0 })
        }

        /** Time to establish a brand-new connection to the victim, -1 if it fails within 10 s. */
        fun probeNewDial(): CompletableFuture<Long> = CompletableFuture.supplyAsync {
            val t0 = System.nanoTime()
            try {
                val c = transport.dial(victim.listenAddr, ConnectionHandler { }).get(10, TimeUnit.SECONDS)
                val ms = (System.nanoTime() - t0) / 1_000_000
                c.close()
                ms
            } catch (e: Exception) {
                -1L
            }
        }

        fun close() {
            running = false
            pinger.join(3_000)
            transport.close().get(10, TimeUnit.SECONDS)
        }
    }

    class Pings(val sortedMicros: List<Long>, val timeouts: Int) {
        private fun q(p: Double) = if (sortedMicros.isEmpty()) "-" else "%.1f".format(sortedMicros[((sortedMicros.size - 1) * p).toInt()] / 1000.0)
        fun pct() = "${q(0.5)}/${q(0.99)}/${q(1.0)}"
    }

    class Second(
        val attempts: Long,
        val handshakes: Long,
        val replies: Long,
        val cpuPct: Double,
        val gcMillis: Long,
        val live: Int,
        val pings: Pings,
        val rssMiB: Long
    )

    class PhaseSummary(val name: String) {
        val seconds = mutableListOf<Second>()
        var amplification: Double? = null
        var attackMbps: Double? = null
        var dialMillis: Long? = null

        fun row(): String {
            val steady = seconds.drop(1) // first second includes ramp-up
            val allPings = steady.flatMap { it.pings.sortedMicros }.sorted()
            val timeouts = steady.sumOf { it.pings.timeouts }
            return "%-14s | %10.0f | %8s | %8.0f | %9.0f%% | %7.0f | %15s | %8d | %21s | %8d | %9s | %7d".format(
                name,
                steady.map { it.attempts }.average(),
                attackMbps?.let { "%.1f".format(it) } ?: "-",
                steady.map { it.handshakes }.average(),
                steady.map { it.cpuPct }.average(),
                steady.map { it.gcMillis }.average(),
                amplification?.let { "%.2fx".format(it) } ?: "-",
                seconds.last().live,
                Pings(allPings, timeouts).pct(),
                timeouts,
                dialMillis?.let { if (it < 0) "FAILED" else "$it ms" } ?: "-",
                seconds.last().rssMiB
            )
        }

        companion object {
            const val HEADER = "phase          | attempts/s | atk Mbit | srv hs/s | victim cpu | gc ms/s | reply/atk bytes | live end | ping p50/p99/max ms   | timeouts | new dial  | rss MiB"
        }
    }

    companion object {
        private fun sleepUntil(nanoTime: Long) {
            val d = nanoTime - System.nanoTime()
            if (d > 0) TimeUnit.NANOSECONDS.sleep(d)
        }

        private fun waitUntil(timeoutMillis: Long, cond: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        }

        private fun gcMillis(): Long = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) }

        private fun rssMiB(): Long = try {
            ProcessBuilder("ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString())
                .start().inputStream.bufferedReader().readText().trim().toLong() / 1024
        } catch (e: Exception) {
            -1
        }
    }
}
