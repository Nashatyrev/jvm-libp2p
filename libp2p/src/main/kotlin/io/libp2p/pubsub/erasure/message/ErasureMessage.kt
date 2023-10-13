package io.libp2p.pubsub.erasure.message

import io.libp2p.pubsub.MessageId

sealed interface ErasureMessage {
    val messageId: MessageId
}