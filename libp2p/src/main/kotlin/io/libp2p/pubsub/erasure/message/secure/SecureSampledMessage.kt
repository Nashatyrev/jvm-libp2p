package io.libp2p.pubsub.erasure.message.secure

import io.libp2p.pubsub.erasure.SampleIndex
import io.libp2p.pubsub.erasure.message.SampledMessage

interface SecureSampledMessage : SampledMessage {

    override val header: SecureErasureHeader

//    override fun getSample(idx: SampleIndex): SecureErasureSample
}