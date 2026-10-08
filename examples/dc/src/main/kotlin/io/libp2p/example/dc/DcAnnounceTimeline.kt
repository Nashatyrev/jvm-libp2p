package io.libp2p.example.dc

import com.google.protobuf.ByteString
import io.libp2p.core.crypto.sha256
import io.libp2p.pubsub.DEFAULT_PUBSUB_MESSAGE_ID_LENGTH
import pubsub.pb.Rpc
import kotlin.time.Duration

/**
 * One node's wire-level timeline per message id: when each IHAVE and IDONTWANT naming it arrived,
 * when this node sent IWANTs for it, and when its first copy arrived. Times are the simulated clock,
 * shared by all nodes, so they compare directly with the publish time carried in the payload.
 *
 * Diagnostic and opt-in ([DcRunConfig.recordAnnounceTimeline]): it keeps every event, which is fine
 * for a hundred nodes and a handful of messages and not for an attestation run.
 */
class DcAnnounceTimeline {

    class Entry {
        var publishedAt: Duration? = null
        var slotIndex: Int? = null
        var firstCopyAt: Duration? = null
        val ihaveAt = mutableListOf<Duration>()
        val idontwantAt = mutableListOf<Duration>()
        val iwantSentAt = mutableListOf<Duration>()
    }

    private val entries = hashMapOf<ByteString, Entry>()
    private val seenDcIds = hashSetOf<Int>()

    @Synchronized
    fun entries(): Map<ByteString, Entry> = entries.toMap()

    private fun entry(id: ByteString) = entries.getOrPut(id) { Entry() }

    @Synchronized
    fun onRead(rpc: Rpc.RPC, now: Duration) {
        rpc.publishList.forEach { message ->
            val dcId = DcMessagePayload.idOf(message.data) ?: return@forEach
            // Hash only the first copy: the id is what IHAVE/IDONTWANT name, and copies are identical.
            if (!seenDcIds.add(dcId)) return@forEach
            val id = ByteString.copyFrom(sha256(message.data.toByteArray()).copyOf(DEFAULT_PUBSUB_MESSAGE_ID_LENGTH))
            val headerBytes = message.data.substring(0, DcMessagePayload.HEADER_BYTES).toByteArray()
            val header = DcMessagePayload.decode(headerBytes)
            entry(id).apply {
                firstCopyAt = now
                publishedAt = header?.publishedAt
                slotIndex = header?.slotIndex
            }
        }
        if (!rpc.hasControl()) return
        rpc.control.ihaveList.forEach { ihave -> ihave.messageIDsList.forEach { entry(it).ihaveAt += now } }
        rpc.control.idontwantList.forEach { idw -> idw.messageIDsList.forEach { entry(it).idontwantAt += now } }
    }

    @Synchronized
    fun onWrite(rpc: Rpc.RPC, now: Duration) {
        if (!rpc.hasControl()) return
        rpc.control.iwantList.forEach { iwant -> iwant.messageIDsList.forEach { entry(it).iwantSentAt += now } }
    }
}
