package io.libp2p.core.multiformats

import io.libp2p.core.multiformats.Protocol.*

class MultiaddrEncapsulated {

    companion object {
        private val P2P_PROTOCOLS = Protocol.PEER_ID_PROTOCOLS.toSet()
        private val IP_RESOLVED_PROTOCOLS = setOf(IP4, IP6, DNS, DNS4, DNS6)
        private val TRANSPORT_PROTOCOLS = setOf(TCP, QUIC, QUICV1, WS, WSS)

        private fun rootProtocols(subprotocols: Set<Protocol>) = emptySet<Protocol?>().canEncapsulate(subprotocols)
        private infix fun Set<Protocol?>.canEncapsulate(subprotocols: Set<Protocol>) = this to subprotocols

        private val ENCAPSULATION_MAP = listOf(
            rootProtocols(
                IP_RESOLVED_PROTOCOLS +
                        P2PCIRCUIT +
                        P2P_PROTOCOLS +
                        DNSADDR +
                        UNIX
            ),
            IP_RESOLVED_PROTOCOLS
                    canEncapsulate setOf(TCP, UDP),
            setOf(UDP)
                    canEncapsulate setOf(QUIC, QUICV1),
            setOf(TCP)
                    canEncapsulate setOf(WS, WSS),
            TRANSPORT_PROTOCOLS + P2PCIRCUIT
                    canEncapsulate P2P_PROTOCOLS,
            P2P_PROTOCOLS
                    canEncapsulate setOf(P2PCIRCUIT)
        )
    }
}