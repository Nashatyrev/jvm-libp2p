package io.libp2p.tools.gossipcompression

import com.google.protobuf.ByteString
import org.xerial.snappy.Snappy
import pubsub.pb.Rpc
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.security.MessageDigest

/**
 * Narrows a recording made with all attestation subnets subscribed down to what a node with a
 * different subscription would have received over the same connections: publishes, IHAVE, GRAFT
 * and PRUNE of topics it isn't subscribed to are dropped by topic; IWANT and IDONTWANT carry only
 * message ids, which are mapped to topics through the eth2 message id of every recorded message.
 *
 * It's an approximation: a node with fewer subscriptions would have a different mesh and peers.
 */
class NodeProfile(
    val name: String,
    /** Attestation subnets to keep; null keeps all of them. */
    private val attestationSubnets: Set<Int>?,
    /** Whether to keep the sync committee subnets (a node without validators isn't subscribed). */
    private val syncCommitteeSubnets: Boolean,
    private val topicOfMessageId: Map<ByteString, String>
) {
    var unknownIds = 0L
        private set
    var knownIds = 0L
        private set

    fun keepsTopic(topic: String): Boolean = when (Category.ofTopic(topic)) {
        Category.ATTESTATION -> attestationSubnets == null || subnetOf(topic) in attestationSubnets
        Category.SYNC_COMMITTEE -> syncCommitteeSubnets
        else -> true
    }

    private fun keepsId(id: ByteString): Boolean {
        val topic = topicOfMessageId[id]
        if (topic == null) {
            unknownIds++
            return true
        }
        knownIds++
        return keepsTopic(topic)
    }

    /** The frame as this node would have got it, or null if nothing is left of it. */
    fun filter(rpc: Rpc.RPC): Rpc.RPC? {
        val b = rpc.toBuilder()
        b.clearPublish()
        rpc.publishList.filter { keepsTopic(it.topicIDsList.firstOrNull() ?: "") }.forEach { b.addPublish(it) }
        if (rpc.hasControl()) {
            val c = rpc.control
            val cb = c.toBuilder().clearIhave().clearIwant().clearGraft().clearPrune().clearIdontwant()
            c.ihaveList.filter { keepsTopic(it.topicID) }.forEach { cb.addIhave(it) }
            c.graftList.filter { keepsTopic(it.topicID) }.forEach { cb.addGraft(it) }
            c.pruneList.filter { keepsTopic(it.topicID) }.forEach { cb.addPrune(it) }
            c.iwantList.forEach { w ->
                val ids = w.messageIDsList.filter(::keepsId)
                if (ids.isNotEmpty()) cb.addIwant(Rpc.ControlIWant.newBuilder().addAllMessageIDs(ids))
            }
            c.idontwantList.forEach { w ->
                val ids = w.messageIDsList.filter(::keepsId)
                if (ids.isNotEmpty()) cb.addIdontwant(Rpc.ControlIDontWant.newBuilder().addAllMessageIDs(ids))
            }
            val control = cb.build()
            if (control.serializedSize > 0) b.control = control else b.clearControl()
        }
        val out = b.build()
        return if (out.serializedSize == 0) null else out
    }

    companion object {
        private val subnetRegex = Regex("/beacon_attestation_(\\d+)/")

        fun subnetOf(topic: String): Int = subnetRegex.find(topic)!!.groupValues[1].toInt()

        /** Parses `all` or a comma separated list of attestation subnets. */
        fun create(spec: String, topicOfMessageId: Map<ByteString, String>): NodeProfile =
            if (spec == "all") {
                NodeProfile("all subnets", null, true, topicOfMessageId)
            } else {
                val subnets = spec.split(",").map { it.trim().toInt() }.toSet()
                NodeProfile("attestation subnets $subnets, no sync committee subnets", subnets, false, topicOfMessageId)
            }

        private val DOMAIN_VALID_SNAPPY = byteArrayOf(1, 0, 0, 0)
        private val DOMAIN_INVALID_SNAPPY = byteArrayOf(0, 0, 0, 0)

        /** The eth2 (Altair+) gossip message id. */
        fun messageId(topic: String, wireData: ByteArray): ByteString {
            val sha = MessageDigest.getInstance("SHA-256")
            val topicBytes = topic.toByteArray()
            val decompressed = try {
                Snappy.uncompress(wireData)
            } catch (e: Exception) {
                null
            }
            sha.update(if (decompressed != null) DOMAIN_VALID_SNAPPY else DOMAIN_INVALID_SNAPPY)
            val len = topicBytes.size.toLong()
            sha.update(ByteArray(8) { (len ushr (8 * it)).toByte() })
            sha.update(topicBytes)
            sha.update(decompressed ?: wireData)
            return ByteString.copyFrom(sha.digest(), 0, 20)
        }

        /** First pass over the recording: the topic of every message id seen in a publish. */
        fun scanMessageIds(dir: File): Map<ByteString, String> {
            val map = HashMap<ByteString, String>(4_000_000)
            val files = dir.listFiles { f -> f.name.endsWith(".grec") }!!.sortedBy { it.name }
            for (file in files) {
                println("Scanning message ids in ${file.name}, ids so far: ${map.size}")
                DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 20)).use { inp ->
                    inp.skipBytes(24)
                    try {
                        while (true) {
                            val type = inp.readByte().toInt()
                            inp.skipBytes(12)
                            when (type) {
                                1 -> {
                                    inp.skipBytes(inp.readShort().toInt())
                                    inp.readBoolean()
                                    inp.skipBytes(inp.readShort().toInt())
                                }
                                2 -> {}
                                3 -> {
                                    inp.readByte()
                                    val bytes = ByteArray(inp.readInt()).also { inp.readFully(it) }
                                    for (msg in Rpc.RPC.parseFrom(bytes).publishList) {
                                        val topic = msg.topicIDsList.firstOrNull() ?: continue
                                        val id = messageId(topic, msg.data.toByteArray())
                                        if (id !in map) map[id] = topic
                                    }
                                }
                                4 -> inp.readLong()
                                else -> error("Unknown record type $type in $file")
                            }
                        }
                    } catch (e: EOFException) {
                        // truncated tail of the last file
                    }
                }
            }
            return map
        }
    }
}
