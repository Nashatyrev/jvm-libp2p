package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.schedule.Controllable
import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.core.schedule.impl.NanoMonotonicTimer
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.buffer.WrappedByteBuf
import io.netty.channel.socket.DatagramPacket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

abstract class AbstractSimPacketBridge(
    idAndIp: Collection<IdMapEntry>,
) : Controllable {

    data class IdMapEntry(
        val nodeId: String,
        val ip: String
    )

    companion object {
        private val bridgeDirectCopyStats = DirectCopyStats()

        fun resetBridgeDirectCopyStats() {
            bridgeDirectCopyStats.reset()
        }

        fun bridgeDirectCopyStatsSnapshot(): DirectCopyStats.Snapshot =
            bridgeDirectCopyStats.snapshot()
    }

    protected var nanosPassed = AtomicLong(0)
    val monotonicTimer: MonotonicTimer = NanoMonotonicTimer(nanosPassed::get)

    protected val idToIp = idAndIp.associate { it.nodeId to it.ip }
    protected val ipToId = idAndIp.associate { it.ip to it.nodeId }

    private val packetIdCounter = AtomicLong()
    private val heapPacketPayloads = System.getProperty("quicsim.bridge.heapPayloads").toBoolean()

    protected val nettyDatagramToSimUdpPacketConverter: (DatagramPacket) -> UdpSimPacket =
        { datagramPacket ->
            val content = datagramPacket.content()
            val readableBytes = content.readableBytes()
            val sender = datagramPacket.sender()
            val recipient = datagramPacket.recipient()
            val payloadRef =
                if (heapPacketPayloads) {
                    val payload = content.copyToHeapBuffer()
                    datagramPacket.release()
                    payload
                } else {
                    content
                }
            UdpSimPacket(
                id = packetIdCounter.incrementAndGet(),
                bytes = readableBytes,
                srcNodeId = ipToId[sender.hostString]!!,
                dstNodeId = ipToId[recipient.hostString]!!,
                srcPort = sender.port,
                dstPort = recipient.port,
                payloadRef = payloadRef
            )
        }
    protected val simUdpPacketToNettyDatagramConverter: (UdpSimPacket) -> DatagramPacket =
        { simPacket ->
            val payload = simPacket.payloadRef as ByteBuf
            val content =
                if (heapPacketPayloads) {
                    val directPayload = payload.copyToDirectBuffer()
                    payload.release()
                    directPayload
                } else {
                    payload
                }
            DatagramPacket(
                content,
                InetSocketAddress(idToIp[simPacket.dstNodeId]!!, simPacket.dstPort),
                InetSocketAddress(idToIp[simPacket.srcNodeId]!!, simPacket.srcPort),
            )
        }

    private fun ByteBuf.copyToHeapBuffer(): ByteBuf {
        val copy = Unpooled.buffer(readableBytes(), readableBytes())
        copy.writeBytes(this, readerIndex(), readableBytes())
        return copy
    }

    private fun ByteBuf.copyToDirectBuffer(): ByteBuf {
        val bytes = readableBytes()
        val copy = Unpooled.directBuffer(bytes, bytes)
        var success = false
        try {
            copy.writeBytes(this, readerIndex(), bytes)
            success = true
            bridgeDirectCopyStats.allocate(bytes.toLong())
            return BridgeDirectCopyByteBuf(copy, bytes.toLong())
        } finally {
            if (!success) {
                copy.release()
            }
        }
    }

    private class BridgeDirectCopyByteBuf(
        buffer: ByteBuf,
        private val bytes: Long
    ) : WrappedByteBuf(buffer) {
        private val released = AtomicBoolean()

        override fun release(): Boolean {
            val deallocated = super.release()
            if (deallocated && released.compareAndSet(false, true)) {
                bridgeDirectCopyStats.release(bytes)
            }
            return deallocated
        }

        override fun release(decrement: Int): Boolean {
            val deallocated = super.release(decrement)
            if (deallocated && released.compareAndSet(false, true)) {
                bridgeDirectCopyStats.release(bytes)
            }
            return deallocated
        }
    }

    class DirectCopyStats {
        private val allocatedBuffers = AtomicLong()
        private val releasedBuffers = AtomicLong()
        private val activeBuffers = AtomicLong()
        private val maxActiveBuffers = AtomicLong()
        private val allocatedBytes = AtomicLong()
        private val releasedBytes = AtomicLong()
        private val activeBytes = AtomicLong()
        private val maxActiveBytes = AtomicLong()

        fun allocate(bytes: Long) {
            allocatedBuffers.incrementAndGet()
            allocatedBytes.addAndGet(bytes)
            updateMax(maxActiveBuffers, activeBuffers.incrementAndGet())
            updateMax(maxActiveBytes, activeBytes.addAndGet(bytes))
        }

        fun release(bytes: Long) {
            releasedBuffers.incrementAndGet()
            releasedBytes.addAndGet(bytes)
            activeBuffers.decrementAndGet()
            activeBytes.addAndGet(-bytes)
        }

        fun reset() {
            allocatedBuffers.set(0)
            releasedBuffers.set(0)
            activeBuffers.set(0)
            maxActiveBuffers.set(0)
            allocatedBytes.set(0)
            releasedBytes.set(0)
            activeBytes.set(0)
            maxActiveBytes.set(0)
        }

        fun snapshot(): Snapshot =
            Snapshot(
                allocatedBuffers = allocatedBuffers.get(),
                releasedBuffers = releasedBuffers.get(),
                activeBuffers = activeBuffers.get(),
                maxActiveBuffers = maxActiveBuffers.get(),
                allocatedBytes = allocatedBytes.get(),
                releasedBytes = releasedBytes.get(),
                activeBytes = activeBytes.get(),
                maxActiveBytes = maxActiveBytes.get()
            )

        private fun updateMax(maxValue: AtomicLong, candidate: Long) {
            while (true) {
                val current = maxValue.get()
                if (candidate <= current || maxValue.compareAndSet(current, candidate)) {
                    return
                }
            }
        }

        data class Snapshot(
            val allocatedBuffers: Long,
            val releasedBuffers: Long,
            val activeBuffers: Long,
            val maxActiveBuffers: Long,
            val allocatedBytes: Long,
            val releasedBytes: Long,
            val activeBytes: Long,
            val maxActiveBytes: Long
        )
    }

    override fun advance(advanceDuration: Duration) {
        nanosPassed.updateAndGet { it + advanceDuration.inWholeNanoseconds }
        advanceImpl(advanceDuration)
    }

    abstract fun advanceImpl(advanceDuration: Duration)
    fun close() {}
}
