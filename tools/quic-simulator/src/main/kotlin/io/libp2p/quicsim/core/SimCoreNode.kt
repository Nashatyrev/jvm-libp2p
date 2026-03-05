package io.libp2p.quicsim.core

import io.libp2p.quicsim.core.schedule.Controllable

interface SimCoreNode : PacketProcessor, Controllable {
}