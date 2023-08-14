package io.libp2p.pubsub.erasure

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow
import java.util.BitSet

interface SampledMessage {

    val header: ErasureHeader

    val availableSamples: StateFlow<BitSet>

    fun getSample(idx: SampleIndex): ErasureSample
}

interface SampledMessageCollector {

    val recoveredMessage: Deferred<Message>

    fun addSample(sample: ErasureSample)
}