package io.libp2p.pubsub.erasure

import io.libp2p.pubsub.erasure.message.secure.SecureErasureHeader
import io.libp2p.pubsub.erasure.message.secure.SecureErasureSample
import io.libp2p.pubsub.erasure.message.secure.SecureSampledMessage
import io.libp2p.pubsub.erasure.message.SourceMessage

interface SecureErasureCoder : ErasureCoder {

    override fun extend(msg: SourceMessage, extensionFactor: Double): SecureSampledMessage

    fun validateSample(header: SecureErasureHeader, sample: SecureErasureSample): Boolean

    fun validateHeader(header: SecureErasureHeader): Boolean
}


