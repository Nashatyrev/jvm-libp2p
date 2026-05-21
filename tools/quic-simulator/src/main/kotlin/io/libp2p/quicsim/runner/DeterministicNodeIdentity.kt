package io.libp2p.quicsim.runner

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.crypto.keys.unmarshalEd25519PrivateKey
import java.security.MessageDigest

object DeterministicNodeIdentity {
    fun privateKey(nodeId: Int): PrivKey {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("quic-sim-node-$nodeId".toByteArray(Charsets.UTF_8))
        return unmarshalEd25519PrivateKey(digest)
    }

    fun peerId(nodeId: Int): PeerId =
        PeerId.fromPubKey(privateKey(nodeId).publicKey())
}
