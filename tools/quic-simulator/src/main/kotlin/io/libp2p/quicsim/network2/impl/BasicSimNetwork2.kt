package io.libp2p.quicsim.network2.impl

import io.libp2p.quicsim.network2.SimLink2
import io.libp2p.quicsim.network2.SimNetwork2
import io.libp2p.quicsim.network2.SimNode

data class BasicSimNetwork2(
    override val nodes: List<SimNode>,
    override val links: List<SimLink2>
) : SimNetwork2
