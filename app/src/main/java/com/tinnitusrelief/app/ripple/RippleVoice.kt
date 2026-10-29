package com.tinnitusrelief.app.ripple

import com.tinnitusrelief.app.audio.Voice
import com.tinnitusrelief.ripple.engine.OutputAuthorization
import com.tinnitusrelief.ripple.engine.OutputCalibration
import com.tinnitusrelief.ripple.engine.RippleEngine
import com.tinnitusrelief.ripple.engine.RippleSafetyLimits
import com.tinnitusrelief.ripple.engine.RippleSink
import com.tinnitusrelief.ripple.engine.StereoMix
import kotlin.math.pow

/**
 * The AUDIBLE path: plugs the ripple engine into the mixer's experimental layer (Layer B: −6 dB cap, limiter).
 * The engine refuses this audible sink unless audible playback is authorised, so in this build construction
 * always fails; [open] returns null. Gain = the calibration record's maximum gain; the user level (≤ −6 dB)
 * is applied inside the engine.
 */
class RippleVoice private constructor(sampleRate: Int, limits: RippleSafetyLimits, calibration: OutputCalibration) : Voice, RippleSink {
    override val audible = true
    private val calGain = 10.0.pow(calibration.maxGainDb / 20).toFloat()
    private var target: FloatArray? = null
    val engine = RippleEngine(sampleRate, limits, this) // throws IllegalArgumentException while not authorised

    override fun render(out: FloatArray, frames: Int) { target = out; engine.process(frames); target = null }
    override fun write(buffer: FloatArray, frames: Int) { target?.let { StereoMix.addMonoToStereo(buffer, frames, it, calGain) } }

    companion object {
        /** Returns null (and creates nothing) unless audible playback is authorised for this build. */
        fun open(sampleRate: Int, limits: RippleSafetyLimits, calibration: OutputCalibration?): RippleVoice? {
            if (!OutputAuthorization.AUDIBLE_PLAYBACK_AUTHORIZED || calibration == null) return null
            return runCatching { RippleVoice(sampleRate, limits, calibration) }.getOrNull()
        }
    }
}
