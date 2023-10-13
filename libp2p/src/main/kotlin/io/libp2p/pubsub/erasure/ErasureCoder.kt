package io.libp2p.pubsub.erasure

import io.libp2p.pubsub.erasure.message.MutableSampledMessage
import io.libp2p.pubsub.erasure.message.SampledMessage
import io.libp2p.pubsub.erasure.message.SourceMessage

interface ErasureCoder {

    fun extend(msg: SourceMessage): SampledMessage

    fun restore(samples: MutableSampledMessage): SourceMessage

}

