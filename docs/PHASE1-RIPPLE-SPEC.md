# Phase 1 — Spectral-ripple (cross-frequency decorrelation) generator: technical specification

Status: **G1 approved (rev. 2, 29 Oct 2026)**. Rev. 2 corrects V6, V7 and the hearing-safety section. G1 adds only the pure-DSP generator (`dsp/ripple`) and its tests; nothing in the app references it, and there is no UI and no listening exposure.
Scope: an experimental signal generator reproducing the stimulus of Yukhnovich et al. (2025), kept separate
from the Shower masker, personalization and the DFCRS-inspired Lab mode, none of which change.

## 1. Provenance rule (clean-room)

Every parameter below is taken from the **published article**, which is licensed CC BY 4.0:

> Yukhnovich E.A., Harrison S., Wray N., Alter K., Sedley W. *Hearing Research* 2025, 109335.
> doi:10.1016/j.heares.2025.109335. Cited below as [P], with equation, table or section numbers.

- The non-commercially licensed MATLAB repository (doi:10.25405/data.ncl.27109693) is **not** a source for this specification. Its code must not be copied, translated or used as a structural template.
- Where this document mentions the repository, it only records that the repository *disagrees* with the paper. It never says how the repository implements anything.
- The person who writes the Kotlin implementation should work from this document and the paper only.
- **Disclosure:** the author of this specification has read the repository code during the earlier licence and parameter review. For a stricter clean room, the implementation can be written by a separate developer (or a fresh agent session) given only this document and the paper. Section 7 explains this.

## 2. Stimulus definition (all from [P])

### 2.1 Segment

| Item | Value | Source |
|---|---|---|
| Segment duration | 4 s, non-overlapping, concatenated to 60 min | [P] Methods |
| Onset/offset | 1 s raised-cosine ramps on each segment | [P] Methods |
| Fundamental f0 | uniform random per segment, 96–256 Hz | [P] Methods |
| Harmonics | all n with 1 000 ≤ n·f0 ≤ 16 000 Hz | [P] eq. 1 |
| Starting phase φn | uniform random 0–2π per harmonic | [P] eq. 1 |
| Unmodulated amplitude | 1, i.e. flat spectrum, before hearing correction | [P] eq. 2 |

Harmonic counts: 156 at f0 = 96 Hz (n = 11…166) and 59 at f0 = 256 Hz (n = 4…62).

### 2.2 Modulation

```
s(t)   = Σn An(t) · sin(2π n f0 t + φn + ψn(t))                 [P] eq. 1
An(t)  = 1 + d · sin(2π[ω t + Fn · S(t)] + q)     (amplitude)   [P] eq. 2
ψn(t)  = π · (1 + d · sin(2π[ω t + Fn · S(t)] + q))  (phase)    [P] eq. 3
Fn     = log2(n f0 / c),  c = logarithmic centre of the band    [P] eq. 4
S(t)   = μ + r · sin(p + 2π ν t)                                [P] eq. 5
```

Parameter values:
- d = 1, ω = 1 Hz.
- μ = 4.5 cycles/octave, r = 3, so S(t) ranges from 1.5 to 7.5 cycles/octave.
- ν = 0.125 Hz, an 8 s cycle.
- q and p are uniform random 0–2π, drawn once per segment.

Outside the modulated band: An = 1 and ψn = 0 [P].

**Variant to implement: amplitude modulation only.**
- The amplitude-modulation group was significant on its own (t(26) = 2.11, p = 0.045) [P].
- The phase variant has an unresolved discrepancy (section 5).
- The noise-replacement variant showed no benefit, either in [P] or in our re-analysis.

### 2.3 Bands and assignment

There are seven octave-wide bands [P, Table 1]:

| Band | Range (kHz) |
|---|---|
| B1 | 1–2 |
| B2 | 1.4–2.8 |
| B3 | 2–4 |
| B4 | 2.8–5.7 |
| B5 | 4–8 |
| B6 | 5.7–11 |
| B7 | 8–16 |

Exact edges are 1000·2^(k/2) Hz.

[P, Table 1] maps 17 tinnitus match frequencies (1–16 kHz in ¼-octave steps) to bands:
- A1 is the active band.
- C1 is the sham band.
- A2 and C2 are used when part of A1 is inaudible.

The table is stored as data with a citation. A unit test checks all 17 rows against the published table.

### 2.4 Hearing-slope correction [P, Fig. 1 / Methods]

- Gain in dB = M · w(f), where M ∈ {0, 15, 30, 45} dB is chosen by the user.
- w(f) is 0 up to 2 kHz, 1/9 at 2.8 kHz, linear in log-frequency up to 8/9 at 8 kHz, and 1 above 8 kHz.
- It is applied per harmonic, as a smooth function of frequency.
- Default M = 0.
- After the correction, total RMS is renormalised (section 4), so the correction redistributes energy across frequencies rather than adding level.

### 2.5 Matching

The user matches their tinnitus to one of 17 tones (1–16 kHz in ¼-octave steps) [P].

The app's existing Guided Match gives a continuous frequency. That value is rounded to the nearest of the 17 frequencies, and the existing pitch-confidence rating is shown next to it. Low confidence blocks the generator (see S9).

## 3. Implementation architecture (proposed)

```
dsp/ripple/                      pure Kotlin, no Android, unit-testable on the JVM
  RippleSpec.kt                  constants above, each with its [P] reference
  RippleBands.kt                 band edges + Table 1 lookup (A1/A2/C1/C2)
  HearingSlope.kt                w(f) and M
  RippleSegmentRenderer.kt       renders one 4 s segment into a FloatArray (seeded RNG)
  RippleMetrics.kt               analysis used by the validation tests (section 6)
audio/
  RippleVoice.kt                 [G3] implements the existing Voice interface; plays pre-rendered segments
engine/
  RippleEngine.kt                background renderer (double buffer), session control, safety state
```

**Rendering**
- Segments are rendered ahead of time on a background thread, one segment ahead, into a double buffer.
- No synthesis happens on the audio thread. Up to 156 harmonics × 48 000 samples/s would otherwise be about 7.5 M oscillator steps per second.
- `RippleVoice` only copies samples. **G1 contains no `RippleVoice` and no engine:** the generator is pure DSP that nothing in the app calls, so it cannot produce sound.

**Sample rate**
- Uses the device's native rate (usually 48 kHz; the trial used 44.1 kHz).
- The harmonic set is defined in Hz, so it does not depend on the sample rate.

**Routing**
- Plays through MixerCore Layer B: capped at −6 dB, smoothed gain, −1 dBFS peak limiter, hard-clip guard.
- Uses a new SessionManager activity, so it is mutually exclusive with other Lab sessions.
- Never mixed with the Shower masker: [P] used the ripple alone, and a masker would cover the stimulus.

**Separation and gating**
- No changes to MaskerEngine, SoundLibrary, Personalize*, AcousticProcessingEngine or the DFCRS mode.
- Compile-time flag `RIPPLE_ENABLED = false` in release builds.
- No screen, menu entry or visual change. Any UI is a later, separate proposal.

**Validation build**
- A JVM command-line renderer (`tools/` + a Gradle test task) writes WAV files and a metrics report.
- CI runs it and publishes the report, so the signal can be reviewed without installing anything.

## 4. Hearing-safety controls

The app cannot know the sound pressure level. Phone output, headphone sensitivity and fit vary by more than 30 dB. A percentage of device volume is therefore **never** treated as a calibrated level. All controls below are relative or behavioural.

| # | Control |
|---|---|
| S1 | **Fixed digital level (signal integrity only).** Each segment is normalised to a fixed RMS (−30 dBFS) after the hearing correction, so peaks stay far below full scale and the limiter never has to act (V10). This keeps the signal undistorted and repeatable. It says **nothing** about sound pressure at the ear. |
| S2 | **Layer B cap** stays at −6 dB, and the hard-clip guard stays in place. |
| S3 | **User-set level.** Each session starts silent and fades in over 5 s. The user may raise the level up to "clearly audible, comfortable, not louder than my tinnitus is bothersome". The app never raises the level by itself and never restores a higher level than the last confirmed one. |
| S4 | **Measured ceiling.** The app gain is capped per measured device and headphone combination so that the acoustic limit in section 4.1 holds even at 100% device volume. Unmeasured combinations are not authorised. Comparison with the Shower volume is dropped: it is not a safety measure. |
| S5 | **Device-volume advisory.** If media volume is above 60% of maximum, the app shows a warning. It is labelled as advice from the study, not a safety limit. Android's own sound-dose warnings (CSD, Android 14+) stay active. |
| S6 | **Dose.** At most 60 min per session and a 60 min break between sessions, as in [P]. Proposed daily cap of 2 h. The timer cannot be extended mid-session. |
| S7 | **Stops.** Headphone disconnect pauses playback (existing behaviour). No automatic resume. A large hearing correction (30–45 dB) requires an extra confirmation, because it puts most of the energy at 8–16 kHz. |
| S8 | **Screening before first use** (adapted from the [P] exclusion and clinical red flags). Tinnitus made worse by moderately loud sounds, sound sensitivity or pain, pulsatile tinnitus, sudden hearing loss, or a one-sided new tinnitus each lead to "do not use, see a clinician". |
| S9 | **Stop rule.** If a session is followed by a rating 2 or more points higher, or by a new symptom (fullness, pain, distortion), the generator locks until the user reviews advice. No pitch match, or a low-confidence pitch match, also blocks use. |
| S10 | **Logging.** Every session's level setting, duration and pre/post ratings go to the existing local tracker, so dose and adverse changes are visible. |

Controls S3, S5–S10 are behavioural. Only S4, based on the measurement below, addresses sound pressure.

### 4.1 Output-level validation (required before any listening is authorised)

Digital RMS, peak limits and percentages of device volume do not determine sound pressure at the eardrum, so listening is authorised only after an acoustic measurement.

**Who and where.** An audiology or acoustics laboratory, or a person trained to use the equipment. This cannot be done from software, and was not done in G1.

**Equipment**
- Ear simulator matching the headphone type: IEC 60318-4 occluded-ear simulator for in-ear phones; IEC 60318-1 artificial ear, or a head-and-torso simulator, for on-ear and over-ear phones.
- Class 1 sound level meter or measurement system (IEC 61672-1).
- Acoustic calibrator (IEC 60942, 94 dB at 1 kHz), checked before and after each session.

**Method**
- Follow the EN 50332-1/-2 procedure for personal music players.
- Convert to free-field or diffuse-field equivalent levels as specified there, so results compare with ITU-T H.870 / WHO safe-listening values.

**Test signal**
- Render these configurations, 60 s each:
  - the generator output for two pitches, using the A1 band and the C1 band;
  - hearing correction M = 0 and M = 45 dB, each at the app's maximum Lab gain.
- Disable device audio effects (equaliser, Dolby or loudness enhancement). Record the Bluetooth codec and absolute-volume state.

**Measurements**
- LAeq (60 s) and LCpeak at device media volume 100%, 80%, 60% and 40%, and at the app's maximum gain.
- Three repetitions with re-seating of the headphone. Report the mean and spread.
- Result: a table of device volume × app gain → dB(A) for that combination.

**Acceptance (proposed, for approval)**
- At app maximum gain and 100% device volume, LAeq ≤ 75 dB(A) for every configuration.
- The weekly dose at the maximum allowed use (2 h/day, 14 h/week) is then about 10 dB below the ITU-T H.870 reference of 80 dB(A) for 40 h/week (allowed level for 14 h/week ≈ 84.6 dB(A)).
- LCpeak ≤ 115 dB(C).

**Outcome**
- A combination-specific gain cap, stored with the measurement record: device model, headphone model, connection, date and measurer.
- Any change of headphone, device or connection type requires a new measurement.

**Ears with hearing loss.** The 30 and 45 dB corrections add high-frequency energy for impaired ears. Their acceptance additionally requires the user's audiologist to agree, because a laboratory value cannot account for an individual's hearing loss.

## 5. Scientific uncertainties

1. **Starting phase.** [P] specifies random starting phases. The deposited trial-generation code appears to use a different setting. Which one the trial files actually used cannot be decided from public sources. The choice changes the peak level (crest factor) a lot, but barely changes the spectrum. This spec follows [P].
2. **Phase-variant depth.** [P] eq. 3 gives a phase swing of 0 to 2π. The deposited code differs. This is not relevant if only the amplitude variant is built.
3. **Hearing-correction shape.** [P] describes a smooth curve; the repository differs in detail. This spec follows [P].
4. **Band centre c.** [P] defines it as the "logarithmic centre" without a formula. This spec uses the geometric centre of the band edges. Any reasonable definition differs by a negligible constant.
5. **Level.** No level is given in [P] beyond "≤ 60% device volume". There is no evidence on dose-response by level.
6. **Codec.** The trial files were distributed in a compressed format, which may have changed the content above about 15 kHz. We generate uncompressed audio.
7. **Band power.** With d = 1, mean amplitude is unchanged as [P] states, but band *power* rises by 1.76 dB (mean An² = 1.5). It is unknown whether the sham was level-matched beyond that. In this spec, active and sham have equal total RMS by construction.
8. **Spatial undersampling (found while specifying V6).** In low bands with a high f0 an octave holds only about 4–8 harmonics (e.g. 1–2 kHz at f0 = 256 Hz: harmonics 4–7). Ripple densities up to 7.5 cycles/octave cannot be represented there and alias. This is a property of the published stimulus, not of this implementation; it is reproduced as published.
9. **Efficacy.** The effect is modest: −0.5 points over sham on a 0–10 scale. There are no predictors of response, no follow-up beyond 3 weeks, and no remission in the dataset (0 of 420 measurements at 0). This generator is a research instrument, not a therapy claim.

## 5b. Published description vs deposited trial stimulus

At G2, eleven differences (D1–D11) were documented in `docs/ripple-g2/G2-REPORT.md`. Option A was chosen: the stimulus follows the published article.

## 6. Validation plan (objective, automated, before any listening)

All tests use seeded RNGs and run on the JVM in CI. There is one report per build.

| # | Test | Pass criterion |
|---|---|---|
| V1 | Harmonic content: FFT of a segment with An ≡ 1 | Peaks at n·f0 (±1 bin) for every n in range. Energy outside 1–16 kHz < −60 dB re total. Harmonic count correct for f0 = 96, 176 and 256 Hz. |
| V2 | Flat spectrum (M = 0) | Harmonic amplitudes within ±0.25 dB of each other |
| V3 | Hearing correction | Measured gain at 1, 2, 2.8, 4, 8, 11 and 16 kHz within ±0.5 dB of w(f)·M for M = 15, 30 and 45. Total RMS unchanged (±0.1 dB). |
| V4 | Band table | All 17 rows of [P] Table 1 (A1/A2/C1/C2) reproduced exactly. Band edges = 1000·2^(k/2). |
| V5 | AM depth and rate | Per-harmonic envelope (demodulated) inside the band spans 0–2 (±0.02) and has mean 1 (±0.02). The envelope spectrum peaks at 1 Hz. Harmonics outside the band have envelope variation < 0.1 dB, apart from ramps. |
| V6 | Spectral ripple and its 8 s cycle | Envelopes are measured from the audio by demodulating each harmonic (section 6.1). **(a) Equation check:** for every in-band harmonic, the measured envelope (ramps divided out, middle 2 s) matches the paper's eq. 2, evaluated independently with the segment's recorded f0, q and p, to RMS error ≤ 0.01. **(b) Blind ripple density:** in segments where the in-band harmonics are dense enough to resolve 7.5 cycles/octave (lowest in-band harmonic number ≥ 22), a sinusoid fitted across log-frequency every 50 ms gives the density Ŝ(t) and ripple phase. The fit explains ≥ 95% of variance. The ripple-phase slope gives ω = 1 Hz ± 0.05. Pooled over ≥ 200 segments, Ŝ stays within 1.5–7.5 (±0.1), and its rate of change never exceeds 2πνr = 2.36 cyc/oct/s (+10%). **(c) The 8 s cycle:** one segment covers only half a cycle, and p and q are redrawn per segment, so the cycle cannot be seen inside a segment. Each segment's Ŝ(t) is placed at cycle phase p + 2πνt, using the recorded p, and pooled across segments. A sinusoid fitted to the pooled points must give mean 4.5 ± 0.1, amplitude 3 ± 0.1 and r² ≥ 0.98. |
| V7 | No modulation outside the band; correct band boundaries | For every harmonic outside the modulated band, the measured envelope (after dividing out the known 1 s ramps, middle 2 s) must be constant: (max − min)/(max + min) < 0.002, i.e. variation < 0.035 dB. Its modulation spectrum (0.25–20 Hz) is at least 60 dB below its mean. For every in-band harmonic, the envelope range is 2 ± 0.05 relative to its mean (full depth). The first harmonic inside each band edge is modulated and the first outside is not. Correlation between constant envelopes is not used: it is undefined. |
| V8 | Active vs sham equivalence | Long-term ⅓-octave spectra differ by < 0.5 dB outside the two bands, and by the predicted 1.76 dB at most inside them. Total RMS equal (±0.1 dB). |
| V9 | Ramps and joins | 1 s raised-cosine onset/offset (shape error < 1%). No sample-to-sample step at segment joins larger than within-segment steps. |
| V10 | Peaks and clipping | Over a 60 min render at nominal level: peak ≤ −6 dBFS, zero samples clipped, zero limiter gain reduction, no NaN or Inf. Crest factor reported. |
| V11 | Level stability | Segment RMS within ±0.5 dB of target over 60 min |
| V12 | Randomness | f0, q, p and φ distributions uniform (Kolmogorov–Smirnov p > 0.01 over 10 000 draws). Same seed gives identical output. |
| V13 | Sample-rate independence | V1–V11 pass at both 44.1 and 48 kHz |
| V14 | Real-time | **G3, needs a phone.** Rendering takes < 30% of real time on the reference phone. No audio underruns in a 60 min session. In G1 only the JVM render speed is reported. |
| V15 | Safety logic | S1 (normalisation, no clipping) is tested in G1. S2–S10 belong to the playback engine, which does not exist in G1; they are tested in G3. |

### 6.1 How envelopes are measured

For harmonic n, the audio is multiplied by e^(−i2πnf0t). It is then low-pass filtered by three cascaded moving averages, each exactly one period 1/f0 long; fractional lengths are handled by interpolation. This removes every other harmonic exactly while passing the slow envelope. The envelope is twice the magnitude of the result.

The measurement uses only the audio plus the segment's recorded f0. The recorded q and p are used only where a test says so (V6a, V6c). The reference equation in V6a is written separately from the generator.

Deliverables for review: the metrics report, spectrograms and modulation spectra for active and sham at three example pitches, and 10-second WAV excerpts at **reduced** level. The excerpts are listened to only by the developer and only after S1–S10 are approved.

## 7. Licensing and regulatory requirements (not legal advice)

**Sources**
- **Article [P], CC BY 4.0.** The equations, parameter values and Table 1 may be used, including commercially, with attribution. Scientific methods and mathematical formulas are generally not protected by copyright in any case.
- **Repository code and data:** README says CC BY-NC-SA 4.0; the repository metadata says CC BY-NC 4.0. The stricter terms are assumed: non-commercial only. Not used.
- The dataset re-analysis was research. No data is shipped.

**Patents and endorsement**
- No patent was found in a basic search. This is **not** a freedom-to-operate opinion; a professional patent search is recommended before commercial release.
- The authors plan their own software platform. Contacting Newcastle University is advisable both for permission questions and to avoid an implied endorsement.
- Do not use their names or the university's name in marketing.

**Clean room**
- Implement from this document and [P] only.
- Keep this document's revision history as provenance.
- Optionally, have the code written by someone who has not seen the repository.

**Regulatory**
- An app that claims to reduce or treat tinnitus is likely medical-device software in the EU (MDR) and in other markets.
- Until a regulatory assessment is done, the generator stays a non-public research feature with no therapeutic claims.
- Using it with other people as a study requires ethics approval.

## 8. Gates

| Gate | What happens | What it needs |
|---|---|---|
| G0 | This specification | Your approval |
| G1 | `dsp/ripple` and validation tests V1–V13 and V15, with no audio output on any device | — |
| G2 | CI metrics report, spectrograms and excerpts | Your review |
| G3 | Engine and playback path; developer-only device tests (V14, routing, limiter) behind the build flag | Approval of section 4 and a completed section 4.1 measurement before any listening |
| G4 | Decision on any personal listening protocol, considering the carryover and blinding limits from the dataset review | Separate approval |
