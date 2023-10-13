package io.libp2p.pubsub.erasure.message

interface SampledMessage {

    val header: ErasureHeader

    val sampleBox: ObservableSampleBox

    companion object {
        fun fromHeader(header: ErasureHeader): SampledMessage = TODO()
    }
}

interface MutableSampledMessage : SampledMessage {
    override val sampleBox: MutableSampleBox

}

