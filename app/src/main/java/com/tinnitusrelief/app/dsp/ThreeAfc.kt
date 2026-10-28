package com.tinnitusrelief.app.dsp

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Three-alternative forced-choice (3AFC) tinnitus PITCH matching.
 *
 * Published rules (Tang, Wang & Sun 2025, Sound & Vibration 59(1), doi:10.59400/sv1928):
 *  - three pure tones Fa < Fb < Fc, 500 ms each, 1 s apart, 125–16 000 Hz, initially one octave apart
 *    (worked example 2000 / 4000 / 8000 Hz);
 *  - the listener picks the tone closest to the tinnitus;
 *      Fa → new upper limit = Fb lowered by 1/3 octave
 *      Fb → new range = [Fa raised by 1/3 octave, Fc lowered by 1/3 octave]
 *      Fc → new lower limit = Fb raised by 1/3 octave
 *  - repeat until the range spans at most two octaves, then test three tones 1/3 octave apart;
 *  - the result is confirmed with an octave-confusion test (procedure not published).
 *
 * ENGINEERING ASSUMPTIONS (not in the paper), each marked below:
 *  [A1] each new coarse triple is centred geometrically in the remaining range, one octave apart;
 *  [A2] fine stage: picking an outer tone re-centres the 1/3-octave triple on it; at most 4 fine rounds;
 *  [A3] octave check = one more 3AFC trial with f/2, f, 2f (skipped when out of range);
 *  [A4] after the octave check the user may fine-tune in 1/24-octave steps (separate UI step).
 */
class ThreeAfcPitch(val minHz: Double = 125.0, maxHz: Double = 16000.0) {

    enum class Phase { COARSE, FINE, OCTAVE_CHECK, DONE }

    val maxHz: Double = max(minHz * 8, maxHz)

    var phase = Phase.COARSE
        private set
    var low = minHz
        private set
    var high = this.maxHz
        private set
    /** The three tones to present now, ascending. */
    var triple: DoubleArray = doubleArrayOf(2000.0, 4000.0, 8000.0).map { it.coerceIn(minHz, this.maxHz) }.toDoubleArray()
        private set
    var result: Double? = null
        private set
    var trials = 0
        private set
    private var fineRounds = 0

    fun choose(index: Int) {
        require(index in 0..2)
        trials++
        val (fa, fb, fc) = Triple(triple[0], triple[1], triple[2])
        when (phase) {
            Phase.COARSE -> {
                when (index) {
                    0 -> high = third(fb, -1)
                    1 -> { low = third(fa, +1); high = third(fc, -1) }
                    2 -> low = third(fb, +1)
                }
                if (high < low) { val m = sqrt(low * high); low = m; high = m }
                if (high / low <= 4.0 + 1e-9) {
                    phase = Phase.FINE
                    triple = fineTriple(sqrt(low * high))
                } else {
                    val c = sqrt(low * high) // [A1]
                    triple = doubleArrayOf(c / 2, c, c * 2).map { it.coerceIn(minHz, maxHz) }.toDoubleArray()
                }
            }
            Phase.FINE -> {
                val chosen = triple[index]
                fineRounds++
                val atEdge = chosen <= minHz * 1.0001 || chosen >= maxHz * 0.9999
                if (index == 1 || fineRounds >= 4 || atEdge) startOctaveCheck(chosen) // [A2]
                else triple = fineTriple(chosen)
            }
            Phase.OCTAVE_CHECK -> {
                result = triple[index]
                phase = Phase.DONE
            }
            Phase.DONE -> Unit
        }
    }

    private fun startOctaveCheck(f: Double) { // [A3]
        if (f / 2 < minHz || f * 2 > maxHz) {
            result = f
            phase = Phase.DONE
        } else {
            triple = doubleArrayOf(f / 2, f, f * 2)
            phase = Phase.OCTAVE_CHECK
        }
    }

    private fun fineTriple(c: Double): DoubleArray {
        val cc = c.coerceIn(minHz, maxHz)
        return doubleArrayOf(third(cc, -1), cc, third(cc, +1)).map { it.coerceIn(minHz, maxHz) }.toDoubleArray()
    }

    private fun third(f: Double, dir: Int) = f * 2.0.pow(dir / 3.0)

    companion object {
        /** [A4] fine-tuning step. */
        const val FINE_TUNE_OCTAVES = 1.0 / 24.0
        const val TONE_MS = 500
        const val GAP_MS = 1000
    }
}

/**
 * 3AFC LOUDNESS matching (same paper): three tones at the tinnitus pitch at levels L−D, L, L+D;
 * D starts at 5 dB; the chosen tone becomes the new middle and D shrinks by 1 dB until D = 1 dB.
 * The result is reported as sensation level = matched level − hearing threshold at the pitch.
 *
 * LIMITATION: a phone cannot measure hearing threshold in dB HL. The threshold here is the quietest level
 * the user can hear on their own headphones (uncalibrated), so the result is a relative, personal value.
 */
class ThreeAfcLoudness(val thresholdDbFs: Double, startAboveThresholdDb: Double = 10.0, val maxDbFs: Double = -12.0) {
    var middle = min(thresholdDbFs + startAboveThresholdDb, maxDbFs - 5.0)
        private set
    var step = 5.0
        private set
    var done = false
        private set

    val levels: DoubleArray get() = doubleArrayOf(middle - step, middle, min(middle + step, maxDbFs))

    fun choose(index: Int) {
        require(index in 0..2)
        if (done) return
        middle = levels[index]
        if (step <= 1.0) done = true else step -= 1.0
    }

    /** Matched loudness relative to the user's own threshold (dB, uncalibrated). */
    val sensationLevelDb: Double get() = middle - thresholdDbFs
}
