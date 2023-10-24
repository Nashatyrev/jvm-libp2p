package io.libp2p.etc.types

import com.google.protobuf.ByteString

/**
 * `ByteArray` wrapper with  `equals()`, `hashCode()` and `toString()`
 */
class WBytes(val array: ByteArray) {

    operator fun plus(other: WBytes) = (array + other.array).toWBytes()
    operator fun plus(other: ByteArray) = (array + other).toWBytes()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as WBytes
        return array.contentEquals(other.array)
    }

    override fun hashCode(): Int {
        return array.contentHashCode()
    }

    override fun toString() = array.toHex()
}

fun ByteArray.toWBytes() = WBytes(this)
fun String.toWBytes() = this.fromHex().toWBytes()
fun WBytes.toProtobuf() = this.array.toProtobuf()
fun ByteString.toWBytes() = this.toByteArray().toWBytes()

fun WBytes.slice(start: Int, len: Int) = WBytes(this.array.sliceArray(start until start + len))
fun WBytes.chunked(maxSize: Int) =
    this.array.indices.chunked(maxSize).map { this.slice(it.first(), it.size) }

val WBytes.size get() = this.array.size
fun WBytes.repeat(count: Int): WBytes = WBytes(ByteArray(this.size * count) { this.array[it % this.size] })