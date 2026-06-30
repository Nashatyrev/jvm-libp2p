package io.libp2p.quicsim.runner

enum class BandwidthQueueDiscipline {
    FIFO,
    FQ_CODEL,
    CODEL,
    FIFO_OUTBOUND_CODEL_INBOUND
}
