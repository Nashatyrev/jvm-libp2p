package io.libp2p.transport.quic.dos

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Builds QUIC v1 client Initial packets around a fixed ClientHello, the way a handshake flooder would.
 *
 * Initial packets are protected only with keys derived from the destination connection id (RFC 9001 §5.2),
 * so anyone can forge one for any DCID with a few HMACs and one AES-GCM pass, no asymmetric crypto.
 * The ClientHello itself (key share included) is captured once and replayed into every packet:
 * a server cannot tell a replayed ClientHello from a fresh one before doing its own key exchange and signature.
 *
 * NOT thread safe: holds reusable Mac and Cipher instances.
 */
class QuicInitialForger(
    /** The complete TLS ClientHello handshake message (type, 3-byte length, body). */
    private val clientHello: ByteArray,
    /** Must match the initial_source_connection_id transport parameter inside [clientHello]. */
    private val scid: ByteArray
) {
    private val hmac = Mac.getInstance("HmacSHA256")
    private val gcm = Cipher.getInstance("AES/GCM/NoPadding")
    private val ecb = Cipher.getInstance("AES/ECB/NoPadding")

    /** Returns the datagrams (each padded to at least [MIN_INITIAL_DATAGRAM] bytes) carrying the ClientHello for [dcid]. */
    fun forge(dcid: ByteArray): List<ByteArray> {
        val keys = clientInitialKeys(dcid)
        val datagrams = mutableListOf<ByteArray>()
        var offset = 0
        var pn = 0L
        while (offset < clientHello.size) {
            val chunk = minOf(MAX_CRYPTO_CHUNK, clientHello.size - offset)
            datagrams += protect(keys, dcid, pn++, cryptoFrame(offset, chunk))
            offset += chunk
        }
        return datagrams
    }

    private fun cryptoFrame(offset: Int, length: Int): ByteArray {
        val out = ByteArrayOutputStream(length + 16)
        out.write(0x06)
        writeVarint(out, offset.toLong())
        writeVarint(out, length.toLong())
        out.write(clientHello, offset, length)
        return out.toByteArray()
    }

    private fun protect(keys: InitialKeys, dcid: ByteArray, pn: Long, frames: ByteArray): ByteArray {
        val pnLen = 4
        val headerLen = 1 + 4 + 1 + dcid.size + 1 + scid.size + 1 + 2 + pnLen
        val plaintextLen = maxOf(frames.size, MIN_INITIAL_DATAGRAM - headerLen - TAG_LEN)
        val plaintext = frames.copyOf(plaintextLen) // zero tail is PADDING frames

        val buf = ByteBuffer.allocate(headerLen + plaintextLen + TAG_LEN)
        buf.put((0xC0 or (pnLen - 1)).toByte()) // long header, Initial
        buf.putInt(QUIC_V1)
        buf.put(dcid.size.toByte()).put(dcid)
        buf.put(scid.size.toByte()).put(scid)
        buf.put(0) // token length
        buf.putShort((0x4000 or (pnLen + plaintextLen + TAG_LEN)).toShort()) // 2-byte varint Length
        val pnOffset = buf.position()
        buf.putInt(pn.toInt())
        val packet = buf.array()

        gcm.init(Cipher.ENCRYPT_MODE, keys.key, GCMParameterSpec(128, nonce(keys.iv, pn)))
        gcm.updateAAD(packet, 0, headerLen)
        gcm.doFinal(plaintext, 0, plaintextLen, packet, headerLen)

        val mask = ecb.doFinal(packet, pnOffset + 4, 16)
        packet[0] = (packet[0].toInt() xor (mask[0].toInt() and 0x0f)).toByte()
        for (i in 0 until pnLen) packet[pnOffset + i] = (packet[pnOffset + i].toInt() xor mask[1 + i].toInt()).toByte()
        return packet
    }

    private fun clientInitialKeys(dcid: ByteArray): InitialKeys {
        val keys = deriveInitialKeys(hmac, dcid, "client in")
        ecb.init(Cipher.ENCRYPT_MODE, keys.hp)
        return keys
    }

    companion object {
        const val QUIC_V1 = 1
        const val MIN_INITIAL_DATAGRAM = 1200
        private const val TAG_LEN = 16
        private const val MAX_CRYPTO_CHUNK = 1100

        private val INITIAL_SALT_V1 = "38762cf7f55934b34d179ae6a4c80cadccbb7f0a".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        /**
         * Recovers the ClientHello and source connection id from the client Initial datagrams of one handshake,
         * as captured on the wire.
         */
        fun fromCapturedInitials(datagrams: List<ByteArray>): QuicInitialForger {
            val hmac = Mac.getInstance("HmacSHA256")
            val crypto = sortedMapOf<Long, ByteArray>()
            var dcid: ByteArray? = null
            var scid: ByteArray? = null
            for (datagram in datagrams) {
                var pos = 0
                while (pos < datagram.size && datagram[pos].toInt() and 0x80 != 0) {
                    val pkt = parseLongHeader(datagram, pos)
                    if (pkt.type != 0 || pkt.version != QUIC_V1) break
                    if (dcid == null) {
                        dcid = pkt.dcid
                        scid = pkt.scid
                    }
                    if (pkt.dcid.contentEquals(dcid)) {
                        val plaintext = unprotect(hmac, datagram, pos, pkt, dcid)
                        collectCryptoFrames(plaintext, crypto)
                    }
                    pos = pkt.end
                }
            }
            val stream = ByteArrayOutputStream()
            for ((off, data) in crypto) {
                if (off > stream.size()) break
                if (off + data.size > stream.size()) stream.write(data, (stream.size() - off).toInt(), (off + data.size - stream.size()).toInt())
            }
            val bytes = stream.toByteArray()
            require(bytes.size >= 4 && bytes[0].toInt() == 1) { "No ClientHello in the captured Initials" }
            val helloLen = 4 + ((bytes[1].toInt() and 0xff) shl 16 or (bytes[2].toInt() and 0xff shl 8) or (bytes[3].toInt() and 0xff))
            require(bytes.size >= helloLen) { "Captured ClientHello is incomplete: ${bytes.size} of $helloLen bytes" }
            return QuicInitialForger(bytes.copyOf(helloLen), scid!!)
        }

        class LongHeader(
            val type: Int,
            val version: Int,
            val dcid: ByteArray,
            val scid: ByteArray,
            val pnOffset: Int,
            val end: Int
        )

        /** Parses a long header packet starting at [start]; Initial is the only type with a token field. */
        fun parseLongHeader(d: ByteArray, start: Int): LongHeader {
            val bb = ByteBuffer.wrap(d, start, d.size - start)
            val first = bb.get().toInt()
            val version = bb.getInt()
            val dcid = ByteArray(bb.get().toInt() and 0xff).also { bb.get(it) }
            val scid = ByteArray(bb.get().toInt() and 0xff).also { bb.get(it) }
            val type = (first shr 4) and 0x3
            if (type == 0) {
                val tokenLen = readVarint(bb)
                bb.position(bb.position() + tokenLen.toInt())
            }
            if (type == 3) return LongHeader(type, version, dcid, scid, bb.position(), d.size) // Retry
            val length = readVarint(bb).toInt()
            return LongHeader(type, version, dcid, scid, bb.position(), bb.position() + length)
        }

        private fun unprotect(hmac: Mac, d: ByteArray, start: Int, pkt: LongHeader, dcid: ByteArray): ByteArray {
            val keys = deriveInitialKeys(hmac, dcid, "client in")
            val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keys.hp) }
            val packet = d.copyOfRange(start, pkt.end)
            val pnOffset = pkt.pnOffset - start
            val mask = ecb.doFinal(packet, pnOffset + 4, 16)
            packet[0] = (packet[0].toInt() xor (mask[0].toInt() and 0x0f)).toByte()
            val pnLen = (packet[0].toInt() and 0x3) + 1
            var pn = 0L
            for (i in 0 until pnLen) {
                packet[pnOffset + i] = (packet[pnOffset + i].toInt() xor mask[1 + i].toInt()).toByte()
                pn = (pn shl 8) or (packet[pnOffset + i].toLong() and 0xff)
            }
            val headerLen = pnOffset + pnLen
            val gcm = Cipher.getInstance("AES/GCM/NoPadding")
            gcm.init(Cipher.DECRYPT_MODE, keys.key, GCMParameterSpec(128, nonce(keys.iv, pn)))
            gcm.updateAAD(packet, 0, headerLen)
            return gcm.doFinal(packet, headerLen, packet.size - headerLen)
        }

        private fun collectCryptoFrames(plaintext: ByteArray, into: MutableMap<Long, ByteArray>) {
            val bb = ByteBuffer.wrap(plaintext)
            while (bb.hasRemaining()) {
                when (val type = readVarint(bb).toInt()) {
                    0x00, 0x01 -> {} // PADDING, PING
                    0x06 -> {
                        val off = readVarint(bb)
                        val data = ByteArray(readVarint(bb).toInt()).also { bb.get(it) }
                        into[off] = data
                    }
                    else -> throw IllegalStateException("Unexpected frame type $type in a client Initial")
                }
            }
        }

        class InitialKeys(val key: SecretKeySpec, val iv: ByteArray, val hp: SecretKeySpec)

        fun deriveInitialKeys(hmac: Mac, dcid: ByteArray, direction: String): InitialKeys {
            hmac.init(SecretKeySpec(INITIAL_SALT_V1, "HmacSHA256"))
            val initialSecret = hmac.doFinal(dcid)
            val secret = expandLabel(hmac, initialSecret, direction, 32)
            return InitialKeys(
                SecretKeySpec(expandLabel(hmac, secret, "quic key", 16), "AES"),
                expandLabel(hmac, secret, "quic iv", 12),
                SecretKeySpec(expandLabel(hmac, secret, "quic hp", 16), "AES")
            )
        }

        /** TLS 1.3 HKDF-Expand-Label with an empty context; every QUIC Initial output fits in one HMAC block. */
        private fun expandLabel(hmac: Mac, secret: ByteArray, label: String, length: Int): ByteArray {
            val fullLabel = "tls13 $label".toByteArray()
            val info = ByteArrayOutputStream()
            info.write(length shr 8)
            info.write(length and 0xff)
            info.write(fullLabel.size)
            info.write(fullLabel)
            info.write(0)
            info.write(1) // HKDF-Expand counter T(1)
            hmac.init(SecretKeySpec(secret, "HmacSHA256"))
            return hmac.doFinal(info.toByteArray()).copyOf(length)
        }

        private fun nonce(iv: ByteArray, pn: Long): ByteArray {
            val nonce = iv.copyOf()
            for (i in 0 until 8) {
                nonce[nonce.size - 1 - i] = (nonce[nonce.size - 1 - i].toInt() xor (pn shr (8 * i)).toInt()).toByte()
            }
            return nonce
        }

        fun readVarint(bb: ByteBuffer): Long {
            val first = bb.get().toInt() and 0xff
            val len = 1 shl (first shr 6)
            var v = (first and 0x3f).toLong()
            repeat(len - 1) { v = (v shl 8) or (bb.get().toLong() and 0xff) }
            return v
        }

        private fun writeVarint(out: ByteArrayOutputStream, v: Long) {
            when {
                v < 0x40 -> out.write(v.toInt())
                v < 0x4000 -> {
                    out.write(0x40 or (v shr 8).toInt())
                    out.write(v.toInt() and 0xff)
                }
                else -> {
                    out.write(0x80 or (v shr 24).toInt())
                    out.write((v shr 16).toInt() and 0xff)
                    out.write((v shr 8).toInt() and 0xff)
                    out.write(v.toInt() and 0xff)
                }
            }
        }
    }
}
