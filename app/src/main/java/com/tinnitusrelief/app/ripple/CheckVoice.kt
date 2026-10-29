package com.tinnitusrelief.app.ripple

import com.tinnitusrelief.app.audio.Voice
import com.tinnitusrelief.ripple.engine.OutputAuthorization
import com.tinnitusrelief.ripple.engine.OutputCalibration
import com.tinnitusrelief.ripple.therapy.CheckTone
import com.tinnitusrelief.ripple.therapy.ToneSequenceRenderer
import kotlin.math.pow

/**
 * AUDIBLE path for the protocol's listening checks (hearing-profile comparison, band audibility): plays a
 * pre-rendered tone sequence once through the experimental layer. Like [RippleVoice] it can only be created
 * when audible playback is authorised and a calibration record exists, so in this build [open] returns null.
 */
class CheckVoice private constructor(private val samples: FloatArray, calibration: OutputCalibration) : Voice {
    private val gain = 10.0.pow(calibration.maxGainDb / 20).toFloat()
    private var pos = 0
    val durationMs: Long get() = samples.size * 1000L / RATE_HINT
    @Volatile var finished = false; private set

    override fun render(out: FloatArray, frames: Int) {
        val n = minOf(frames, samples.size - pos)
        var j = 0
        for (i in 0 until n) { val v = samples[pos + i] * gain; out[j] += v; out[j + 1] += v; j += 2 }
        pos += maxOf(0, n)
        if (pos >= samples.size) finished = true
    }

    companion object {
        private var RATE_HINT = 48_000

        fun open(tones: List<CheckTone>, sampleRate: Int, toneMs: Int, gapMs: Int, calibration: OutputCalibration?): CheckVoice? {
            if (!OutputAuthorization.AUDIBLE_PLAYBACK_AUTHORIZED || calibration == null) return null
            RATE_HINT = sampleRate
            return CheckVoice(ToneSequenceRenderer.render(tones, sampleRate, toneMs, gapMs), calibration)
        }
    }
}
