package io.libp2p.tools.gossipcompression

import com.github.luben.zstd.ZstdDictTrainer

private val categories = Category.values()

/** Bytes per (direction, category), accumulated over the frames of the evaluation window. */
class Tally {
    val bytes = Array(2) { DoubleArray(categories.size) }
    val messages = Array(2) { LongArray(categories.size) }
    var cpuNanos = 0L
    var maxContexts = 0

    fun total(dir: Int? = null, cat: Category? = null): Double =
        (if (dir == null) listOf(0, 1) else listOf(dir)).sumOf { d ->
            if (cat == null) bytes[d].sum() else bytes[d][cat.ordinal]
        }
}

class Window(val skipNanos: Long, val trainToNanos: Long, val evalFromNanos: Long, val evalToNanos: Long) {
    fun isTrain(f: Frame) = f.nanos >= skipNanos && f.nanos < trainToNanos
    fun isEval(f: Frame) = f.nanos >= evalFromNanos && f.nanos < evalToNanos
}

abstract class Scenario(val group: String, val name: String) {
    open val windowBytes: Long = 0
    abstract fun run(rec: Recording, window: Window): Tally

    protected fun Tally.addExact(f: Frame, dataLen: (Publish) -> Int) {
        val d = if (f.outbound) 1 else 0
        var rpcLen = 0
        for (p in f.publishes) {
            val s = p.rpcFieldSize(dataLen(p))
            bytes[d][p.category.ordinal] += s.toDouble()
            messages[d][p.category.ordinal]++
            rpcLen += s
        }
        for ((c, s) in f.nonPublishSizes) {
            bytes[d][c.ordinal] += s.toDouble()
            rpcLen += s
        }
        bytes[d][Category.FRAMING.ordinal] += varintSize(rpcLen).toDouble()
    }

    /** Spreads the compressed size of a whole frame over its parts in proportion to their raw size. */
    protected fun Tally.addProportional(f: Frame, compressed: Int) {
        val d = if (f.outbound) 1 else 0
        val k = compressed.toDouble() / f.raw.size
        var rpcLen = 0
        for (p in f.publishes) {
            val s = p.rpcFieldSize(p.ssz.size)
            bytes[d][p.category.ordinal] += s * k
            messages[d][p.category.ordinal]++
            rpcLen += s
        }
        for ((c, s) in f.nonPublishSizes) {
            bytes[d][c.ordinal] += s * k
            rpcLen += s
        }
        bytes[d][Category.FRAMING.ordinal] += varintSize(rpcLen) * k
    }
}

/** What went over the wire: per-message snappy from whichever client sent it. */
class WireScenario : Scenario("S0", "wire (snappy block)") {
    override fun run(rec: Recording, window: Window) = Tally().apply {
        rec.frames.filter(window::isEval).forEach { addExact(it) { p -> p.wireDataLen } }
    }
}

class RawScenario : Scenario("S0", "no compression") {
    override fun run(rec: Recording, window: Window) = Tally().apply {
        rec.frames.filter(window::isEval).forEach { addExact(it) { p -> p.ssz.size } }
    }
}

/** Every payload compressed on its own, like snappy today but with another algorithm. */
class BlockScenario(group: String, private val algo: BlockAlgo) : Scenario(group, "per-message ${algo.name}") {
    override fun run(rec: Recording, window: Window) = Tally().apply {
        val c = algo.create()
        rec.frames.filter(window::isEval).forEach { f ->
            val t0 = System.nanoTime()
            val sizes = f.publishes.associateWith { c.compress(it.ssz) }
            cpuNanos += System.nanoTime() - t0
            addExact(f) { sizes.getValue(it) }
        }
    }
}

/** Every payload compressed on its own with a static zstd dictionary trained per topic kind. */
class DictScenario(private val level: Int, private val dictSize: Int, private val dicts: Map<Category, ByteArray>) :
    Scenario("S2", "per-message zstd-$level + ${dictSize / 1024}k dict per topic") {
    override fun run(rec: Recording, window: Window) = Tally().apply {
        val compressors = Category.publishKinds.associateWith { zstdBlock(level, dicts[it]).create() }
        rec.frames.filter(window::isEval).forEach { f ->
            val t0 = System.nanoTime()
            val sizes = f.publishes.associateWith { compressors.getValue(it.category).compress(it.ssz) }
            cpuNanos += System.nanoTime() - t0
            addExact(f) { sizes.getValue(it) }
        }
    }
}

/** Whole RPC frames (envelope, topic strings, control included) compressed one by one with one static dictionary. */
class FrameDictScenario(private val level: Int, private val dict: ByteArray) :
    Scenario("S2", "per-frame zstd-$level + ${dict.size / 1024}k frame dict") {
    override fun run(rec: Recording, window: Window) = Tally().apply {
        val c = zstdBlock(level, dict).create()
        rec.frames.filter(window::isEval).forEach { f ->
            val t0 = System.nanoTime()
            val n = c.compress(f.raw)
            cpuNanos += System.nanoTime() - t0
            addProportional(f, varintSize(n) + n)
        }
    }
}

/** Last frame index of every (stream, direction), so that a context can be released when its stream is done. */
private fun lastFrameOf(rec: Recording): Map<Long, Int> {
    val last = HashMap<Long, Int>()
    rec.frames.forEachIndexed { i, f -> last[ctxKey(f)] = i }
    return last
}

private fun ctxKey(f: Frame) = (f.streamKey.toLong() shl 1) or (if (f.outbound) 1L else 0L)

/** One stream compressor per (stream, direction) over the whole RPC byte stream, flushed after every frame. */
class FrameStreamScenario(private val algo: StreamAlgo) : Scenario("S3", "stream ${algo.name} per peer stream") {
    override val windowBytes get() = algo.windowBytes

    override fun run(rec: Recording, window: Window) = Tally().apply {
        val last = lastFrameOf(rec)
        val contexts = HashMap<Long, StreamCompressor>()
        for ((i, f) in rec.frames.withIndex()) {
            if (f.nanos >= window.evalToNanos) break
            val key = ctxKey(f)
            val t0 = System.nanoTime()
            val n = contexts.getOrPut(key) { algo.create() }.write(f.raw)
            if (window.isEval(f)) cpuNanos += System.nanoTime() - t0
            maxContexts = maxOf(maxContexts, contexts.size)
            if (window.isEval(f)) addProportional(f, n)
            if (last[key] == i) contexts.remove(key)!!.close()
        }
        contexts.values.forEach { it.close() }
    }
}

/**
 * One stream compressor per (stream, direction, topic kind) over the publish payloads only;
 * the envelope and control stay uncompressed. With [crossPeer] the context is shared by all
 * peers - not implementable (every connection is decoded separately), only an upper bound.
 */
class TopicStreamScenario(private val algo: StreamAlgo, private val crossPeer: Boolean) :
    Scenario(if (crossPeer) "S5" else "S4", "stream ${algo.name} per ${if (crossPeer) "topic, shared by all peers" else "peer stream and topic"}") {
    override val windowBytes get() = algo.windowBytes

    override fun run(rec: Recording, window: Window) = Tally().apply {
        val last = lastFrameOf(rec)
        val contexts = HashMap<Long, StreamCompressor>()
        for ((i, f) in rec.frames.withIndex()) {
            if (f.nanos >= window.evalToNanos) break
            val streamPart = if (crossPeer) (if (f.outbound) 1L else 0L) else ctxKey(f)
            val t0 = System.nanoTime()
            val sizes = f.publishes.associateWith { p ->
                contexts.getOrPut(streamPart * 16 + p.category.ordinal) { algo.create() }.write(p.ssz)
            }
            if (window.isEval(f)) cpuNanos += System.nanoTime() - t0
            maxContexts = maxOf(maxContexts, contexts.size)
            if (window.isEval(f)) addExact(f) { sizes.getValue(it) }
            if (!crossPeer && last[ctxKey(f)] == i) {
                Category.publishKinds.forEach { c -> contexts.remove(streamPart * 16 + c.ordinal)?.close() }
            }
        }
        contexts.values.forEach { it.close() }
    }
}

/** Trains a zstd dictionary on up to [sampleBytes] of samples. Returns null if there is too little data. */
fun trainDict(samples: Sequence<ByteArray>, dictSize: Int, sampleBytes: Int = 64 shl 20): ByteArray? {
    val trainer = ZstdDictTrainer(sampleBytes, dictSize)
    var count = 0
    for (s in samples) {
        if (!trainer.addSample(s)) break
        count++
    }
    if (count < 1000) return null
    return try {
        trainer.trainSamples()
    } catch (e: Exception) {
        println("Dictionary training failed ($count samples): $e")
        null
    }
}

/**
 * Plain LZ77 with a shared history per (stream, direction) over whole frames, like S3 but without
 * entropy coding (see [LzHistoryEncoder]). Every frame is decoded back and compared with the
 * original, so the numbers come from a verified round trip. With [lz4] the same parse is priced in
 * the LZ4 block format instead (plus varint decoded and encoded lengths, since LZ4 blocks carry
 * neither); only valid for histories up to 64 KiB.
 */
class LzStreamScenario(private val history: Int, private val chainDepth: Int, private val lz4: Boolean) :
    Scenario("S6", "LZ77 ${if (lz4) "lz4" else "snappy"}-format hist ${(history + 1023) / 1024}k depth $chainDepth per peer stream") {
    override val windowBytes get() = history.toLong()

    init {
        require(!lz4 || history <= 65535) { "LZ4 offsets are 16 bit" }
    }

    override fun run(rec: Recording, window: Window) = Tally().apply {
        val last = lastFrameOf(rec)
        val encoders = HashMap<Long, LzHistoryEncoder>()
        val decoders = HashMap<Long, LzHistoryDecoder>()
        for ((i, f) in rec.frames.withIndex()) {
            if (f.nanos >= window.evalToNanos) break
            val key = ctxKey(f)
            val enc = encoders.getOrPut(key) { LzHistoryEncoder(history, chainDepth) }
            val t0 = System.nanoTime()
            enc.encode(f.raw)
            if (window.isEval(f)) cpuNanos += System.nanoTime() - t0
            if (!lz4) {
                val decoded = decoders.getOrPut(key) { LzHistoryDecoder(history, 1 shl 24) }.decode(enc.lastOutput, enc.lastSize)
                check(decoded.contentEquals(f.raw)) { "LZ round trip mismatch at frame $i" }
            }
            maxContexts = maxOf(maxContexts, encoders.size)
            val size = if (lz4) {
                varintSize(f.raw.size) + varintSize(enc.lastLz4Size) + enc.lastLz4Size
            } else {
                varintSize(enc.lastSize) + enc.lastSize
            }
            if (window.isEval(f)) addProportional(f, size)
            if (last[key] == i) {
                encoders.remove(key)
                decoders.remove(key)
            }
        }
    }
}
