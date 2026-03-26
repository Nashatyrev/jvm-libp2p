package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.udpnetwork.UdpSimLink
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNode

data class BasicUdpSimNetwork(
    override val nodes: List<UdpSimNode>,
    override val links: List<UdpSimLink>
) : UdpSimNetwork
