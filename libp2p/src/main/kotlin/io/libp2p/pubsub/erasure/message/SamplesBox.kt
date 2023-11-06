package io.libp2p.pubsub.erasure.message

import java.util.concurrent.CopyOnWriteArrayList

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

    fun addSamples(samples: Collection<ErasureSample>)
}


class SamplesBoxImpl(
    override val samples: MutableSet<ErasureSample> = mutableSetOf()
) : MutableSampleBox {

    override val observers: MutableList<SampleBoxObserver> = CopyOnWriteArrayList()

    override fun addSamples(addedSamples: Collection<ErasureSample>) {
        val newSamples = addedSamples.toSet() - samples
        if (newSamples.isNotEmpty()) {
            samples += newSamples
            observers.forEach {
                it.updated(this, newSamples)
            }
        }
    }
}

operator fun MutableSampleBox.plusAssign(sample: ErasureSample) = this.addSamples(setOf(sample))