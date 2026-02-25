package io.libp2p.quicsim.bandwidth

/**
 * Delivery decision returned by [BandwidthPolicy] for a queued datagram.
 */
enum class BandwidthDecision {
    /** Datagram may be delivered now and required bandwidth budget is consumed. */
    ALLOW,

    /** Datagram must stay queued and be retried later. */
    HOLD,

    /** Datagram must be dropped immediately. */
    DROP
}
