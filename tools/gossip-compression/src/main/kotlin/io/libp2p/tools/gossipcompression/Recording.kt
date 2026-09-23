package io.libp2p.tools.gossipcompression

import com.google.protobuf.CodedOutputStream
import org.xerial.snappy.Snappy
import pubsub.pb.Rpc
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File

/**
 * What a part of an RPC frame is. Publish parts are split by eth2 topic kind; the rest of the
 * frame is subscriptions, control, and the varint length prefix of the frame itself.
 */
enum class Category(val isPublish: Boolean) {
    ATTESTATION(true),
    AGGREGATE(true),
    BLOCK(true),
    DATA_COLUMN(true),
    SYNC_COMMITTEE(true),
    SYNC_CONTRIBUTION(true),
    OTHER_PUBLISH(true),
    CONTROL(false),
    SUBS(false),
    OTHER(false),
    FRAMING(false);

    companion object {
        val publishKinds = values().filter { it.isPublish }

        fun ofTopic(topic: String): Category = when {
            "/beacon_attestation_" in topic -> ATTESTATION
            "/beacon_aggregate_and_proof/" in topic -> AGGREGATE
            "/beacon_block/" in topic -> BLOCK
            "/data_column_sidecar_" in topic -> DATA_COLUMN
            "/sync_committee_contribution_and_proof/" in topic -> SYNC_CONTRIBUTION
            "/sync_committee_" in topic -> SYNC_COMMITTEE
            else -> OTHER_PUBLISH
        }
    }
}

/**
 * One published message inside a frame.
 * [ssz] is the uncompressed payload, [wireDataLen] the size of the snappy block the sender put on the wire,
 * [envelopeLen] the size of the protobuf `Message` without its `data` field (topic string, etc).
 */
class Publish(val category: Category, val topic: String, val ssz: ByteArray, val wireDataLen: Int, val envelopeLen: Int) {
    /** Size of this publish inside the RPC (tag + length + Message) given a `data` field of [dataLen] bytes. */
    fun rpcFieldSize(dataLen: Int): Int {
        val msgLen = envelopeLen + 1 + varintSize(dataLen) + dataLen
        return 1 + varintSize(msgLen) + msgLen
    }
}

/**
 * One gossipsub RPC frame as seen on one stream in one direction.
 * [raw] is the frame (with its varint length prefix) with every publish payload snappy-decompressed,
 * i.e. the input a stream compressor replacing snappy would see. [wireLen] is the same for the frame
 * as it was on the wire. [nonPublishSizes] are the raw sizes of the non-publish
 * parts of the frame (without the length prefix).
 */
class Frame(
    val nanos: Long,
    val streamKey: Int,
    val outbound: Boolean,
    val wireLen: Int,
    val raw: ByteArray,
    val publishes: List<Publish>,
    val nonPublishSizes: Map<Category, Int>
) {
    val dirKey get() = if (outbound) "out" else "in"

    /** Total frame size on the wire (with length prefix) when every publish has a payload of `dataLen(publish)`. */
    inline fun sizeWith(dataLen: (Publish) -> Int): Int {
        var len = nonPublishSizes.values.sum()
        for (p in publishes) len += p.rpcFieldSize(dataLen(p))
        return varintSize(len) + len
    }
}

class StreamInfo(val idx: Int, val peerId: ByteArray, val initiator: Boolean, val protocol: String)

class Recording(val startEpochMillis: Long, val frames: List<Frame>, val streams: Map<Int, StreamInfo>, val droppedRecords: Long, val snappyFailures: Long)

fun varintSize(v: Int): Int = CodedOutputStream.computeUInt32SizeNoTag(v)

/** Reads the `*.grec` files written by Teku's GossipRecordingHandler, in file name order. */
fun readRecording(dir: File, maxNanos: Long = Long.MAX_VALUE): Recording {
    val files = dir.listFiles { f -> f.name.endsWith(".grec") }!!.sortedBy { it.name }
    require(files.isNotEmpty()) { "No .grec files in $dir" }
    val frames = ArrayList<Frame>(10_000_000)
    val streams = HashMap<Int, StreamInfo>()
    var startEpochMillis = 0L
    var dropped = 0L
    var snappyFailures = 0L
    for (file in files) {
        println("Reading ${file.name} (${file.length() shr 20} MiB), frames so far: ${frames.size}")
        DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 20)).use { inp ->
            val magic = ByteArray(4).also { inp.readFully(it) }
            require(String(magic) == "GREC") { "Bad magic in $file" }
            require(inp.readInt() == 1) { "Unsupported version in $file" }
            startEpochMillis = inp.readLong()
            inp.readLong()
            try {
                while (true) {
                    val type = inp.readByte().toInt()
                    val nanos = inp.readLong()
                    val streamIdx = inp.readInt()
                    when (type) {
                        1 -> {
                            val peerId = ByteArray(inp.readShort().toInt()).also { inp.readFully(it) }
                            val initiator = inp.readBoolean()
                            val proto = ByteArray(inp.readShort().toInt()).also { inp.readFully(it) }
                            streams[streamIdx] = StreamInfo(streamIdx, peerId, initiator, String(proto))
                        }
                        2 -> {}
                        3 -> {
                            val outbound = inp.readByte().toInt() == 1
                            val bytes = ByteArray(inp.readInt()).also { inp.readFully(it) }
                            if (nanos <= maxNanos) {
                                val (frame, failures) = parseFrame(nanos, streamIdx, outbound, bytes)
                                frames += frame
                                snappyFailures += failures
                            }
                        }
                        4 -> dropped = inp.readLong()
                        else -> error("Unknown record type $type in $file")
                    }
                }
            } catch (e: EOFException) {
                // the last record of a file which was being written may be truncated
            }
        }
    }
    frames.sortBy { it.nanos }
    return Recording(startEpochMillis, frames, streams, dropped, snappyFailures)
}

private fun parseFrame(nanos: Long, streamIdx: Int, outbound: Boolean, bytes: ByteArray): Pair<Frame, Int> {
    val rpc = Rpc.RPC.parseFrom(bytes)
    var failures = 0
    val publishes = rpc.publishList.map { msg ->
        val wireData = msg.data.toByteArray()
        val ssz = try {
            Snappy.uncompress(wireData)
        } catch (e: Exception) {
            failures++
            wireData
        }
        val envelopeLen = msg.toBuilder().clearData().build().serializedSize
        val topic = msg.topicIDsList.firstOrNull() ?: ""
        Publish(Category.ofTopic(topic), topic, ssz, wireData.size, envelopeLen)
    }
    val nonPublish = HashMap<Category, Int>()
    fun field(c: Category, size: Int) = nonPublish.merge(c, 1 + varintSize(size) + size, Int::plus)
    rpc.subscriptionsList.forEach { field(Category.SUBS, it.serializedSize) }
    if (rpc.hasControl()) field(Category.CONTROL, rpc.control.serializedSize)
    val rawRpc = rpc.toBuilder().apply {
        publishList.forEachIndexed { i, _ -> setPublish(i, getPublish(i).toBuilder().setData(com.google.protobuf.ByteString.copyFrom(publishes[i].ssz))) }
    }.build()
    val known = nonPublish.values.sum() + publishes.sumOf { it.rpcFieldSize(it.wireDataLen) }
    if (bytes.size != known) nonPublish[Category.OTHER] = bytes.size - known
    val raw = rawRpc.toByteArray()
    val framed = ByteArray(varintSize(raw.size) + raw.size)
    CodedOutputStream.newInstance(framed).apply {
        writeUInt32NoTag(raw.size)
        writeRawBytes(raw)
        flush()
    }
    return Frame(nanos, streamIdx, outbound, varintSize(bytes.size) + bytes.size, framed, publishes, nonPublish) to failures
}
