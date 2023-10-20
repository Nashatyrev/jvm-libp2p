package io.libp2p.pubsub.erasure.message

import io.libp2p.etc.types.WBytes
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.erasure.SampleIndex

class ErasureSample(
    override val messageId: MessageId,
    val sampleIndex: SampleIndex,
    val data: WBytes
) : ErasureMessage