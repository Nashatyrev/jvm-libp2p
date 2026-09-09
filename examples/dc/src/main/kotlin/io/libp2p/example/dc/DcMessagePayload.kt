package io.libp2p.example.dc

import com.google.protobuf.ByteString
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Kinds of message the scenario publishes, told apart on the wire by [magic] rather than by a field
 * of their own. Keeping the magic in the first four bytes means every other field sits at the same
 * offset for every kind, so [GossipByteCounter] can attribute bytes to a wave without caring which
 * kind it is looking at.
 */
enum class DcMessageKind(val magic: Int) {
    ATTESTATION(0x0DCA7757),
    BLOCK(0x0DCB10CC);

    companion object {
        fun ofMagic(magic: Int): DcMessageKind? = values().firstOrNull { it.magic == magic }
    }
}

/** Header of a published payload, as read back by the receiver. */
data class DcMessageHeader(
    val kind: DcMessageKind,
    val id: Int,
    val waveIndex: Int,
    val subnetId: Int,
    val publishedAtNanos: Long
) {
    val publishedAt: Duration get() = publishedAtNanos.nanoseconds
}

/**
 * The wire format every published message shares:
 * `[magic][id][waveIndex][subnetId][publishedAtNanos]` followed by random filler up to the
 * configured message size.
 *
 * The publisher's own timestamp travels in the payload, which is what makes delivery latency
 * measurable: every node's scheduler starts at zero and they advance in lockstep, so
 * `timer.elapsedTime()` is a clock shared by all nodes and the subtraction is exact.
 *
 * Filler is random rather than zeroed so that nothing downstream can compress it away and make a
 * configured size mean less than it says.
 */
object DcMessagePayload {

    /** magic + id + wave + subnet + timestamp */
    const val HEADER_BYTES: Int = 4 + 4 + 4 + 4 + 8

    /** Byte offset of the wave index, for readers that only need that. */
    private const val WAVE_INDEX_OFFSET: Int = 4 + 4

    /** [DcMessageHeader.subnetId] of a kind that has no subnet, e.g. a block on its global topic. */
    const val NO_SUBNET: Int = -1

    fun encode(
        kind: DcMessageKind,
        id: Int,
        waveIndex: Int,
        subnetId: Int,
        publishedAt: Duration,
        sizeBytes: Int,
        random: Random
    ): ByteArray {
        require(sizeBytes >= HEADER_BYTES) {
            "$kind size must be at least $HEADER_BYTES bytes, got $sizeBytes"
        }
        val payload = ByteArray(sizeBytes)
        random.nextBytes(payload)
        ByteBuffer.wrap(payload).apply {
            putInt(kind.magic)
            putInt(id)
            putInt(waveIndex)
            putInt(subnetId)
            putLong(publishedAt.inWholeNanoseconds)
        }
        return payload
    }

    /** Header of [payload], or null if it is not one of ours. */
    fun decode(payload: ByteArray): DcMessageHeader? {
        if (payload.size < HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(payload)
        val kind = DcMessageKind.ofMagic(buffer.int) ?: return null
        return DcMessageHeader(
            kind = kind,
            id = buffer.int,
            waveIndex = buffer.int,
            subnetId = buffer.int,
            publishedAtNanos = buffer.long
        )
    }

    /**
     * Wave index of a payload seen on the wire, or null if it is not one of ours — the magic guard
     * keeps foreign traffic on the same channels from being attributed to a wave.
     *
     * Takes a [ByteString] and reads only the two fields it needs, so counting bytes never copies a
     * megabyte-sized block payload out of protobuf.
     */
    fun waveIndexOf(data: ByteString): Int? {
        if (data.size() < HEADER_BYTES) return null
        if (DcMessageKind.ofMagic(intAt(data, 0)) == null) return null
        return intAt(data, WAVE_INDEX_OFFSET)
    }

    /** Big-endian int, matching the [ByteBuffer] the payload is written with. */
    private fun intAt(data: ByteString, offset: Int): Int =
        ((data.byteAt(offset).toInt() and 0xFF) shl 24) or
            ((data.byteAt(offset + 1).toInt() and 0xFF) shl 16) or
            ((data.byteAt(offset + 2).toInt() and 0xFF) shl 8) or
            (data.byteAt(offset + 3).toInt() and 0xFF)
}
