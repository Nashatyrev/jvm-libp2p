package io.libp2p.pubsub.erasure.message.secure

import io.libp2p.pubsub.erasure.ErasureSampleProof
import io.libp2p.pubsub.erasure.message.ErasureSample

interface SecureErasureSample {
    val proof: ErasureSampleProof
}