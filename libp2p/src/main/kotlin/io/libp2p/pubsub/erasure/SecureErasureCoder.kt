package io.libp2p.pubsub.erasure

interface SecureErasureCoder : ErasureCoder {

    override fun extend(msg: Message, extensionFactor: Double): SecureSampledMessage

    fun validateSample(header: SecureErasureHeader, sample: SecureErasureSample): Boolean

    fun validateHeader(header: SecureErasureHeader): Boolean
}

interface ErasureCommitment
interface ErasureSampleProof

interface SecureSampledMessage : SampledMessage{

    override val header: SecureErasureHeader

    override fun getSample(idx: SampleIndex): SecureErasureSample
}

interface SecureErasureHeader : ErasureHeader {
    val commitment: ErasureCommitment
}

interface SecureErasureSample : ErasureSample {
    val proof: ErasureSampleProof
}
