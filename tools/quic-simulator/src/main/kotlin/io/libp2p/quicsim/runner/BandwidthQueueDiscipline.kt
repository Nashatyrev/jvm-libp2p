package io.libp2p.quicsim.runner

enum class BandwidthQueueDiscipline {
    /** Simple FIFO bandwidth shaper on every directed link. Useful as a baseline with unbounded queues. */
    FIFO,

    /** Per-flow round-robin queue with CoDel-like drops per flow. Experimental approximation of fq_codel. */
    FQ_CODEL,

    /** Plain CoDel queue on every directed link, including endpoint egress and host ingress links. */
    CODEL,

    /** Shadow-like model: FIFO on endpoint outbound links and plain CoDel on router-to-host inbound links. */
    SHADOW_LIKE
}
