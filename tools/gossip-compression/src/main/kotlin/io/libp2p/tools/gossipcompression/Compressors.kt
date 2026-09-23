package io.libp2p.tools.gossipcompression

import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream
import com.aayushatharva.brotli4j.encoder.Encoder
import com.github.luben.zstd.EndDirective
import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdDictCompress
import org.xerial.snappy.Snappy
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater

/**
 * Stateful compressor of one byte stream. Every [write] is flushed immediately (gossip can't wait
 * for more data), and returns how many compressed bytes that flush put on the wire.
 */
interface StreamCompressor {
    fun write(data: ByteArray): Int
    fun close()
}

/** Stateless compressor of a single message. Returns the compressed size. */
fun interface BlockCompressor {
    fun compress(data: ByteArray): Int
}

/** A named recipe for creating compressors, so that a scenario can create one per context. */
class StreamAlgo(val name: String, val windowBytes: Long, val create: () -> StreamCompressor)
class BlockAlgo(val name: String, val create: () -> BlockCompressor)

fun zstdStream(level: Int, windowLog: Int? = null) = StreamAlgo(
    "zstd-$level" + (windowLog?.let { "-w$it" } ?: ""),
    1L shl (windowLog ?: defaultZstdWindowLog(level))
) { ZstdStream(level, windowLog) }

// zstd's own defaults for inputs of unknown size (lib/compress/clevels.h)
private fun defaultZstdWindowLog(level: Int) = when {
    level <= 1 -> 19
    level <= 2 -> 20
    level <= 5 -> 21
    level <= 12 -> 22
    else -> 23
}

fun deflateStream(level: Int) = StreamAlgo("deflate-$level", 1L shl 15) { DeflateStream(level) }

fun brotliStream(quality: Int, lgwin: Int) = StreamAlgo("brotli-$quality-w$lgwin", 1L shl lgwin) { BrotliStream(quality, lgwin) }

fun zstdBlock(level: Int, dict: ByteArray? = null) = BlockAlgo(
    "zstd-$level" + (dict?.let { "-dict${it.size / 1024}k" } ?: "")
) {
    val ctx = ZstdCompressCtx().apply {
        setLevel(level)
        setMagicless(true)
        setChecksum(false)
        setContentSize(false)
        setDictID(false)
        if (dict != null) loadDict(ZstdDictCompress(dict, level))
    }
    BlockCompressor { data ->
        val dst = ByteArray(64 + data.size + data.size / 100)
        ctx.compressByteArray(dst, 0, dst.size, data, 0, data.size)
    }
}

fun snappyBlock() = BlockAlgo("snappy") { BlockCompressor { Snappy.maxCompressedLength(it.size).let { max -> Snappy.rawCompress(it, 0, it.size, ByteArray(max), 0) } } }

fun deflateBlock(level: Int) = BlockAlgo("deflate-$level") {
    val deflater = Deflater(level, true)
    BlockCompressor { data ->
        deflater.reset()
        deflater.setInput(data)
        deflater.finish()
        val buf = ByteArray(64 + data.size * 2)
        var total = 0
        while (!deflater.finished()) total += deflater.deflate(buf)
        total
    }
}

fun brotliBlock(quality: Int) = BlockAlgo("brotli-$quality") {
    Brotli4jLoader.ensureAvailability()
    val params = Encoder.Parameters().setQuality(quality)
    BlockCompressor { Encoder.compress(it, params).size }
}

private class ZstdStream(level: Int, windowLog: Int?) : StreamCompressor {
    private val ctx = ZstdCompressCtx().apply {
        setLevel(level)
        setMagicless(true)
        setChecksum(false)
        setContentSize(false)
        if (windowLog != null) setWindowLog(windowLog)
    }
    private var src = ByteBuffer.allocateDirect(1 shl 16)
    private val dst = ByteBuffer.allocateDirect(1 shl 17)

    override fun write(data: ByteArray): Int {
        if (src.capacity() < data.size) src = ByteBuffer.allocateDirect(data.size)
        src.clear()
        src.put(data)
        src.flip()
        var total = 0
        while (true) {
            dst.clear()
            val done = ctx.compressDirectByteBufferStream(dst, src, EndDirective.FLUSH)
            total += dst.position()
            if (done) return total
        }
    }

    override fun close() = ctx.close()
}

private class DeflateStream(level: Int) : StreamCompressor {
    private val deflater = Deflater(level, true)
    private val buf = ByteArray(1 shl 17)

    override fun write(data: ByteArray): Int {
        deflater.setInput(data)
        var total = 0
        while (true) {
            val n = deflater.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
            total += n
            if (n < buf.size) return total
        }
    }

    override fun close() = deflater.end()
}

private class CountingOutputStream : OutputStream() {
    var count = 0L
    override fun write(b: Int) {
        count++
    }
    override fun write(b: ByteArray, off: Int, len: Int) {
        count += len
    }
}

private class BrotliStream(quality: Int, lgwin: Int) : StreamCompressor {
    private val counter = CountingOutputStream()
    private val out: BrotliOutputStream

    init {
        Brotli4jLoader.ensureAvailability()
        out = BrotliOutputStream(counter, Encoder.Parameters().setQuality(quality).setWindow(lgwin), 1 shl 16)
    }

    override fun write(data: ByteArray): Int {
        val before = counter.count
        out.write(data)
        out.flush()
        return (counter.count - before).toInt()
    }

    override fun close() = out.close()
}
