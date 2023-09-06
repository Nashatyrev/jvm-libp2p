package io.libp2p.pubsub.erasure

import io.libp2p.pubsub.erasure.message.SampledMessage
import io.libp2p.pubsub.erasure.message.SourceMessage

interface ErasureCoder {

    val erasureSerializer: ErasureSerializer

    fun extend(msg: SourceMessage, extensionFactor: Double): SampledMessage

    fun restore(samples: SampledMessage): SourceMessage

}

