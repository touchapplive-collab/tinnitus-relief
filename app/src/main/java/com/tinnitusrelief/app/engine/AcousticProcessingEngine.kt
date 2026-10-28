package com.tinnitusrelief.app.engine

import com.tinnitusrelief.app.audio.Voice
import com.tinnitusrelief.app.data.SoundLibrary
import com.tinnitusrelief.app.dsp.AcousticMode
import com.tinnitusrelief.app.dsp.BandNoiseVoice
import com.tinnitusrelief.app.dsp.LabSettings
import com.tinnitusrelief.app.dsp.PublishedBands
import com.tinnitusrelief.app.dsp.ShapedVoice
import com.tinnitusrelief.app.dsp.SpectralShaper
import com.tinnitusrelief.app.dsp.ToneVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds Layer B voices for the Acoustic Lab ("Neural Acoustic Engine" in the UI).
 *
 * Modular: every mode is either
 *  - a SOURCE processed by the zero-phase [SpectralShaper] with a list of bands
 *    (DFCRS-inspired, notched, HFEM, custom filter), or
 *  - a GENERATOR (narrow-band noise, pure / AM / FM tone).
 * New research algorithms plug in by adding a mode that returns bands or a Voice.
 *
 * Live parameter changes (bands, modulation) are applied to the running voice without rebuilding it.
 */
class AcousticProcessingEngine(private val library: SoundLibrary, private val sampleRate: Int) {

    private var shaper: SpectralShaper? = null
    private var tone: ToneVoice? = null
    private var noise: BandNoiseVoice? = null
    private var builtFor: Pair<AcousticMode, String>? = null

    /** Generator level: −20 dBFS peak for tones, about −20 dBFS RMS for noise; Layer B gain applies on top. */
    private val generatorAmp = 0.1f

    suspend fun build(s: LabSettings, pitchHz: Double): Voice = withContext(Dispatchers.Default) {
        shaper = null; tone = null; noise = null
        val voice: Voice = when (s.mode) {
            AcousticMode.DFCRS_INSPIRED, AcousticMode.NOTCHED, AcousticMode.HFEM, AcousticMode.CUSTOM_FILTER -> {
                val sound = library.find(s.sourceId) ?: library.find(SoundLibrary.PINK)!!
                val src = library.createVoice(sound, sampleRate)
                val sh = SpectralShaper(sampleRate)
                sh.setBands(s.bands(pitchHz), s.transitionOctaves)
                shaper = sh
                ShapedVoice(src, sh)
            }
            AcousticMode.NARROWBAND_NOISE -> {
                val b = PublishedBands.mostNarrowband(pitchHz)
                BandNoiseVoice(sampleRate, b.centerHz, b.widthOctaves, rms = 0.1f).also { noise = it }
            }
            AcousticMode.PURE_TONE, AcousticMode.AM_TONE, AcousticMode.FM_TONE ->
                ToneVoice(sampleRate, pitchHz, generatorAmp).also { tone = it; applyModulation(it, s) }
        }
        builtFor = s.mode to s.sourceId
        voice
    }

    /** True when [s] can be applied to the running voice without rebuilding it. */
    fun canUpdateLive(s: LabSettings): Boolean = builtFor == (s.mode to s.sourceId)

    fun updateLive(s: LabSettings, pitchHz: Double) {
        shaper?.setBands(s.bands(pitchHz), s.transitionOctaves)
        noise?.setCenter(pitchHz)
        tone?.let { it.targetFreq = pitchHz; applyModulation(it, s) }
    }

    fun clear() { shaper = null; tone = null; noise = null; builtFor = null }

    private fun applyModulation(t: ToneVoice, s: LabSettings) {
        when (s.mode) {
            AcousticMode.AM_TONE -> { t.fmCents = 0.0; t.amRate = s.amRateHz; t.amDepth = s.amDepth }
            AcousticMode.FM_TONE -> { t.amDepth = 0f; t.fmRate = s.fmRateHz; t.fmCents = s.fmCents }
            else -> { t.amDepth = 0f; t.fmCents = 0.0 }
        }
    }
}
