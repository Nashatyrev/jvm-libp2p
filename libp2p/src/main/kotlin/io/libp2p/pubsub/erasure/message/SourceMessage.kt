package io.libp2p.pubsub.erasure.message

import io.libp2p.etc.types.WBytes
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.Topic

data class SourceMessage(
    val topic: Topic,
    val messageId: MessageId,
    val blob: WBytes
)

