package io.libp2p.pubsub.erasure

import io.libp2p.core.PeerId
import io.libp2p.pubsub.erasure.message.ErasureMessage
import java.util.concurrent.CompletableFuture

typealias SampleIndex = Int

interface ErasureCommitment
interface ErasureSampleProof

typealias ErasureSender = (PeerId, List<ErasureMessage>) -> CompletableFuture<Unit>