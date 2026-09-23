package io.libp2p.compression

/**
 * Snappy block format where copies may also reach into the last [history] bytes of the previous
 * blocks of the same stream.
 *
 * A block is a varint decoded length followed by Snappy literal and copy elements, byte for byte
 * the Snappy block format. The only extension is that a copy offset may point before the start of
 * the current block, into the history. With `history = 0` this is ordinary Snappy.
 *
 * Copies may reach at most [maxOffset] = max(65535, history) bytes back.
 */
private fun maxOffsetFor(history: Int) = maxOf(65535, history)

/**
 * Encoder side of [SnappyHistoryDecoder]. NOT thread safe.
 *
 * A greedy parser over a 4-byte hash table. With [chainDepth] = 1 (the default) it keeps only the
 * latest position per hash, like Snappy's own encoder; a deeper chain tries more earlier positions
 * for a slightly better ratio at a higher CPU and memory cost. The decoder doesn't depend on it.
 */
class SnappyHistoryEncoder(
    private val history: Int,
    private val maxBlockSize: Int,
    private val chainDepth: Int = 1,
    private val hashBits: Int = 14
) {
    private val maxOffset = maxOffsetFor(history)
    private var buf = ByteArray(2 * history + maxBlockSize)
    private var size = 0
    private val head = IntArray(1 shl hashBits) { -1 }
    private var prev: IntArray? = if (chainDepth > 1) IntArray(buf.size) else null

    private var out = ByteArray(maxCompressedLength(maxBlockSize))

    /** Encodes [len] bytes of [src] from [off] as one block; returns the size of the block in [output]. */
    fun encode(src: ByteArray, off: Int, len: Int): Int {
        require(len <= maxBlockSize) { "Block of $len bytes exceeds $maxBlockSize" }
        makeRoom(len)
        val start = size
        val end = start + len
        System.arraycopy(src, off, buf, start, len)
        size = end
        // the last positions of the previous block could not be hashed until more bytes arrived
        for (p in maxOf(0, start - 3) until start) if (p + 4 <= end) insert(p)

        var o = writeVarint(out, 0, len)
        var i = start
        var litStart = start
        val minPos = start - minOf(history, start)
        while (i < end) {
            var bestLen = 0
            var bestOff = 0
            if (i + 4 <= end) {
                var c = head[hash(i)]
                var depth = 0
                val maxLen = end - i
                while (c >= minPos && depth < chainDepth && i - c <= maxOffset) {
                    if (buf[c + bestLen] == buf[i + bestLen]) {
                        var l = 0
                        while (l < maxLen && buf[c + l] == buf[i + l]) l++
                        if (l > bestLen) {
                            bestLen = l
                            bestOff = i - c
                            if (l == maxLen) break
                        }
                    }
                    c = prev?.get(c) ?: -1
                    depth++
                }
                insert(i)
            }
            if (bestLen >= 4) {
                o = emitLiteral(litStart, i, o)
                o = emitCopy(bestOff, bestLen, o)
                for (p in i + 1 until i + bestLen) if (p + 4 <= end) insert(p)
                i += bestLen
                litStart = i
            } else {
                i++
            }
        }
        return emitLiteral(litStart, end, o)
    }

    /** The last encoded block, valid until the next [encode] call. */
    val output: ByteArray get() = out

    private fun makeRoom(n: Int) {
        if (size + n <= buf.size) return
        val keep = minOf(history, size)
        System.arraycopy(buf, size - keep, buf, 0, keep)
        size = keep
        head.fill(-1)
        for (p in 0..keep - 4) insert(p)
    }

    private fun hash(p: Int): Int {
        val v = (buf[p].toInt() and 0xff) or ((buf[p + 1].toInt() and 0xff) shl 8) or
            ((buf[p + 2].toInt() and 0xff) shl 16) or ((buf[p + 3].toInt() and 0xff) shl 24)
        return (v * 0x1e35a7bd) ushr (32 - hashBits)
    }

    private fun insert(p: Int) {
        val h = hash(p)
        prev?.set(p, head[h])
        head[h] = p
    }

    private fun emitLiteral(from: Int, to: Int, pos: Int): Int {
        val len = to - from
        if (len == 0) return pos
        var o = pos
        val n = len - 1
        when {
            n < 60 -> out[o++] = (n shl 2).toByte()
            n < 1 shl 8 -> {
                out[o++] = (60 shl 2).toByte()
                out[o++] = n.toByte()
            }
            n < 1 shl 16 -> {
                out[o++] = (61 shl 2).toByte()
                o = le(n, 2, o)
            }
            n < 1 shl 24 -> {
                out[o++] = (62 shl 2).toByte()
                o = le(n, 3, o)
            }
            else -> {
                out[o++] = (63 shl 2).toByte()
                o = le(n, 4, o)
            }
        }
        System.arraycopy(buf, from, out, o, len)
        return o + len
    }

    // Same splitting as Snappy's emitCopy: chunks of 64, keeping the remainder >= 4
    private fun emitCopy(offset: Int, length: Int, pos: Int): Int {
        var o = pos
        var len = length
        while (len >= 68) {
            o = emitCopyChunk(offset, 64, o)
            len -= 64
        }
        if (len > 64) {
            o = emitCopyChunk(offset, 60, o)
            len -= 60
        }
        return emitCopyChunk(offset, len, o)
    }

    private fun emitCopyChunk(offset: Int, len: Int, pos: Int): Int {
        var o = pos
        when {
            len in 4..11 && offset < 2048 -> {
                out[o++] = (1 or ((len - 4) shl 2) or ((offset ushr 8) shl 5)).toByte()
                out[o++] = offset.toByte()
            }
            offset < 65536 -> {
                out[o++] = (2 or ((len - 1) shl 2)).toByte()
                o = le(offset, 2, o)
            }
            else -> {
                out[o++] = (3 or ((len - 1) shl 2)).toByte()
                o = le(offset, 4, o)
            }
        }
        return o
    }

    private fun le(v: Int, bytes: Int, pos: Int): Int {
        for (k in 0 until bytes) out[pos + k] = (v ushr (8 * k)).toByte()
        return pos + bytes
    }

    companion object {
        /** Upper bound of an encoded block, the same bound as Snappy's. */
        fun maxCompressedLength(blockSize: Int) = 32 + blockSize + blockSize / 6
    }
}

private fun writeVarint(dst: ByteArray, pos: Int, value: Int): Int {
    var v = value
    var o = pos
    while (v >= 0x80) {
        dst[o++] = ((v and 0x7f) or 0x80).toByte()
        v = v ushr 7
    }
    dst[o++] = v.toByte()
    return o
}

class SnappyHistoryFormatException(msg: String) : RuntimeException(msg)

/**
 * Decoder for [SnappyHistoryEncoder] blocks: a Snappy decoder whose output buffer is prefixed by
 * the last [history] bytes decoded on this stream. NOT thread safe.
 *
 * Every length and offset is checked before it is used and a block may declare at most
 * [maxBlockSize] decoded bytes, so one block can never produce more than that (the same
 * decompression bomb bound as plain Snappy with the same limit). Memory is fixed: history plus
 * one block. After any [SnappyHistoryFormatException] the state is undefined and the stream must
 * be closed.
 */
class SnappyHistoryDecoder(private val history: Int, private val maxBlockSize: Int) {
    private val maxOffset = maxOffsetFor(history)
    private val buf = ByteArray(2 * history + maxBlockSize)
    private var size = 0

    /** Decodes one block from `src[off, off + len)`. Returns the decoded bytes. */
    fun decode(src: ByteArray, off: Int, len: Int): ByteArray {
        val srcEnd = off + len
        var s = off
        var n = 0
        var shift = 0
        while (true) {
            if (s >= srcEnd || shift > 28) throw SnappyHistoryFormatException("bad length preamble")
            val b = src[s++].toInt() and 0xff
            n = n or ((b and 0x7f) shl shift)
            shift += 7
            if (b < 0x80) break
        }
        if (n < 0 || n > maxBlockSize) throw SnappyHistoryFormatException("declared block length $n exceeds $maxBlockSize")
        if (size + n > buf.size) {
            val keep = minOf(history, size)
            System.arraycopy(buf, size - keep, buf, 0, keep)
            size = keep
        }
        val start = size
        val end = start + n
        val minPos = start - minOf(history, start)
        var d = start
        while (s < srcEnd) {
            val tag = src[s++].toInt() and 0xff
            if (tag and 3 == 0) {
                var litLen = tag ushr 2
                if (litLen >= 60) {
                    val extra = litLen - 59
                    if (s + extra > srcEnd) throw SnappyHistoryFormatException("truncated literal length")
                    litLen = 0
                    for (k in 0 until extra) litLen = litLen or ((src[s++].toInt() and 0xff) shl (8 * k))
                }
                litLen += 1
                if (litLen <= 0 || litLen > end - d || litLen > srcEnd - s) {
                    throw SnappyHistoryFormatException("literal out of bounds")
                }
                System.arraycopy(src, s, buf, d, litLen)
                s += litLen
                d += litLen
            } else {
                val copyLen: Int
                val offset: Int
                when (tag and 3) {
                    1 -> {
                        if (s + 1 > srcEnd) throw SnappyHistoryFormatException("truncated copy")
                        copyLen = ((tag ushr 2) and 7) + 4
                        offset = ((tag ushr 5) shl 8) or (src[s++].toInt() and 0xff)
                    }
                    2 -> {
                        if (s + 2 > srcEnd) throw SnappyHistoryFormatException("truncated copy")
                        copyLen = (tag ushr 2) + 1
                        offset = (src[s].toInt() and 0xff) or ((src[s + 1].toInt() and 0xff) shl 8)
                        s += 2
                    }
                    else -> {
                        if (s + 4 > srcEnd) throw SnappyHistoryFormatException("truncated copy")
                        copyLen = (tag ushr 2) + 1
                        offset = (src[s].toInt() and 0xff) or ((src[s + 1].toInt() and 0xff) shl 8) or
                            ((src[s + 2].toInt() and 0xff) shl 16) or ((src[s + 3].toInt() and 0xff) shl 24)
                        s += 4
                    }
                }
                if (offset <= 0 || offset > maxOffset || d - offset < minPos) {
                    throw SnappyHistoryFormatException("copy offset $offset out of bounds")
                }
                if (copyLen > end - d) throw SnappyHistoryFormatException("copy past declared length")
                // byte by byte: a copy may overlap its own output
                for (k in 0 until copyLen) buf[d + k] = buf[d - offset + k]
                d += copyLen
            }
        }
        if (d != end) throw SnappyHistoryFormatException("decoded ${d - start} bytes, declared $n")
        size = end
        return buf.copyOfRange(start, end)
    }
}
