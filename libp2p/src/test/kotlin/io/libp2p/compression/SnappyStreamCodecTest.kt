package io.libp2p.compression

import io.libp2p.etc.types.toByteArray
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.CorruptedFrameException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SnappyStreamCodecTest {

    private fun ByteArray.buf(): ByteBuf = Unpooled.wrappedBuffer(this)

    /** Repeating, gossip-like messages: a shared header plus a bit of noise each. */
    private fun messages(rnd: Random, count: Int): List<ByteArray> {
        val header = rnd.nextBytes(120)
        return List(count) { header + rnd.nextBytes(rnd.nextInt(0, 100)) + header.copyOf(rnd.nextInt(0, 120)) }
    }

    private fun EmbeddedChannel.drainOutbound(): ByteArray {
        val all = Unpooled.buffer()
        while (true) {
            val b = readOutbound<ByteBuf>() ?: break
            all.writeBytes(b)
            b.release()
        }
        return all.toByteArray()
    }

    private fun EmbeddedChannel.drainInbound(): ByteArray {
        val all = Unpooled.buffer()
        while (true) {
            val b = readInbound<ByteBuf>() ?: break
            all.writeBytes(b)
            b.release()
        }
        return all.toByteArray()
    }

    @Test
    fun `stream round trip with arbitrary splits of the wire bytes`() {
        val rnd = Random(1)
        val senderCodec = SnappyStreamCodec()
        val sender = EmbeddedChannel(senderCodec)
        val receiver = EmbeddedChannel(SnappyStreamCodec())

        val msgs = messages(rnd, 2000) + listOf(ByteArray(200_000) { (it % 97).toByte() })
        val wire = msgs.flatMap { m ->
            sender.writeAndFlush(m.buf())
            sender.drainOutbound().toList()
        }.toByteArray()

        // deliver in random pieces: blocks and their length prefixes get split across reads
        var pos = 0
        while (pos < wire.size) {
            val n = minOf(rnd.nextInt(1, 3000), wire.size - pos)
            receiver.writeInbound(wire.copyOfRange(pos, pos + n).buf())
            pos += n
        }

        val expected = msgs.fold(ByteArray(0)) { a, b -> a + b }
        assertThat(receiver.drainInbound()).isEqualTo(expected)
        assertThat(senderCodec.plainBytesOut).isEqualTo(expected.size.toLong())
        // the shared header is only paid for once per message's worth of history
        assertThat(senderCodec.wireBytesOut).isLessThan(expected.size / 2L)
    }

    @Test
    fun `a write larger than a block is split and reassembled`() {
        val sender = EmbeddedChannel(SnappyStreamCodec())
        val receiver = EmbeddedChannel(SnappyStreamCodec())
        val big = Random(2).nextBytes(3 * SnappyStreamCompression.MAX_BLOCK_SIZE + 17)

        sender.writeAndFlush(big.buf())
        receiver.writeInbound(sender.drainOutbound().buf())

        assertThat(receiver.drainInbound()).isEqualTo(big)
    }

    @Test
    fun `garbage closes the stream`() {
        val receiver = EmbeddedChannel(SnappyStreamCodec())
        assertThatThrownBy { receiver.writeInbound(byteArrayOf(5, 0x7f, 1, 2, 3, 4).buf()) }
            .isInstanceOf(CorruptedFrameException::class.java)
        assertThat(receiver.isOpen).isFalse()
    }

    @Test
    fun `oversized block length closes the stream before buffering it`() {
        val receiver = EmbeddedChannel(SnappyStreamCodec())
        // varint 0x0fffffff: far above the largest possible encoded block
        assertThatThrownBy { receiver.writeInbound(byteArrayOf(-1, -1, -1, 0x7f).buf()) }
            .isInstanceOf(CorruptedFrameException::class.java)
        assertThat(receiver.isOpen).isFalse()
    }
}
