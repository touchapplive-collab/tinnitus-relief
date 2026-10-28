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
- It has **not** been run on the real files yet: this build environment cannot reach PMC. Run the GitHub workflow *DFCRS audio analysis*, or download the two files and run the script locally.
- If the measured slope differs from 1/12 octave, set `GainCurve.DEFAULT_TRANSITION_OCTAVES` (or the `transitionOctaves` setting) to the measured value.

## Evidence for DFCRS (for honest framing)

- **MOST RCT (S3):**
  - DFCRS beat unmodified music by −4.37 THI points (95 % CI −6.25 to −2.48). This is below the ~7-point minimal clinically important difference.
  - Responders reaching the MCID: 81 % for DFCRS vs 83 % for plain music.
  - One complete remission among 440 participants, in the HFEM arm.
- **JMIR (S2):** non-randomised; 5.6 % self-reported disappearance at 3 months.
- **S1:** pilot with n = 32; no numeric results reported.

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

## Relief test and long-term tracking

- Ratings use the 0–10 scale "How noticeable is your tinnitus right now?".
- A rating is taken before, right after, and 5 min after a session. The 5 minutes are an ASSUMPTION.
- One optional daily rating; no notifications are sent, to avoid drawing attention to the tinnitus.
- Everything is stored locally in `files/responses.tsv`.
- These are self-reports, not THI scores. They track personal trends; they are not clinical outcomes.
