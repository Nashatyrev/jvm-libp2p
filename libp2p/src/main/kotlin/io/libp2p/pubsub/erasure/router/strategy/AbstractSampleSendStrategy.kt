package io.libp2p.pubsub.erasure.router.strategy

abstract class AbstractSampleSendStrategy(
    protected var samplesToSend: Int
) : SampleSendStrategy {

    override fun hasToSend(): Boolean = samplesToSend > 0
    override fun onSent() {
        require(hasToSend())
        samplesToSend--
    }

    protected fun addSamples(count: Int) { samplesToSend += count }
}