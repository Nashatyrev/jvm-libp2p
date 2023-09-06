package io.libp2p.pubsub.erasure

import io.libp2p.pubsub.erasure.message.ErasureSample
import io.libp2p.pubsub.erasure.message.SourceMessage
import kotlinx.coroutines.Deferred

interface SampledMessageCollector {

    val recoveredMessage: Deferred<SourceMessage>

    fun addSample(sample: ErasureSample)
}