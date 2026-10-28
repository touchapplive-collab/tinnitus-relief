package com.tinnitusrelief.app.audio

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Decoded interleaved stereo 16-bit PCM. */
class Pcm(val data: ShortArray, val sampleRate: Int) {
    val frames: Int get() = data.size / 2
}

/** Pure loop preparation (no Android dependencies, unit-tested). */
object Seamless {
    /**
     * Makes a buffer loop without a click or audible seam: the last [crossfadeSeconds] are blended
     * into the start with an equal-power curve and then dropped, so the end flows into the beginning.
     * Also normalises loudness so imported files land at a consistent, conservative level.
     */
    fun makeSeamless(pcm: Pcm, crossfadeSeconds: Float = 1.5f, targetRmsDb: Float = -20f): Pcm {
        val frames = pcm.frames
        val x = min((pcm.sampleRate * crossfadeSeconds).toInt(), frames / 4)
        val src = pcm.data
        val outFrames = frames - x
        val out = ShortArray(outFrames * 2)
        // RMS over the whole file for gain normalisation (never boosts more than +12 dB)
        var sum = 0.0
        for (s in src) sum += s.toDouble() * s
        val rms = sqrt(sum / src.size.coerceAtLeast(1)) / 32768.0
        val target = Math.pow(10.0, targetRmsDb / 20.0)
        var gain = if (rms > 1e-6) (target / rms) else 1.0
        gain = gain.coerceIn(0.05, 4.0)
        for (i in 0 until outFrames) {
            for (c in 0..1) {
                var v = src[2 * i + c].toDouble()
                if (i < x) {
                    val t = i.toDouble() / x
                    val tail = src[2 * (frames - x + i) + c].toDouble()
                    v = v * sin(t * Math.PI / 2) + tail * cos(t * Math.PI / 2)
                }
                v *= gain
                out[2 * i + c] = v.coerceIn(-32767.0, 32767.0).toInt().toShort()
            }
        }
        return Pcm(out, pcm.sampleRate)
    }

}
