package io.libp2p.pubsub.erasure

import io.libp2p.etc.types.WBytes
import io.libp2p.pubsub.erasure.message.ErasureHeader
import io.libp2p.pubsub.erasure.message.ErasureSample

interface ErasureSerializer {
    fun encodeHeader(header: ErasureHeader): WBytes
    fun decodeHeader(bytes: WBytes): ErasureHeader
    fun encodeSample(sample: ErasureSample): WBytes
    fun decodeSample(bytes: WBytes): ErasureSample
}