package io.libp2p.quicsim.network.impl

import io.libp2p.quicsim.network.SimLink
import io.libp2p.quicsim.network.SimNetwork
import io.libp2p.quicsim.network.SimNode

data class BasicSimNetwork(
    override val nodes: List<SimNode>,
    override val links: List<SimLink>
) : SimNetwork
