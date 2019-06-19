package io.libp2p.core

import io.libp2p.core.protocol.ProtocolMatcher
import io.libp2p.core.security.SecureChannel
import io.netty.channel.socket.SocketChannel

/**
 * The Host is the libp2p entrypoint.
 */
class Host private constructor (var id: PeerId?, var secureChannels: Map<ProtocolMatcher, SecureChannel<SocketChannel>>) {

    fun peer(id: PeerId): Peer = TODO()

    companion object {
        /**
         * Starts a fluent builder to construct a new Host.
         */
        fun create(fn: Builder.() -> Unit) = Builder().apply(fn).build()
    }

    class Builder {
        private var id: PeerId? = null
        private var secureChannels = mutableMapOf<ProtocolMatcher, SecureChannel<SocketChannel>>()

        /**
         * Sets an identity for this host. If unset, libp2p will default to a random identity.
         */
        fun id(fn: (Map<ProtocolMatcher, SecureChannel<SocketChannel>>).() -> Builder): Builder = apply { fn(secureChannels) }

        /**
         * Manipulates the security channels for this host.
         */
        fun secureChannels(fn: (MutableMap<ProtocolMatcher, SecureChannel<SocketChannel>>).() -> Unit): Builder = apply { fn(secureChannels) }

        /**
         * Constructs the Host with the provided parameters.
         */
        fun build(): Host {
            // TODO: validate parameters.

            return Host(id, secureChannels)
        }
    }
}