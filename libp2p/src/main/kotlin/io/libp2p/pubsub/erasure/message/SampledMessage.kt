package io.libp2p.pubsub.erasure.message

interface SampledMessage {

    val header: ErasureHeader

    val sampleBox: ObservableSampleBox

    companion object {
        fun fromHeader(header: ErasureHeader): MutableSampledMessage = TODO()
    }
}

interface MutableSampledMessage : SampledMessage {
    override val sampleBox: MutableSampleBox

}

fun SampledMessage.isComplete() = this.sampleBox.samples.size >= this.header.recoverSampleCount