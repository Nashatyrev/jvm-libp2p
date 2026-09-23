package io.libp2p.tools.gossipcompression

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Usage: gossip-compression <rec-dir> <out-dir> [--skip-min 5] [--train-min 20] [--eval-min 60] [--only <regex>]
 *
 * The first `skip` minutes of the recording are ignored (node warming up), the next `train`
 * minutes are only used to train the static dictionaries, and the `eval` minutes after that are
 * measured. Stream compressors see every frame of their stream from its start, so their context is
 * warm when the evaluation window begins, as it would be on a long-lived connection.
 */
fun main(args: Array<String>) {
    val recDir = File(args[0])
    val outDir = File(args[1]).also { it.mkdirs() }
    val opts = args.drop(2).chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
    val min = 60_000_000_000L
    val skip = (opts["skip-min"] ?: "5").toLong() * min
    val train = (opts["train-min"] ?: "20").toLong() * min
    val eval = (opts["eval-min"] ?: "60").toLong() * min
    val only = opts["only"]?.toRegex()
    val window = Window(skip, skip + train, skip + train, skip + train + eval)

    val rec = readRecording(recDir, window.evalToNanos)
    val lastNanos = rec.frames.last().nanos
    println(
        "Loaded ${rec.frames.size} frames, ${rec.streams.size} streams, recording span ${lastNanos / min} min, " +
            "dropped records: ${rec.droppedRecords}, snappy failures: ${rec.snappyFailures}"
    )
    require(rec.droppedRecords == 0L) { "The recorder dropped records, the stream contexts would be wrong" }
    if (lastNanos < window.evalToNanos) println("WARNING: recording ends ${(window.evalToNanos - lastNanos) / min} min before the evaluation window does")

    val trainFrames = rec.frames.filter(window::isTrain)
    val dictSizes = listOf(16 shl 10, 64 shl 10, 112 shl 10)
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())

    println("Training dictionaries on ${trainFrames.size} frames")
    val topicDictJobs = dictSizes.associateWith { size ->
        Category.publishKinds.associateWith { cat ->
            pool.submit<ByteArray?> { trainDict(trainFrames.asSequence().flatMap { it.publishes }.filter { it.category == cat }.map { it.ssz }, size) }
        }
    }
    val frameDictJobs = dictSizes.associateWith { size -> pool.submit<ByteArray?> { trainDict(trainFrames.asSequence().map { it.raw }, size) } }
    val topicDicts = topicDictJobs.mapValues { (_, m) -> m.mapValues { it.value.get() }.filterValues { it != null }.mapValues { it.value!! } }
    val frameDicts = frameDictJobs.mapValues { it.value.get() }
    topicDicts.forEach { (size, m) -> println("  ${size / 1024}k topic dicts: ${m.keys}") }

    val scenarios = buildList<Scenario> {
        add(WireScenario())
        add(RawScenario())
        add(BlockScenario("S0", snappyBlock()))
        listOf(zstdBlock(3), zstdBlock(9), deflateBlock(6), brotliBlock(5)).forEach { add(BlockScenario("S1", it)) }
        dictSizes.forEach { size -> add(DictScenario(3, size, topicDicts.getValue(size))) }
        frameDicts.values.filterNotNull().forEach { add(FrameDictScenario(3, it)) }
        listOf(
            zstdStream(1), zstdStream(3), zstdStream(9),
            zstdStream(3, 15), zstdStream(3, 17), zstdStream(3, 20), zstdStream(3, 23),
            deflateStream(6), brotliStream(5, 16), brotliStream(5, 22)
        ).forEach { add(FrameStreamScenario(it)) }
        listOf(zstdStream(3, 17), zstdStream(3, 20), zstdStream(3, 23), deflateStream(6), brotliStream(5, 22))
            .forEach { add(TopicStreamScenario(it, crossPeer = false)) }
        listOf(zstdStream(3, 20), zstdStream(3, 23)).forEach { add(TopicStreamScenario(it, crossPeer = true)) }
    }.filter { only == null || only.containsMatchIn(it.name) || it is WireScenario }

    val results = scenarios.map { s ->
        pool.submit<Tally> {
            val t0 = System.nanoTime()
            s.run(rec, window).also { println("  done in ${(System.nanoTime() - t0) / 1_000_000_000}s: ${s.group} ${s.name}") }
        }
    }.map { it.get() }
    pool.shutdown()
    pool.awaitTermination(1, TimeUnit.MINUTES)

    writeCsv(File(outDir, "results.csv"), scenarios, results)
    val summary = summary(scenarios, results, window, rec)
    File(outDir, "summary.txt").writeText(summary)
    println(summary)
}

private fun writeCsv(file: File, scenarios: List<Scenario>, results: List<Tally>) {
    file.printWriter().use { w ->
        w.println("group,scenario,dir,category,messages,bytes,cpu_ms,max_contexts,window_bytes")
        scenarios.zip(results).forEach { (s, t) ->
            for (d in 0..1) for (c in Category.values()) {
                if (t.bytes[d][c.ordinal] == 0.0) continue
                w.println(
                    "${s.group},\"${s.name}\",${if (d == 1) "out" else "in"},$c,${t.messages[d][c.ordinal]}," +
                        "${t.bytes[d][c.ordinal].toLong()},${t.cpuNanos / 1_000_000},${t.maxContexts},${s.windowBytes}"
                )
            }
        }
    }
}

private fun summary(scenarios: List<Scenario>, results: List<Tally>, window: Window, rec: Recording): String {
    val wire = results[scenarios.indexOfFirst { it is WireScenario }]
    val evalMin = (window.evalToNanos - window.evalFromNanos) / 60_000_000_000.0
    fun pct(t: Tally, cat: Category? = null, dir: Int? = null) =
        "%6.1f%%".format(100 * t.total(dir, cat) / wire.total(dir, cat))
    val sb = StringBuilder()
    val evalFrames = rec.frames.filter(window::isEval)
    sb.appendLine("Evaluation window: %.0f min, frames in window: %d".format(evalMin, evalFrames.size))
    // the S0 accounting rebuilds frame sizes from their parts; it must match the recorded frames
    sb.appendLine("Consistency: recorded wire bytes %d, S0 reconstructed %d".format(evalFrames.sumOf { it.wireLen.toLong() }, wire.total().toLong()))
    sb.appendLine()
    sb.appendLine("Wire bytes (baseline) by category, MiB:            in        out")
    for (c in Category.values()) {
        val i = wire.total(0, c) / (1 shl 20)
        val o = wire.total(1, c) / (1 shl 20)
        if (i + o > 0) sb.appendLine("  %-20s                          %9.1f  %9.1f   msgs in %d / out %d".format(c, i, o, wire.messages[0][c.ordinal], wire.messages[1][c.ordinal]))
    }
    sb.appendLine("  %-20s                          %9.1f  %9.1f".format("TOTAL", wire.total(0) / (1 shl 20), wire.total(1) / (1 shl 20)))
    sb.appendLine()
    sb.appendLine("Size relative to the wire (100% = today). att = attestation, agg = aggregate")
    sb.appendLine(
        "%-4s %-52s %10s %8s %8s %8s %8s %8s %8s %8s %9s %10s %8s".format(
            "grp", "scenario", "MiB", "total", "in", "out", "att", "agg", "block", "ctrl", "att B/msg", "cpu s/h", "ctx max"
        )
    )
    scenarios.zip(results).forEach { (s, t) ->
        sb.appendLine(
            "%-4s %-52s %10.1f %8s %8s %8s %8s %8s %8s %8s %9.1f %10.1f %8s".format(
                s.group, s.name, t.total() / (1 shl 20), pct(t), pct(t, dir = 0), pct(t, dir = 1),
                pct(t, Category.ATTESTATION), pct(t, Category.AGGREGATE), pct(t, Category.BLOCK), pct(t, Category.CONTROL),
                t.total(cat = Category.ATTESTATION) / (t.messages[0][Category.ATTESTATION.ordinal] + t.messages[1][Category.ATTESTATION.ordinal]),
                t.cpuNanos / 1e9 * 60 / evalMin, if (t.maxContexts > 0) t.maxContexts.toString() else "-"
            )
        )
    }
    return sb.toString()
}
