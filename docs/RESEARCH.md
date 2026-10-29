# Research basis: what is published, what is assumed, what is experimental

Every Acoustic Lab feature is classified as one of:

- **PUBLISHED**: value or rule taken directly from a cited publication.
- **ASSUMPTION**: engineering choice made because the publication does not specify it.
- **EXPERIMENTAL**: offered for exploration only, with no therapeutic parameters implied.

All Lab modes are off by default, time-limited, capped at −6 dB, and require explicit activation. None is presented as a proven treatment.

## Sources read

| # | Publication | What it contains |
|---|---|---|
| S1 | Tang D, Wang K, Sun S. *Three-alternative forced-choice method (3AFC) for tinnitus matching and digital frequency customized relieving sound (DFCRS) generation strategy.* Sound & Vibration 59(1), 2025. doi:10.59400/sv1928 | 3AFC pitch and loudness procedure; band equations (1)–(6); n = 32 pilot |
| S2 | *Digital Frequency Customized Relieving Sound for Chronic Subjective Tinnitus Management: Prospective Controlled Study.* JMIR 2025;27:e60150 | Two 1/3-octave bands, +10 dB, ≥ 2 h/day, 3 months; non-randomised |
| S3 | Tang D et al. *Efficacy and safety of a modified sound therapy for patients with subjective tinnitus (MOST): multicentre, double-blind RCT.* eClinicalMedicine 2025;90:103671 (PMC12766481) | 440 patients, 9 months; DFCRS vs UM, UM+NBN, HFEM; example audio files |
| S4 | Tang D et al. *MTRS protocol.* Trials 2023 (doi:10.1186/s13063-023-07389-8) | Protocol only; algorithm referred to as "our particular algorithm" |
| S5 | Okamoto H et al. *Listening to tailor-made notched music reduces tinnitus loudness…* PNAS 2010 | One-octave notch around the tinnitus frequency |
| S6 | Feldmann H. 1971 and later residual-inhibition literature | 60 s masker, about 10 dB above minimum masking level |

## DFCRS-inspired mode

| Item | Status | Value in the app |
|---|---|---|
| Lower band centre Fl = Ft/√2, upper band centre Fu = √2·Ft | PUBLISHED (S1 eq. 1–2) | exact |
| Band edges (2^(1/N))^(±1/2)·F, N = 3 (1/3 octave) | PUBLISHED (S1 eq. 3–6; S2, S3 "1/3 octave") | exact, unit-tested |
| Gain +10 dB in both bands | PUBLISHED (S2, S3, label "DFCRS-4000hz-10.00dB"); S1 also says 5–10 dB | 10 dB default, adjustable 0–10 dB |
| Filter type, order and edge slope | **not published** → ASSUMPTION | zero-phase STFT gain mask, raised-cosine edges over 1/12 octave outside each band |
| "Dynamic" gain | **not defined** in any paper. S3 attributes the time variation to music's natural fluctuation. → ASSUMPTION | static band gain on time-varying source audio |
| Source material | "soothing / soft relieving music", titles not published → ASSUMPTION | any sound from the library; importing calm music is recommended |
| Playback level | S1: match or slightly exceed the tinnitus loudness; S2: comfortable; no SPL → ASSUMPTION | user volume, Layer B capped at −6 dB, limiter at −1 dBFS |
| Dose | ≥ 2 h/day for 3–9 months (S2, S3) | **not enforced or recommended by the app**; sessions are 1–60 min, default 15 |

**Conclusion.** Band placement and gain are reproduced faithfully. The filter shape and the dynamics are assumptions, so the mode is labelled "DFCRS-inspired". It is not clinically equivalent to the published method.

**How to close the gap.** S3 published the example audio files `mmc3.mp3` (unmodified music, "UM") and `mmc4.mp3` ("DFCRS-4000hz-10.00dB"). Their spectral ratio *is* the applied filter. `tools/analyze_dfcrs_audio.py` measures it: boosted regions, −3 dB edges, peak gain and second-by-second variation.

- The analyser was validated on a synthetic file with a known two-band +10 dB boost. It recovered 2522–3177 Hz and 5043–6354 Hz at 10.0 dB, against the predicted 2520–3175 Hz and 5040–6350 Hz.
- **Run on the real files (October 2026)**, downloaded from the Elsevier supplementary host by the *Research downloads* workflow (`mmc3`, `mmc4`, `mmc5`; 44.1 kHz stereo, 128 kbit/s, 198 s). Results:
  - `mmc4` is the same recording as `mmc3` (correlation 0.999) with two boosted regions: **2485–3131 Hz** (≈ +10.0 dB) and **5043–6354 Hz** (≈ +10.2 dB). Predicted by the published equations: 2520–3175 Hz and 5040–6350 Hz. Band placement and gain are confirmed.
  - **The boost is static**: second-by-second standard deviation 0.4 dB. "Dynamic" refers to the music, not to the filter.
  - **The edges are near brick-wall**: the gain goes from < 1 dB to > 9 dB within about 1/24 octave. The app's 1/12-octave raised-cosine transition is gentler than the published files. Setting the transition to ≈ 1/48 octave would match them (not changed yet: Lab algorithms are frozen until approved).
  - `mmc5` (HFEM) has **+20 dB** at 6.0–9.0 kHz, not the +10 dB stated in the paper's text.

## Evidence for DFCRS (for honest framing)

- **MOST RCT (S3):**
  - DFCRS beat unmodified music by −4.37 THI points (95 % CI −6.25 to −2.48). This is below the ~7-point minimal clinically important difference.
  - Responders reaching the MCID: 81 % for DFCRS vs 83 % for plain music.
  - One complete remission among 440 participants, in the HFEM arm.
- **JMIR (S2):** non-randomised; 5.6 % self-reported disappearance at 3 months.
- **S1:** pilot with n = 32; no numeric results reported.

## Spectral-ripple sound therapy (Yukhnovich et al., Hearing Research 2025) — not implemented yet

Source code and data were found in the Newcastle repository (doi:10.25405/data.ncl.27109693; README licence **CC BY-NC-SA 4.0**, figshare metadata CC BY-NC 4.0):
- `mod_ripple.m` — the complete generator: harmonic complex (f0 96–256 Hz), amplitude, phase or noise-replacement modulation of the harmonics in the chosen band, ripple `1 + sin(2π(φ + tmr·t + S(t)·f))`, S(t) = 4.5 ± 3 cyc/oct with an 8 s cycle, 1 s band ramps.
- `Generate_Full_Experiment_Stimuli.m` — "can be run in present form to exactly recreate stimuli used in original published trial": 900 × 4 s segments = 60 min per file, 7 octave-wide bands in ½-octave steps from 1–2 kHz to 8–16 kHz, 4 hearing-loss corrections (0/15/30/45 dB, 1→8 kHz). `loud = 0.1` is marked "needs optimising".
- `Sound files notes.docx` — the table that assigns the active and the sham band from the tinnitus match (17 match frequencies, 1–16 kHz in ¼-octave steps).
- `Hearing_Tin_Stim_Generation.m` — the tone / ½-octave noise matching stimuli.
- `Final_Data_Set.xlsx` — anonymised questionnaires (NRS loudness, THI, TFI, hyperacusis) for the cross-over trial; 77 completers per the README.

A protocol-faithful port is therefore possible. The licence allows it for non-commercial use with attribution and the same licence for the derived code; commercial use would need permission from Newcastle University.

## Other modes

| Mode | Published | Assumed / experimental |
|---|---|---|
| Notched | One-octave notch centred on Ft (S5) | depth −60 dB, edge slope, source audio |
| HFEM (6–9 kHz +10 dB) | MOST comparator arm (S3), which did slightly **worse** than unmodified music | filter shape; included for comparison only |
| Narrow-band noise | 1/3 octave centred on Ft (S3, UM+NBN arm) | level (not published); filter = two cascaded RBJ band-pass sections |
| Custom filter, pure / AM / FM tone | — | EXPERIMENTAL signal generators; defaults (4 Hz, 50 %, 50 cents) are neutral starting points, not therapeutic values |

## Pitch and loudness matching (3AFC)

PUBLISHED (S1):
- Three tones, 500 ms each, 1 s apart.
- Range 125–16 000 Hz, starting one octave apart (2/4/8 kHz in the worked example).
- Narrowing rule: picking Fa sets the upper limit 1/3 octave below Fb. Picking Fb sets the range to [Fa + 1/3 oct, Fc − 1/3 oct]. Picking Fc sets the lower limit 1/3 octave above Fb.
- Once the range is ≤ 2 octaves, tones are spaced 1/3 octave apart.
- Loudness: step D starts at 5 dB and shrinks by 1 dB to 1 dB. Results are reported as sensation level.

ASSUMPTIONS, marked [A1]–[A4] in `ThreeAfcPitch`:
- [A1] Geometric centring of each new triple in the remaining range.
- [A2] Re-centring in the fine stage, with at most 4 fine rounds.
- [A3] Octave check with f/2, f, 2f. S1 mentions an octave-confusion test but does not describe it.
- [A4] 1/24-octave fine-tuning.
- 10 ms on/off ramps on each tone.

LIMITATION: phone output is uncalibrated. Thresholds and levels are personal and relative (dBFS), never dB HL or dB SPL.

## Residual inhibition test

- 60 s of 1/3-octave narrow-band noise at the pitch.
- Level set by ear to "just covers" (approximate MML), plus 0 / 5 / 10 dB. +10 dB is the literature value.
- Ratings before and after, with outcome gone / quieter / same / louder.
- A stopwatch until the tinnitus returns, capped at 10 min.
- Limits: uncalibrated level, and the +10 dB step is capped by the −6 dB Lab limit (the UI says when that happens).

## Long-term tracking (Phase 0)

Four outcomes are kept apart: masking (rating while a sound plays), comfort, residual inhibition (after a sound stops) and long-term change (daily rating **with no sound playing**). Only the last one feeds the trend.

| Item | Status |
|---|---|
| Daily rating stores whether a sound was playing; a "Pause sound" button is offered; optional "bother" rating | ENGINEERING |
| Pitch confidence: HIGH = ≥ 3 estimates in 180 days within 1/3 octave and the latest not "not sure"; MEDIUM = ≥ 2 within 1/2 octave or one "very sure"; otherwise LOW | ENGINEERING RULE |
| Weekly summaries (Monday–Sunday); trend needs ≥ 4 weeks with ≥ 3 unmasked ratings; first two vs last two weeks; < 1 point = "about the same" | ENGINEERING THRESHOLD |
| "Self-reported tinnitus-free weeks": consecutive recent weeks with ≥ 4 unmasked ratings, all 0. The app never uses the word remission | wording rule |
| CSV export (one row per entry) through the system file picker; nothing leaves the phone otherwise | — |

## Relief test and long-term tracking

- Ratings use the 0–10 scale "How noticeable is your tinnitus right now?".
- A rating is taken before, right after, and 5 min after a session. The 5 minutes are an ASSUMPTION.
- One optional daily rating; no notifications are sent, to avoid drawing attention to the tinnitus.
- Everything is stored locally in `files/responses.tsv`.
- These are self-reports, not THI scores. They track personal trends; they are not clinical outcomes.

## Sound personalization (Relief → Personalize sound)

This is a **listening-preference** tool, not a treatment. No publication defines an optimal masker spectrum
for a person; the feature only helps the listener find the balance they find most comfortable and masking.

| Item | Status |
|---|---|
| FFT analysis (Welch, Hann, 4096, 1/6-octave), regions >30 dB below the strongest are not adjusted | ENGINEERING |
| 4 broad regions: low shelf 200 Hz, peaks 700 Hz / 2.5 kHz (Q 0.7), high shelf 7 kHz; −8…+6 dB | ENGINEERING |
| Loudness matching by A-weighted power of the analysed spectrum (IEC 61672 A-weighting) | ENGINEERING (A-weighting is a standard approximation of loudness, not a loudness model) |
| Search: broad directions (tilt, air, depth, presence), steps 4 → 2 → 1 dB, 5–8 comparisons, "no difference" retires a direction, original recommended without ≥2 decisive answers or ≥1.5 dB change | ENGINEERING |
| Original vs personalized sessions: balanced random order, blinded until the end | Standard bias-reduction practice; self-reports only |
