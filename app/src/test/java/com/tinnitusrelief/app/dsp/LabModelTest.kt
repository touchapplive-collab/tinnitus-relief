package com.tinnitusrelief.app.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabModelTest {

    @Test fun labSettingsRoundTripAndClampHostileValues() {
        val s = LabSettings(mode = AcousticMode.CUSTOM_FILTER, sourceId = "file:My song (2).mp3", volume = 0.2f, minutes = 20, mixWithMasker = true, customGainDb = 6.0)
        assertEquals(s, LabSettings.decode(s.encode()))
        val hostile = LabSettings.decode("mode=NOPE;vol=9;min=999;gain=40;cg=80;fmc=99999")
        assertEquals(AcousticMode.DFCRS_INSPIRED, hostile.mode)
        assertEquals(1f, hostile.volume, 0f)
        assertEquals(SessionLimits.MAX_MINUTES, hostile.minutes)
        assertEquals(10.0, hostile.bandGainDb, 0.0)
        assertEquals(10.0, hostile.customGainDb, 0.0)
        assertEquals(600.0, hostile.fmCents, 0.0)
        assertEquals(LabSettings(), LabSettings.decode(null))
    }

    @Test fun experimentalDefaultsAreConservative() {
        val d = LabSettings()
        assertEquals(SessionLimits.DEFAULT_MINUTES, d.minutes)
        assertTrue(d.volume < 0.5f)
        assertEquals(10.0, d.bandGainDb, 0.0) // the published value, never higher
    }

    @Test fun everyModeProducesTheDocumentedBands() {
        val p = 4000.0
        assertEquals(PublishedBands.dfcrs(p), LabSettings(mode = AcousticMode.DFCRS_INSPIRED).bands(p))
        assertEquals(listOf(PublishedBands.okamotoNotch(p)), LabSettings(mode = AcousticMode.NOTCHED).bands(p))
        assertEquals(PublishedBands.hfem(), LabSettings(mode = AcousticMode.HFEM).bands(p))
        val c = LabSettings(mode = AcousticMode.CUSTOM_FILTER, customOffsetOctaves = 1.0, customWidthOctaves = 0.5, customGainDb = -12.0).bands(p).single()
        assertEquals(8000.0, c.centerHz, 1e-6); assertEquals(0.5, c.widthOctaves, 1e-9); assertEquals(-12.0, c.gainDb, 0.0)
        assertTrue(LabSettings(mode = AcousticMode.AM_TONE).bands(p).isEmpty())
    }

    @Test fun mixAndPresetsRoundTripAndKeepAtMostThreeSounds() {
        val mix = listOf(MixEntry("builtin:shower", 0.7f), MixEntry("noise:pink", 0.3f), MixEntry("file:a=b.mp3", 0.5f))
        assertEquals(mix, MixCodec.decode(MixCodec.encode(mix)))
        assertEquals(3, MixCodec.decode(MixCodec.encode(mix + MixEntry("builtin:fan", 1f))).size)
        val presets = listOf(Preset("Night | sleep", mix), Preset("Focus", mix.take(1)))
        val back = MixCodec.decodePresets(MixCodec.encodePresets(presets))
        assertEquals("Night / sleep", back[0].name)
        assertEquals(mix, back[0].mix)
        assertEquals(2, back.size)
        assertTrue(MixCodec.decode("garbage;;=;x=").isEmpty())
    }
}
