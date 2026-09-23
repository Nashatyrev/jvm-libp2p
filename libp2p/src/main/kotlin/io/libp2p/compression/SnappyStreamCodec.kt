package io.libp2p.compression

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageCodec
import io.netty.handler.codec.CorruptedFrameException

/**
 * Compresses a whole stream with [SnappyHistoryEncoder] / [SnappyHistoryDecoder].
 *
 * Every outbound write becomes one or more blocks of at most [maxBlockSize] decoded bytes, each
 * sent as `varint(encodedLength) || block`. Nothing is buffered across writes, so a flush sends
 * everything written so far, and the history makes later blocks refer back to earlier ones.
 * Inbound blocks are decoded in order and passed on as plain bytes. Any malformed block closes
 * the stream: the decoder state is then undefined and can't be recovered.
 */
class SnappyStreamCodec(
    history: Int = SnappyStreamCompression.HISTORY_SIZE,
    private val maxBlockSize: Int = SnappyStreamCompression.MAX_BLOCK_SIZE,
    encoderChainDepth: Int = 1
) : ByteToMessageCodec<ByteBuf>() {

    private val encoder = SnappyHistoryEncoder(history, maxBlockSize, encoderChainDepth)
    private val decoder = SnappyHistoryDecoder(history, maxBlockSize)
    private val maxEncodedBlock = SnappyHistoryEncoder.maxCompressedLength(maxBlockSize)
    private val chunk = ByteArray(maxBlockSize)
    private var failed = false

    /** Plain bytes written by the protocol, and what they cost on the wire. */
    var plainBytesOut = 0L
        private set
    var wireBytesOut = 0L
        private set

    /** Wire bytes received, and the plain bytes they decoded to. */
    var wireBytesIn = 0L
        private set
    var plainBytesIn = 0L
        private set

    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: ByteBuf) {
        while (msg.isReadable) {
            val n = minOf(msg.readableBytes(), maxBlockSize)
            msg.readBytes(chunk, 0, n)
            val encodedLen = encoder.encode(chunk, 0, n)
            val before = out.writerIndex()
            writeVarint(out, encodedLen)
            out.writeBytes(encoder.output, 0, encodedLen)
            plainBytesOut += n
            wireBytesOut += out.writerIndex() - before
        }
    }

    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        if (failed) {
            msg.skipBytes(msg.readableBytes())
            return
        }
        while (true) {
            val start = msg.readerIndex()
            val encodedLen = readVarint(ctx, msg) ?: return
            if (encodedLen > maxEncodedBlock) fail(ctx, "block of $encodedLen bytes exceeds $maxEncodedBlock")
            if (msg.readableBytes() < encodedLen) {
                msg.readerIndex(start)
                return
            }
            val block = ByteArray(encodedLen)
            msg.readBytes(block)
            val decoded = try {
                decoder.decode(block, 0, encodedLen)
            } catch (e: SnappyHistoryFormatException) {
                fail(ctx, e.message ?: "malformed block")
            }
            wireBytesIn += msg.readerIndex() - start
            plainBytesIn += decoded.size
            if (decoded.isNotEmpty()) out += Unpooled.wrappedBuffer(decoded)
        }
    }

    private fun fail(ctx: ChannelHandlerContext, reason: String): Nothing {
        failed = true
        ctx.close()
        throw CorruptedFrameException("Snappy stream: $reason")
    }

    private fun writeVarint(out: ByteBuf, value: Int) {
        var v = value
        while (v >= 0x80) {
            out.writeByte((v and 0x7f) or 0x80)
            v = v ushr 7
        }
        out.writeByte(v)
    }

    /** Reads a varint if it is complete; otherwise leaves the reader index as it was and returns null. */
    private fun readVarint(ctx: ChannelHandlerContext, buf: ByteBuf): Int? {
        val start = buf.readerIndex()
        var result = 0
        var shift = 0
        while (buf.isReadable) {
            val b = buf.readByte().toInt() and 0xff
            result = result or ((b and 0x7f) shl shift)
            if (b < 0x80) return result
            shift += 7
            if (shift > 28) fail(ctx, "malformed block length")
        }
        buf.readerIndex(start)
        return null
    }
}
