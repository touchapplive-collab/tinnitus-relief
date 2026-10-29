# Spectral ripple in SILANCE — final software status (30 Oct 2026)

Experimental. **Audible ripple playback is disabled in every build** (`OutputAuthorization.AUDIBLE_PLAYBACK_AUTHORIZED = false`; CI fails if this changes). No software test in this project establishes a safe sound-pressure level; that requires the acoustic measurement in section 5.

Source: [P] Yukhnovich E.A., Harrison S., Wray N., Alter K., Sedley W. *Hearing Research* 2025, 109335 (CC BY 4.0). Implemented from the article only; no code from the non-commercial Newcastle repository is used.

## 1. What is built

| Area | Where | Status |
|---|---|---|
| Stimulus generator, [P] eq. 1–5 (AM variant), Table 1 bands, hearing correction | `:ripple` | done; 30/30 signal checks; independent article-only implementation matches to ≤ −118 dB |
| Silent engine: render-ahead (3 segments), fades, level rules, limits, interruptions, ledger | `:ripple/engine` | done |
| Safety gate: screening, pitch confidence, band audibility, hearing-profile lock (30/45 dB), dose limits, stop rules, calibration and route match, authorisation | `:ripple/engine` | done |
| Study plan ([P]: 2 × 6-week listening + 3-week washout, random blinded order), outcomes from unmasked daily ratings | `:ripple/therapy` | done |
| Listening checks: hearing-profile comparison (17 tones × profile), band audibility (match tones inside A1) | `:ripple/therapy` + app | logic and UI done; playback gated (disabled) |
| App integration: Lab → "Spectral ripple" (one new row); pages for safety questions, profile, audibility, plan, session, results, safety | `app/…/ripple`, `ui/RipplePage.kt` | done |
| Audible paths (ripple voice and check voice into the mixer's experimental layer with calibration gain, both ears) | `app/…/ripple` | written, unreachable: cannot be constructed while unauthorised |
| Silent rehearsal: full session flow (rating, fade-in, timer, level, stop/interruption, resume, report, locks) with all audio discarded | app | done, tested on emulator |
| Silent bench APK (real-time, background, interruptions on a phone) | `:ripplebench` | done; emulator pass; **real phone pending** |

Unchanged: Relief, mixing, Shower and its personalisation, offline playback, the other Lab tools, DFCRS, and all existing user data (the ripple layer writes only to `files/ripple/`).

## 2. Session flow (as implemented)

1. **Gate.** Every start is evaluated. All blockers are shown in plain words; the first one appears on the Lab row.
2. **Before the session.** Rating with no sound playing. For therapy sessions, this also becomes today's daily rating if none exists yet.
3. **Session.** Starts silent and fades in over 5 s. The level starts at −40 dB on the first session, and later sessions start no higher than the last confirmed level. Only the user can raise the level, up to −6 dB relative to the calibrated maximum. A countdown runs, and the session ends exactly at the allowed time with a 3 s fade.
4. **Interruptions.** Unplugged headphones, an output change, or a stop of the playback service (call, other audio, sleep timer) fade out over 150 ms and end the session. A session never resumes by itself. The user may continue it within 10 min, inside the same 60 min.
5. **After the session.** Rating with no sound playing, plus a symptom checklist.
   - Rating ≥ 2 points higher → locked for at least 24 h, then lifted only after acknowledging the advice.
   - Any symptom → locked until a clinician has cleared it.
6. **Results.** Per period: 7-day means at the start and end of listening and at the end of the washout; sessions and minutes. The active/sham labels are shown only after the plan ends or after an explicit, recorded unblinding.

## 3. Tests (all automated; no audible playback anywhere)

| Suite | Count | Result |
|---|---|---|
| `:ripple` unit tests (signal V1–V13 subset, negative controls, engine, gate, ledger, plan, outcomes, checks, codecs) | 42 | pass (CI) |
| Full signal validation, full counts, 48 + 44.1 kHz | 30 checks | pass (`docs/ripple-g3/results-full.md`) |
| Independent article-only re-implementation, sample by sample | 8 cases | ≤ −118 dB |
| App unit tests (existing + audible path cannot be created) | 64 | pass (CI) |
| App integration on an Android 14 emulator started without audio: UI path through screening, blocked therapy, full silent rehearsal with stop and report; interruption, no auto-resume, user resume | 2 | pass (CI) |
| Silent bench on the emulator: render speed, paced real-time at native rate (0 glitches, 0 underruns), 4 interruption types | 1 (19 checks) | pass (CI) |
| CI guards: authorisation flag false; no audio-output/input API in ripple code, bench or app ripple glue | — | pass |

## 4. Differences from the published stimulus and trial

No clinical equivalence is claimed. Differences D1–D11 compare the article with the deposited trial generator; E1–E9 come from this implementation.

| # | Difference | Status |
|---|---|---|
| D1 | Harmonic starting phase: random (article) vs sine phase (trial code). Crest factor 13–16 vs ≈ 23 dB; same spectrum | article followed |
| D2 | Hearing correction: smooth curve (article) vs ½-octave staircase (trial code), up to 4 dB at 15 dB | article followed |
| D3 | Maximum above 8 kHz | resolved: identical to the article and the trial code |
| D4 | Band centre: geometric (ours) vs mean log-frequency of the harmonics (0.06 oct; in-band rate 1 ± 0.13 Hz) | minor |
| D5 | f0 continuous vs whole Hz | negligible |
| D6 | Digital level −30 dBFS vs −40 dBFS before ramps; neither is a sound-pressure level | calibration governs |
| D7 | Trial: one fixed hour-long file per participant, repeated daily. Ours: fresh random segments | open; possible future option |
| D8 | Trial files compressed (.mp4, 44.1 kHz); ours uncompressed at the device rate | codec effect unknown |
| D9 | 16 kHz harmonic included vs excluded | negligible |
| D10 | Ramps per band vs on the sum | mathematically identical |
| D11 | Pitch: chosen by ear among 17 tones vs nearest of 17 to the app's continuous match | method differs |
| E1 | 5 s session fade-in; fade-outs of 3 s / 1 s / 150 ms | safety addition |
| E2 | Dose limits enforced: 60 min, 60 min break, 120 min per 24 h. The trial only instructed them | stricter |
| E3 | Level set by the user, never self-raising, capped by measurement. Trial: ≤ 60% device volume | different; no trial level data |
| E4 | Any interruption ends the session | stricter |
| E5 | Mono signal to both ears | presumed equivalent |
| E6 | 30/45 dB profiles locked (the trial offered all four) | pending audiologist |
| E7 | Outcome: 7-day means of daily 0–10 ratings with no sound (trial: one NRS rating at each period boundary, plus THI/TFI) | different measure |
| E8 | Allocation and blinding by the app; the sham band is C1 from Table 1 as in the trial. Active/sham long-term spectra differ by ≈ 2 dB (option A) | as published; blinding limited |
| E9 | Screening adds common clinical red flags to the trial's exclusion (worsening with moderate sound) | stricter |

## 5. Blockers and the exact steps before the first listening session

Each step needs physical measurement, professional review or your approval. Software alone cannot complete any of them.

1. **Real-phone silent bench.**
   - Install `RippleBench-silent.apk` on the phone you will use.
   - Run the full validation, including several minutes with the screen off.
   - Send the copied results. Required: 0 glitches, 0 underruns, slowest segment well under 12 s.
2. **Acoustic output measurement** (laboratory) for that phone and each headphone/connection you will use.
   - Equipment: an ear simulator (IEC 60318-4 for in-ear, 60318-1 or a head-and-torso simulator for on/over-ear), a class 1 meter and a calibrator.
   - Method: EN 50332-1/-2, measured at the app's maximum ripple level (−6 dB), Layer B cap and 100% device volume.
   - Measure: LAeq 60 s and LCpeak, for the active and sham bands, hearing profiles 0 and 15 dB.
   - Acceptance: LAeq ≤ 75 dB(A), LCpeak ≤ 115 dB(C).
   - Output: one calibration line per combination, with the route id the app shows under "Headphones and calibration": `device:…;headphone:…;route:…;measuredAtMs:…;measuredBy:…;maxGainDb:…;laeqDbA:…`.
3. **Audiologist review.**
   - Hearing profile choice (0 or 15 dB).
   - Whether 30/45 dB may ever be unlocked.
   - Screening wording.
   - Whether you personally should use the sound, given your hearing.
4. **Your decision on D7:** fresh random segments (current) or a fixed daily file as in the trial.
5. **Authorisation update** (needs your explicit approval). Set `AUDIBLE_PLAYBACK_AUTHORIZED = true`, change the CI guard accordingly, and build. Then, on the real phone:
   - repeat the bench and the emulator suite;
   - import the calibration record;
   - verify that the route id matches;
   - run the hearing-profile comparison and the band audibility check at a low level.
6. **First listening session.**
   - Choose the lowest clearly audible level, below your comfortable Shower level, and confirm it.
   - Keep the session short at first (for example 10–15 min by stopping manually).
   - Complete the report.
   - Start the 18-week plan only after a few uneventful sessions.
7. **Before anyone else uses it:** freedom-to-operate search, contact with Newcastle University, medical-device and regulatory assessment, ethics approval.
