package com.tinnitusrelief.ripple

import org.junit.Assert.assertTrue
import org.junit.Test

/** Fast subset of the G1 validation (full counts run in RippleReportMain). V13 runs everything at 44.1 kHz. */
class RippleValidationTest {
    private fun check(c: RippleValidation.Check) =
        assertTrue("${c.id} ${c.title}: " + c.details.joinToString("; ") { "${it.first}: ${it.second}" }, c.pass)

    private val v48 = RippleValidation(full = false, sampleRate = 48_000)
    private val v44 = RippleValidation(full = false, sampleRate = 44_100)

    @Test fun v01HarmonicContent() { check(v48.v1()); check(v44.v1()) }
    @Test fun v02FlatSpectrum() { check(v48.v2()) }
    @Test fun v03HearingCorrection() { check(v48.v3()) }
    @Test fun v04BandTable() { check(v48.v4()) }
    @Test fun v05v06aModulationDepthAndEquation() { v48.v5v6a().forEach(::check); v44.v5v6a().forEach(::check) }
    @Test fun v06bcRippleDensityAndCycle() { v48.v6bc().forEach(::check) }
    @Test fun v07NoModulationOutsideBand() { check(v48.v7()); check(v44.v7()) }
    @Test fun v08ActiveShamEquivalence() { check(v48.v8()) }
    @Test fun v09RampsAndJoins() { check(v48.v9()) }
    @Test fun v10v11PeaksAndLevel() { v48.v10v11().forEach(::check) }
    @Test fun v12Randomness() { check(v48.v12()) }
    /** G2: band choice for a 5.04 kHz pitch ([P] Table 1, nearest of the 17 match tones on a log scale). */
    @Test fun pitch5040HzUsesTheFourPointEightKilohertzRow() {
        val r = RippleBandTable.forPitch(5040.0)
        assertTrue(Math.abs(r.matchHz - 4756.83) < 0.01)
        assertTrue(r.active == RippleBand.B5 && r.activeAlt == RippleBand.B4 && r.sham == RippleBand.B3 && r.shamAlt == RippleBand.B2)
        // just above the 4.76/5.66 kHz midpoint (5187 Hz) the main bands are unchanged, only the alternatives drop out
        val up = RippleBandTable.forPitch(5200.0)
        assertTrue(up.active == RippleBand.B5 && up.activeAlt == null && up.sham == RippleBand.B3 && up.shamAlt == null)
    }

    @Test fun negativeControlsDetectFaults() { check(v48.negativeControls()) }
}
