# Gate G2 — independent validation of the spectral-ripple generator (29 Oct 2026)

Decision recorded: **option A**. The stimulus stays as published; active and sham are not re-matched.

## 1. Independent verification against the article

A separate reviewer re-implemented [P] eq. 1–5 in Python (`tools/ripple_reference/`). The reviewer worked from the CC BY article and the spec's documented open choices only, and opened the generator source only afterwards. The reviewer had no access to the Newcastle repository.

The generator's output was then compared sample by sample on 8 cases: bands B1–B7, hearing correction 0/15/30/45 dB, AM and unmodulated, 48 and 44.1 kHz.

| Case | Harmonics | Max abs error | RMS error re signal |
|---|---|---|---|
| b5_am_m0_48k | 6–85 | 1.3e-7 | −122.4 dB |
| b5_am_m45_48k | 6–83 | 1.0e-7 | −126.4 dB |
| b3_am_m15_48k | 9–140 | 5.8e-8 | −131.1 dB |
| b1_am_m30_48k | 7–96 | 8.2e-9 | −148.9 dB |
| b7_am_m0_48k | 7–101 | 2.4e-7 | −119.0 dB |
| b4_none_m0_48k | 5–74 | 3.7e-9 | −151.8 dB |
| b6_am_m0_44k | 7–101 | 2.9e-7 | −118.3 dB |
| b2_am_m45_44k | 6–82 | 8.3e-9 | −151.2 dB |

- The residual comes from the 32-sample envelope control rate.
- With the reviewer's own convention for the last ramp sample (exactly 0), the error is −97 dB in every case. That difference is inaudible and does not affect segment joins.
- Audit result: every numeric parameter and all 17 rows of Table 1 (A1/A2/C1/C2) match the article. No unsourced parameter was found.

## 2. Band for a 5.04 kHz pitch

- **Nearest match tone:** 4.76 kHz (printed 4.8 kHz), 1.0 semitone below 5.04 kHz.
- **Bands:** active A1 = B5 (4–8 kHz), alternative A2 = B4 (2.8–5.7 kHz), sham C1 = B3 (2–4 kHz), alternative C2 = B2 (1.4–2.8 kHz).
- **Decision boundary:** the boundary to the 5.66 kHz row is 5.19 kHz, 0.5 semitone above. Above it, A1 and C1 stay B5 and B3; only the alternatives A2/C2 disappear.
- The main bands are therefore robust to pitch-match error at this frequency.
- [P] uses A2 only if part of A1 (4–8 kHz) is inaudible. That needs an audibility check, which belongs to G3/G4.
- Covered by the unit test `pitch5040HzUsesTheFourPointEightKilohertzRow`.

## 3. Published description vs the deposited trial stimulus

The authors state that their deposited generator "can be run in present form to exactly recreate stimuli used in the original published trial". Below are the observed differences between that code's settings and the article. Only behaviour is described; no code is reproduced.

| # | Item | Article | Deposited trial generator | This implementation | Effect |
|---|---|---|---|---|---|
| D1 | Harmonic starting phase | random | all harmonics in sine phase | random (article) | same spectrum; crest factor ≈ 23 dB instead of ≈ 13–16 dB |
| D2 | Hearing correction shape | 0 to 2 kHz, 1/9 at 2.8, linear to 8/9 at 8 kHz, maximum above | one value per ½-octave band: 0, 0, 1/8, 3/8, 5/8, 7/8, 1, 1 (from 1 kHz up), a staircase | smooth curve through the article's anchors | up to 4.0 / 7.9 / 11.9 dB difference (RMS 1.4 / 2.8 / 4.3 dB) at M = 15 / 30 / 45, at the lower edge of each ½-octave band |
| D3 | Maximum above 8 kHz | "maximum above 8 kHz" | full maximum from 8 kHz | reaches maximum ¼ octave above 8 kHz (spec assumption) | up to ~5 dB less boost at 8–9.5 kHz for M = 45; **see decision T1** |
| D4 | Band centre c | "logarithmic centre" | mean log-frequency of the modulated harmonics | geometric centre of the band edges | offset ≈ 0.06 octave; the in-band temporal rate wanders by up to ±0.13 Hz around 1 Hz |
| D5 | f0 | random 96–256 Hz | rounded to whole Hz | continuous | negligible |
| D6 | Level | ≤ 60% device volume, no dBFS value | each 4 s segment scaled to −40 dBFS RMS (before ramps), marked "needs optimising" | −30 dBFS RMS including ramps (≈ −28.4 dBFS on the plateau) | ≈ 11.6 dB hotter digitally. Neither defines sound pressure; the acoustic cap (spec 4.1) governs |
| D7 | Repetition | hour-long tracks | one fixed hour-long file per participant, so the same random draws were repeated every day | new random draws every segment | unknown; relevant to protocol design (**T4**) |
| D8 | Delivery | not stated | compressed .mp4 at 44.1 kHz, mono | uncompressed float, device rate, mono | codec effects on >15 kHz content and envelope detail are unknown |
| D9 | Upper harmonic limit | 1–16 kHz | a harmonic exactly at 16 kHz is excluded | included | negligible: only when f0 divides 16000 exactly |
| D10 | Ramps | 1 s raised cosine | applied per ½-octave band before summing | applied to the summed segment | mathematically identical |
| D11 | Pitch matching | 17 tones chosen by ear (Fig. 1 caption says 8 sounds) | 17 tones | nearest of 17 to the app's continuous match | matching method differs; band choice robust at 5.04 kHz |

The SMR parameters (4.5 ± 3 cyc/oct, 8 s), d = 1 and ω = 1 Hz agree in all three. In the amplitude variant, the per-segment renormalisation that includes the modulated band (option A) is the same in the trial code and here.

## 4. Unresolved issues

**Technical**

| # | Issue | Status |
|---|---|---|
| T1 | Hearing correction above 8 kHz (D3). The literal article text and the trial code agree on full maximum from 8 kHz; the spec assumption reaches it ¼ octave later. | **Decision needed**; changing it is a signal change |
| T2 | Smooth curve (article) vs staircase (trial code) for the hearing correction (D2) | Article followed; document only |
| T3 | Zero vs random starting phase (D1) | Article followed; the acoustic measurement covers the implemented signal |
| T4 | Fixed daily file (trial) vs fresh random draws (D7) | Decide in the protocol stage (G4) |
| T5 | Envelope update every 32 samples | Effect ≤ −118 dB; acceptable |
| T6 | Playback engine, build flag, real-time on a phone (V14), safety logic S2–S10 (V15) | Not built: G3 |

**Safety**

| # | Issue | Status |
|---|---|---|
| S-a | No acoustic output-level validation yet (spec 4.1) | **Blocks any listening** |
| S-b | No level-setting rule tied to sound pressure: the article gives only "≤ 60% volume", and the trial's digital level is marked unoptimised | Needs 4.1 plus a protocol rule |
| S-c | High-frequency boosts of 30–45 dB for impaired ears | Need audiologist agreement (spec 4.1) |
| S-d | A2/C2 depend on an audibility check that does not exist yet | G3/G4 |
| S-e | Screening and stop rules (S8/S9) are specified but not implemented | G3 |
| S-f | Active vs sham differ by ≈ 2 dB in different octaves (option A); blinding is limited | Accepted; documented |

**Legal and regulatory** (unchanged from the spec): professional freedom-to-operate search, Newcastle contact, regulatory assessment and ethics approval before any use with other people.

## 5. Readiness

**The signal generator is technically ready for G3** (playback engine and developer-only device tests without listening), provided decision T1 is taken first. It is **not ready for any listening**: S-a and S-b block it.

Tests: 13 ripple tests (12 G1 + the 5.04 kHz case) and all 63 earlier tests pass. The full validation from G1 is unchanged (30/30 checks).
