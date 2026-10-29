package com.tinnitusrelief.ripple.engine

/** Adds a mono signal to an interleaved stereo buffer (both ears receive the same signal) with a gain. */
object StereoMix {
    fun addMonoToStereo(mono: FloatArray, frames: Int, stereo: FloatArray, gain: Float) {
        var j = 0
        for (i in 0 until frames) {
            val v = mono[i] * gain
            stereo[j] += v; stereo[j + 1] += v
            j += 2
        }
    }
}
