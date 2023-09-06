package io.libp2p.pubsub.erasure.message

import io.libp2p.pubsub.erasure.SampleIndex
import kotlinx.coroutines.flow.StateFlow
import java.util.BitSet

interface SampledMessage {

    val header: ErasureHeader

    val availableSamples: StateFlow<BitSet>

    fun getSample(idx: SampleIndex): ErasureSample
}

