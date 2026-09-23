package io.libp2p.compression

import io.libp2p.core.multistream.ProtocolId

/**
 * `/snappy-stream` stream compression.
 *
 * A stream negotiated as [PREFIX] + `<protocol>` (e.g. `/snappy-stream/meshsub/1.2.0`) runs
 * `<protocol>` over a stream compressed with [SnappyStreamCodec]: Snappy blocks which may copy
 * from the last [HISTORY_SIZE] bytes of the stream. An initiator offers the prefixed id first and
 * the plain id right after it, so a peer without support answers `na` and the plain protocol is
 * negotiated instead. To the protocol the stream looks like a plain `<protocol>` stream.
 *
 * Wire format, after multistream has agreed on the prefixed id (the negotiation itself is plain):
 * ```
 * stream := frame*
 * frame  := uvarint(len(block)) block                 ; len(block) <= 32 + B + B/6
 * block  := uvarint(n) element*                        ; Snappy block format, n <= B decoded bytes
 * ```
 * with `B` = [MAX_BLOCK_SIZE]. Decoding a block appends its `n` bytes to the stream's output; a
 * copy element may reach back up to 65535 bytes, into the current block or into the last
 * [HISTORY_SIZE] bytes of the previous blocks of the same direction. Each direction has its own
 * history, starting empty. A sender may cut blocks anywhere (this one cuts at every write, and at
 * `B`); the receiver must reject a block that declares more than `B` bytes, has an element
 * outside its input, a copy outside the history, or doesn't decode to exactly `n` bytes, and then
 * close the stream.
 *
 * The parameters are part of the protocol: a different history or block size needs a different prefix.
 */
object SnappyStreamCompression {
    const val PREFIX = "/snappy-stream"

    /** Up to 64 KiB back, so that every copy fits Snappy's 2-byte-offset element. */
    const val HISTORY_SIZE = 65535

    /** Largest decoded block; longer writes are split. Bounds what one block can expand to. */
    const val MAX_BLOCK_SIZE = 64 * 1024

    const val CODEC_HANDLER_NAME = "SnappyStreamCodec"

    fun compressed(protocol: ProtocolId): ProtocolId = PREFIX + protocol

    /** The wrapped protocol if [protocol] is a compressed one, else null. */
    fun uncompressed(protocol: ProtocolId): ProtocolId? =
        if (protocol.startsWith("$PREFIX/")) protocol.substring(PREFIX.length) else null
}
