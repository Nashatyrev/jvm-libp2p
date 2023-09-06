package io.libp2p.pubsub.erasure.message.secure

import io.libp2p.pubsub.erasure.ErasureCommitment
import io.libp2p.pubsub.erasure.message.ErasureHeader

interface SecureErasureHeader : ErasureHeader {
    val commitment: ErasureCommitment
}