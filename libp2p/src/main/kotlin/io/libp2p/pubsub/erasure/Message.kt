package io.libp2p.pubsub.erasure

import io.libp2p.etc.types.WBytes
import io.libp2p.pubsub.Topic

interface MessageId

interface Message {
    val topic: Topic
    val messageId: MessageId
    val blob: WBytes
}

