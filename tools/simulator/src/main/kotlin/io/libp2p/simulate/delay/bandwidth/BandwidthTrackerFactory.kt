package io.libp2p.simulate.delay.bandwidth

import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.BandwidthDelayer
import java.util.concurrent.ScheduledExecutorService

fun interface BandwidthTrackerFactory {
    fun create(
        totalBandwidth: Bandwidth,
        executor: ScheduledExecutorService,
        timeSupplier: CurrentTimeSupplier,
        name: String,
    ): BandwidthDelayer

    companion object {
        fun fromLambda(ctor: (Bandwidth, ScheduledExecutorService, CurrentTimeSupplier, String) -> BandwidthDelayer): BandwidthTrackerFactory =
            BandwidthTrackerFactory { totalBandwidth, executor, timeSupplier, name ->
                ctor(totalBandwidth, executor, timeSupplier, name)
            }
    }
}