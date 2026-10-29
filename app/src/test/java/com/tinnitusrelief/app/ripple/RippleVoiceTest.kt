package com.tinnitusrelief.app.ripple

import com.tinnitusrelief.ripple.engine.OutputAuthorization
import com.tinnitusrelief.ripple.engine.OutputCalibration
import com.tinnitusrelief.ripple.engine.RippleSafetyLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The audible path inside the app cannot be created in this build, with or without a calibration record. */
class RippleVoiceTest {
    @Test fun audibleVoiceCannotBeCreated() {
        assertFalse(OutputAuthorization.AUDIBLE_PLAYBACK_AUTHORIZED)
        val cal = OutputCalibration("Pixel", "Wired", "wired-headphones:X", 0, "lab", -12.0, 70.0)
        assertEquals(null, RippleVoice.open(48_000, RippleSafetyLimits(), cal))
        assertEquals(null, RippleVoice.open(48_000, RippleSafetyLimits(), null))
    }
}
