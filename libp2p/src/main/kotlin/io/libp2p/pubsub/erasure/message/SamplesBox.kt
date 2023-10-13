package io.libp2p.pubsub.erasure.message

interface SamplesBox {

    val samples: Set<ErasureSample>
}

fun interface SampleBoxObserver {

    fun updated(samplesBox: SamplesBox, newSamples: Set<ErasureSample>)
}

interface ObservableSampleBox : SamplesBox {

    val observers: MutableList<SampleBoxObserver>
}

interface MutableSampleBox : ObservableSampleBox {

    fun addSamples(samples: Set<ErasureSample>)
}


class SamplesBoxImpl : MutableSampleBox {

    override val observers: MutableList<SampleBoxObserver> = mutableListOf()

    override val samples: MutableSet<ErasureSample> = mutableSetOf()

    override fun addSamples(addedSamples: Set<ErasureSample>) {
        val newSamples = addedSamples - samples
        if (samples.isNotEmpty()) {
            samples += newSamples
            observers.forEach {
                it.updated(this, newSamples)
            }
        }
    }
}