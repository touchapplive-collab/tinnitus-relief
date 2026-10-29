package com.tinnitusrelief.ripple

// =====================================================================================================
//  EXPERIMENTAL spectral-ripple (cross-frequency decorrelation) stimulus — Gate G1 (signal only).
//
//  Implemented independently from the published article (CC BY 4.0):
//    [P] Yukhnovich E.A., Harrison S., Wray N., Alter K., Sedley W. Hearing Research 2025, 109335.
//        doi:10.1016/j.heares.2025.109335
//  and from docs/PHASE1-RIPPLE-SPEC.md. No code from the non-commercially licensed data repository is used.
//
//  Nothing in the app references this package: it cannot produce sound. Playback is a later gate (G3).
// =====================================================================================================

/** Stimulus constants. Every value cites its source; ENGINEERING marks values that are not from [P]. */
object RippleSpec {
    /** [P] Methods: non-overlapping 4 s harmonic complexes, concatenated to hour-long tracks. */
    const val SEGMENT_SECONDS = 4.0
    const val SEGMENTS_PER_HOUR = 900

    /** [P] Methods: 1 s raised-cosine onset and offset ramps. */
    const val RAMP_SECONDS = 1.0

    /** [P] Methods: randomised fundamental, 96–256 Hz. */
    const val F0_MIN_HZ = 96.0
    const val F0_MAX_HZ = 256.0

    /** [P] eq. 1: harmonics falling within 1–16 kHz. */
    const val HARMONIC_LOW_HZ = 1000.0
    const val HARMONIC_HIGH_HZ = 16000.0

    /** [P] eq. 2: modulation depth d. */
    const val DEPTH = 1.0
    /** [P] eq. 2: temporal modulation rate ω (Hz). */
    const val TEMPORAL_RATE_HZ = 1.0
    /** [P] eq. 5: mean spectral modulation rate μ (cycles/octave). */
    const val SMR_MEAN = 4.5
    /** [P] eq. 5: extent of variability r (cycles/octave). */
    const val SMR_EXTENT = 3.0
    /** [P] eq. 5: rate of change ν (Hz) — one full spectral-rate cycle every 8 s. */
    const val SMR_RATE_HZ = 0.125

    /** ENGINEERING (spec S1): every segment is normalised to this RMS. Signal integrity only — not a safety level. */
    const val TARGET_RMS_DBFS = -30.0
}
