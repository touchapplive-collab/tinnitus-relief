# Gate G3 — silent engine and safety interlocks (29 Oct 2026)

Scope approved: engineering and non-listening validation only. **No audible playback exists, and none was produced.**

## 1. Changes

- **Hearing correction (decision T1):** full maximum above 8 kHz, as [P] states and the trial code does. Exactly at 8 kHz the value is 8/9. All signal validations were re-run: V3 now measures 15.00 / 45.00 dB at 8.06 kHz for M = 15 / 45. The independent article-only implementation still matches all 8 cases (−118 to −152 dB).
- **Isolation:** the generator moved out of the app into the pure-Kotlin module `:ripple`. `:app` does not depend on it. The app's files changed since G1 only by removal of `dsp/ripple`.
- **New engine** (`:ripple/engine`): gate, safety rules, ledger, engine, real-time pacer.
- **New silent bench app** (`:ripplebench`): separate APK with no audio-output code. Published as the pre-release "ripplebench", not as the app's release.

## 2. Why audible output is impossible in G3

There are five independent layers.

1. The production app has no dependency on `:ripple`. CI checks the source, the build file and the resolved runtime classpath.
2. No audio-output or input API exists in `:ripple` or `:ripplebench`. CI greps for AudioTrack, AAudio, MediaPlayer, SoundPool, ExoPlayer/Media3, OpenSL, Oboe, AudioRecord, ToneGenerator, Ringtone and JetPlayer. The bench only reads AudioManager properties.
3. `OutputAuthorization.AUDIBLE_PLAYBACK_AUTHORIZED = false`. The engine constructor throws for an audible sink.
4. The gate blocks any audible request (AUDIBLE_PLAYBACK_NOT_AUTHORIZED, NO_CALIBRATION), even when a calibration record is supplied.
5. Only silent sinks exist: NullSink and CaptureSink.

## 3. Safety interlocks implemented (spec section 4)

| Rule | Implementation | Test |
|---|---|---|
| S1 fixed digital level | generator −30 dBFS RMS; sample guard at −6 dBFS never reached (0 clamps in 60 min at maximum level) | V10, `sixtyMinuteSessionRunsToTheLimit` |
| S2 level cap | level ≤ −6 dB relative to the calibrated cap | `levelNeverRises…` |
| S3 start silent and fade in; no automatic increase | 5 s raised-cosine fade-in from 0, exact to 1e-7. First session −40 dB; later sessions start at most at the last confirmed level. Automatic raises are refused, lowering is allowed, user raises are capped | `fadeIn…`, `levelNeverRises…` |
| S4 measured ceiling | gate requires a calibration record matching the current route with LAeq ≤ 75 dB(A) | `calibrationMustMatchRouteAndLimit` |
| S5 volume advisory | not applicable in G3 (no output) | G4 |
| S6 dose | ≤ 60 min per session, ≥ 60 min break [P], ≤ 120 min in a rolling 24 h. The session ends exactly at its allowed time with a 3 s fade | `breakDailyCapAndResume`, `sessionEndsAtTheLimit…` |
| S7 stops | any interruption (focus loss or duck, headphones unplugged, route change, call, service stop, thermal) fades out in 150 ms and stops. Never resumes automatically. Manual resume only within 10 min, inside the same 60 min | `everyInterruption…`, `focusChanges…` |
| S7 high corrections | 30 and 45 dB profiles locked (HEARING_PROFILE_LOCKED); 0 and 15 dB allowed | `hearingProfiles30And45AreLocked` |
| S8 screening | 7 questions; any "yes" to an exclusion excludes; unanswered blocks | `screeningExcludesOrBlocks` |
| S9 pitch and band | no pitch or LOW confidence blocks; the A1 audibility answer is required. If A1 is inaudible, A2/C2 apply; with no A2 the session is blocked | `pitchAndAudibilityGates` |
| S9 stop rules | post-session report required. Rise ≥ 2 points → lock for ≥ 24 h plus acknowledgement. Any symptom (fullness, pain, distortion, louder or changed tinnitus, dizziness, hearing decrease) → lock until a clinician clears it | `stopRulesLockAndUnlock` |
| S10 logging | persistent ledger: sessions, end reason, interruption, band, arm, profile, level, reports, lock (text codec) | `ledgerRoundTrip` |

## 4. Runtime validation (no audio device anywhere)

- **Buffer handling:** segments are rendered ahead on a background thread; the output thread only copies.
  - Before the first segment is ready, the engine outputs silence without starting the session clock.
  - A starved renderer causes only silent gaps at segment boundaries, which are at zero amplitude, so there is no click. Tested.
  - The render thread stops cleanly.
- **Real-time pacing:** a driver models a device buffer drained at the sample rate and counts dropouts.
  - JVM: 0 glitches; load 0.05–0.15%.
  - Emulator (Android 14, x86_64, started with `-no-audio`, native 48 kHz, burst 1088):
    - 0 glitches in 30 s + 60 s;
    - 0 segment underruns;
    - worst segment render 643 ms per 4 s (16% of real time);
    - output peak 0.082 at maximum level.
- **Sample rates:** 44.1, 48 and 96 kHz; spectrum stays inside 1–16 kHz.
- **Interruptions on the emulator:** call, unplug, transient focus loss and route change all stopped within the fade and stayed silent.
- **Background:** the bench runs as a foreground service with a partial wake lock, as playback would.
  - On the emulator the run happened entirely without a foreground activity, with 0 glitches.
  - Screen-off and Doze behaviour **on a real phone is not yet measured.**

**Totals:**
- `:ripple`: 33 unit tests pass.
- App: 63 unit tests pass.
- Full signal validation: 30/30 pass at 48 and 44.1 kHz after the 8 kHz change (`results-full.md`).

## 5. Remaining differences from the actual trial stimulus

D1–D11 are from G2; D3 is resolved by T1. E1–E7 were added by the engine.

| # | Difference | Status |
|---|---|---|
| D1 | Starting phase: article random, trial code in sine phase (crest factor 13–16 vs ≈ 23 dB) | article followed |
| D2 | Hearing correction: smooth (article) vs ½-octave staircase (trial), up to 4.0 / 7.9 / 11.9 dB at M 15 / 30 / 45 | article followed; only 0 and 15 dB are usable now |
| D3 | Maximum above 8 kHz | **resolved:** now identical to the article text and the trial code |
| D4 | Band centre c: geometric centre vs mean log-frequency of the harmonics (0.06 oct; in-band rate 1 ± 0.13 Hz) | open, minor |
| D5 | f0 continuous vs whole Hz | negligible |
| D6 | Digital level −30 dBFS (ours) vs −40 dBFS before ramps (trial); neither defines sound pressure | the acoustic cap governs |
| D7 | Trial: one fixed hour-long file repeated daily. Ours: fresh random draws | protocol decision (G4) |
| D8 | Trial: compressed .mp4 at 44.1 kHz. Ours: uncompressed, device rate | codec effect unknown |
| D9 | 16 kHz harmonic included vs excluded | negligible |
| D10 | Ramps per band vs on the sum | identical |
| D11 | Pitch: 17 tones by ear vs nearest of 17 to the app's continuous match | matching method differs |
| E1 | Session start: 5 s fade-in from silence (the trial file simply started) | safety; affects the first 5 s only |
| E2 | Session end: 3 s fade at the limit, 1 s on stop, 150 ms on interruption (trial: player behaviour) | safety |
| E3 | Dose: 60 min / 60 min break / 120 min per 24 h enforced. The trial only instructed this, and actual listening varied (~1 h/day reported) | stricter than the trial |
| E4 | Level: user-set, never rising by itself, capped by measurement. Trial: device volume ≤ 60%, self-chosen | different; no level data from the trial exists |
| E5 | Any interruption ends the session. Trial: whatever the participant's player did | stricter |
| E6 | Channels: generator mono; G4 would play the same signal to both ears. Trial files mono; playback setup unknown | presumed equivalent |
| E7 | 30/45 dB profiles unavailable. The trial offered all four, chosen by a self-test | pending audiologist |

## 6. Required before any listening test

All of the following are required, in this order:

1. **Real-phone silent bench pass** on the phone to be used: install "Ripple bench (silent)", run the full 8-minute validation including the screen-off part, and send the results. This covers V14 and real background behaviour.
2. **Acoustic output-level measurement** (spec section 4.1) for that phone, each headphone and each connection (wired / Bluetooth codec). It needs an ear simulator (IEC 60318-4 or -1), a class 1 meter and a calibrator, follows EN 50332, and accepts LAeq ≤ 75 dB(A) at maximum app gain and 100% volume. The result is a calibration record.
3. **Audible output path (G4 engineering):** an output sink feeding both ears; integration with the existing foreground playback service, audio focus, unplug and route-change callbacks; a route identifier matching the calibration record. Then repeat the silent bench and interruption tests on the real path.
4. **Separate new screens (no change to existing screens):**
   - screening questionnaire;
   - A1 audibility check (its test tones need the same level validation);
   - level setting with confirmation;
   - pre/post ratings and symptom report;
   - lock and advice messages.
   These need your approval of the design first.
5. **Audiologist review:**
   - hearing-profile selection (whether 15 dB is appropriate for you);
   - whether 30/45 dB may ever be enabled;
   - the screening wording.
6. **Protocol (G4), which decides:**
   - active/sham allocation and blinding (≈ 2 dB spectral difference, option A);
   - period lengths and washout, given the ≥ 3-week carryover;
   - fixed daily file vs fresh draws (D7);
   - primary outcome: the unmasked daily rating from Phase 0;
   - stopping criteria.
   Use with other people would also need ethics approval.
7. **Legal and regulatory** before any distribution: freedom-to-operate search, contact with Newcastle University, medical-device assessment.
8. **Your explicit approval** to set `AUDIBLE_PLAYBACK_AUTHORIZED = true`, after items 1–5.

## 7. Readiness

- **Engineering:** the isolated, silent engine and its safety interlocks are complete and validated on JVM and emulator.
- **Not ready for listening:** item 1 (real phone) is the next step you can do yourself; item 2 needs a laboratory.
