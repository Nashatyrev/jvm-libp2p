package io.libp2p.tools.gossipcompression

/**
 * Plain LZ77 (literals + copies, no entropy coding) where copies may also reach into a history of
 * the last [history] bytes of the previous messages of the same stream.
 *
 * Output uses the Snappy block format unchanged: a varint uncompressed length, then literal and
 * copy elements. The only extension is that a copy offset may point before the start of the current
 * message, into the history. With [history] = 0 this is ordinary per-message Snappy.
 * Copies are limited to [maxOffset] = max(64 KiB - 1, history) bytes back; beyond 64 KiB they need
 * Snappy's 4-byte-offset copy element.
 *
 * The same parse is also priced in the LZ4 block format ([lastLz4Size]), which is valid only while
 * every offset fits in 16 bits, i.e. [history] <= 65535.
 *
 * [chainDepth] is how many earlier positions with the same 4-byte hash are tried (1 = Snappy-like
 * single hash table). It only affects the encoder; the decoder is the same.
 */
class LzHistoryEncoder(private val history: Int, private val chainDepth: Int, private val hashBits: Int = 16) {
    val maxOffset = maxOf(65535, history)
    private var buf = ByteArray(maxOf(4 * history, 1 shl 16))
    private var size = 0
    private val head = IntArray(1 shl hashBits) { -1 }
    private var prev = IntArray(buf.size)

    private var out = ByteArray(1 shl 16)
    var lastSize = 0
        private set
    var lastLz4Size = 0
        private set
    val lastOutput get() = out

    /** Encodes [msg]; the result is [lastOutput] up to [lastSize] (Snappy format) and [lastLz4Size]. */
    fun encode(msg: ByteArray) {
        val n = msg.size
        makeRoom(n)
        val start = size
        val end = start + n
        System.arraycopy(msg, 0, buf, start, n)
        size = end
        // the last positions of the previous message could not be hashed until more bytes arrived
        for (p in maxOf(0, start - 3) until start) if (p + 4 <= end) insert(p)

        ensureOut(16 + n + n / 6)
        var o = writeVarint(out, 0, n)
        var lz4 = 0
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
                while (c >= 0 && depth < chainDepth && c >= minPos && i - c <= maxOffset) {
                    if (buf[c + bestLen] == buf[i + bestLen]) {
                        var l = 0
                        while (l < maxLen && buf[c + l] == buf[i + l]) l++
                        if (l > bestLen) {
                            bestLen = l
                            bestOff = i - c
                            if (l == maxLen) break
                        }
                    }
                    c = prev[c]
                    depth++
                }
                insert(i)
            }
            if (bestLen >= 4) {
                o = emitLiteral(litStart, i, o)
                o = emitCopy(bestOff, bestLen, o)
                lz4 += 1 + lz4ExtLen(i - litStart) + (i - litStart) + 2 + lz4ExtLen(bestLen - 4)
                for (p in i + 1 until i + bestLen) if (p + 4 <= end) insert(p)
                i += bestLen
                litStart = i
            } else {
                i++
            }
        }
        o = emitLiteral(litStart, end, o)
        lz4 += 1 + lz4ExtLen(end - litStart) + (end - litStart)
        lastSize = o
        lastLz4Size = lz4
    }

    /** Keeps only the last [history] bytes in front of the buffer and re-indexes them. */
    private fun makeRoom(n: Int) {
        if (size + n <= buf.size) return
        val keep = minOf(history, size)
        val newCap = maxOf(buf.size, keep + n)
        val newBuf = ByteArray(newCap)
        System.arraycopy(buf, size - keep, newBuf, 0, keep)
        buf = newBuf
        size = keep
        prev = IntArray(newCap)
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
        prev[p] = head[h]
        head[h] = p
    }

    private fun ensureOut(cap: Int) {
        if (out.size < cap) out = ByteArray(cap)
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
}

private fun lz4ExtLen(x: Int) = if (x < 15) 0 else (x - 15) / 255 + 1

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

class LzFormatException(msg: String) : RuntimeException(msg)

/**
 * Decoder for [LzHistoryEncoder] output. This is all a receiver would need: a Snappy decoder whose
 * output buffer is prefixed by the last [history] bytes of the previous messages. Every length and
 * offset is checked before use, and the declared output length is capped by [maxLen], so the
 * output can never exceed that cap (the decompression-bomb bound is the same as today's).
 */
class LzHistoryDecoder(private val history: Int, private val maxLen: Int) {
    private val maxOffset = maxOf(65535, history)
    private var buf = ByteArray(maxOf(4 * history, 1 shl 16))
    private var size = 0

    fun decode(src: ByteArray, srcLen: Int): ByteArray {
        var s = 0
        var n = 0
        var shift = 0
        while (true) {
            if (s >= srcLen || shift > 28) throw LzFormatException("bad length preamble")
            val b = src[s++].toInt() and 0xff
            n = n or ((b and 0x7f) shl shift)
            shift += 7
            if (b < 0x80) break
        }
        if (n < 0 || n > maxLen) throw LzFormatException("declared length $n exceeds limit $maxLen")
        if (size + n > buf.size) {
            val keep = minOf(history, size)
            val newBuf = ByteArray(maxOf(buf.size, keep + n))
            System.arraycopy(buf, size - keep, newBuf, 0, keep)
            buf = newBuf
            size = keep
        }
        val start = size
        val end = start + n
        val minPos = start - minOf(history, start)
        var d = start
        while (s < srcLen) {
            val tag = src[s++].toInt() and 0xff
            when (tag and 3) {
                0 -> {
                    var len = tag ushr 2
                    if (len >= 60) {
                        val extra = len - 59
                        if (s + extra > srcLen) throw LzFormatException("truncated literal length")
                        len = 0
                        for (k in 0 until extra) len = len or ((src[s++].toInt() and 0xff) shl (8 * k))
                    }
                    len += 1
                    if (len <= 0 || len > end - d || len > srcLen - s) throw LzFormatException("literal out of bounds")
                    System.arraycopy(src, s, buf, d, len)
                    s += len
                    d += len
                }
                else -> {
                    val len: Int
                    val off: Int
                    when (tag and 3) {
                        1 -> {
                            if (s + 1 > srcLen) throw LzFormatException("truncated copy")
                            len = ((tag ushr 2) and 7) + 4
                            off = ((tag ushr 5) shl 8) or (src[s++].toInt() and 0xff)
                        }
                        2 -> {
                            if (s + 2 > srcLen) throw LzFormatException("truncated copy")
                            len = (tag ushr 2) + 1
                            off = (src[s].toInt() and 0xff) or ((src[s + 1].toInt() and 0xff) shl 8)
                            s += 2
                        }
                        else -> {
                            if (s + 4 > srcLen) throw LzFormatException("truncated copy")
                            len = (tag ushr 2) + 1
                            off = (src[s].toInt() and 0xff) or ((src[s + 1].toInt() and 0xff) shl 8) or
                                ((src[s + 2].toInt() and 0xff) shl 16) or ((src[s + 3].toInt() and 0xff) shl 24)
                            s += 4
                        }
                    }
                    if (off <= 0 || off > maxOffset || d - off < minPos) throw LzFormatException("copy offset $off out of bounds")
                    if (len > end - d) throw LzFormatException("copy past declared length")
                    for (k in 0 until len) buf[d + k] = buf[d - off + k]
                    d += len
                }
            }
        }
        if (d != end) throw LzFormatException("decoded ${d - start} bytes, declared $n")
        size = end
        return buf.copyOfRange(start, end)
    }
}
