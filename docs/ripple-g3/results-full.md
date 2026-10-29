# G1 validation results (full counts)

Failures: 0

## Sample rate 48000 Hz (227 s)

### V1 — Harmonic content: PASS

- f0 96 Hz: harmonics 11–166 (156, expected 156); worst peak offset 0 bin; energy outside 1–16 kHz -154.1 dB; energy not at harmonics -71.2 dB
- f0 176 Hz: harmonics 6–90 (85, expected 85); worst peak offset 0 bin; energy outside 1–16 kHz -154.5 dB; energy not at harmonics -71.2 dB
- f0 256 Hz: harmonics 4–62 (59, expected 59); worst peak offset 0 bin; energy outside 1–16 kHz -149.6 dB; energy not at harmonics -71.2 dB

### V2 — Flat spectrum (M = 0): PASS

- f0 96 Hz: harmonic levels within ±0.0000 dB
- f0 176 Hz: harmonic levels within ±0.0000 dB
- f0 256 Hz: harmonic levels within ±0.0000 dB

### V3 — Hearing correction: PASS

- M = 15 dB: measured/expected dB re 1 kHz: 1.02 kHz 0.00/0.00; 2.05 kHz 0.11/0.11; 2.82 kHz 1.65/1.65; 3.97 kHz 5.47/5.47; 8.06 kHz 15.00/15.00; 11.01 kHz 15.00/15.00; 16.00 kHz 15.00/15.00; worst error 0.000 dB; total RMS error 0.0000 dB
- M = 30 dB: measured/expected dB re 1 kHz: 1.02 kHz 0.00/0.00; 2.05 kHz 0.23/0.23; 2.82 kHz 3.29/3.29; 3.97 kHz 10.93/10.93; 8.06 kHz 30.00/30.00; 11.01 kHz 30.00/30.00; 16.00 kHz 30.00/30.00; worst error 0.000 dB; total RMS error 0.0000 dB
- M = 45 dB: measured/expected dB re 1 kHz: 1.02 kHz 0.00/0.00; 2.05 kHz 0.34/0.34; 2.82 kHz 4.94/4.94; 3.97 kHz 16.40/16.40; 8.06 kHz 45.00/45.00; 11.01 kHz 45.00/45.00; 16.00 kHz 45.00/45.00; worst error 0.000 dB; total RMS error 0.0000 dB
- curve anchor points: 0 at ≤2 kHz, 1/9 at 2.83 kHz, 8/9 at 8 kHz, 1 above 8 kHz: exact

### V4 — Band table: PASS

- rows: 17 rows, 0 mismatches against [P] Table 1
- band edges: 1000·2^(k/2) Hz, octave wide: exact
- nearest-tone lookup: 4.1 kHz → B4, 15 kHz → B7

### V5 — AM depth (in band): PASS

- segments: 40 (bands B5 and B7), 1430 in-band harmonic tracks
- envelope range: min 0.0000, max 2.0000 (expected 0 and 2, d = 1)
- pooled mean: 0.9974 (expected 1 ± 0.02)
- tracks spanning the full 0–2 range in the 1.9 s window: 1310 of 1430 (not required: near band edges F·S(t) can cancel ωt for a while)
- 1 Hz temporal rate: verified in V6b from the ripple phase

### V6a — Envelopes follow [P] eq. 2 and 5: PASS

- worst RMS error: 0.00141 (limit 0.01) over 1430 in-band harmonic tracks

### V6b — Blind ripple density and temporal rate: PASS

- segments: 200 (band B7, 8–16 kHz, ≥ 31 harmonics per octave), fits every 50 ms in 1.05–2.95 s
- worst explained variance: 1.0000 (limit 0.95)
- temporal rate ω: worst |ω̂ − 1 Hz| = 0.0000 Hz (limit 0.05)
- measured density range: 1.500 – 7.500 cyc/oct (published 1.5–7.5)
- max rate of change: 2.357 cyc/oct/s (limit 2.59)

### V6c — 8 s spectral-rate cycle (folded across segments): PASS

- pooled points: 7800 from 200 half-cycle segments, placed at cycle phase p + 2πνt (recorded p)
- fitted mean μ: 4.5000 (published 4.5)
- fitted extent r: 3.0000 (published 3)
- r²: 1.00000

### V7 — No modulation outside the band; band edges: PASS

- f0 96 Hz, band B3: 135 out-of-band harmonics: worst modulation index 2.3e-05 (limit 2e-3), worst modulation-spectrum line -113.0 dB re mean (limit −60); edge harmonics 21/41 modulated, 20/42 not: yes
- f0 176 Hz, band B5: 62 out-of-band harmonics: worst modulation index 6.4e-06 (limit 2e-3), worst modulation-spectrum line -130.1 dB re mean (limit −60); edge harmonics 23/45 modulated, 22/46 not: yes
- f0 256 Hz, band B1: 55 out-of-band harmonics: worst modulation index 3.2e-06 (limit 2e-3), worst modulation-spectrum line -131.4 dB re mean (limit −60); edge harmonics 4/7 modulated, 3/8 not: yes
- f0 143 Hz, band B7: 49 out-of-band harmonics: worst modulation index 9.8e-06 (limit 2e-3), worst modulation-spectrum line -129.3 dB re mean (limit −60); edge harmonics 56/111 modulated, 55/112 not: yes

### V8 — Active vs sham equivalence: PASS

- configuration: pitch 4 kHz → active B4, sham B6; 100 segments, identical random draws
- total RMS difference: 0.0000 dB (limit 0.05)
- ⅓-octave difference, measured vs predicted: worst deviation 0.003 dB (limit 0.25)
- largest |active − sham| outside both bands: 0.36 dB (renormalisation to equal total RMS; predicted)
- largest |active − sham| in a modulated band: 2.14 dB

### V9 — Ramps and joins: PASS

- ramp shape: worst deviation from 1 s raised cosine 2.74e-05 of full scale (limit 1%)
- segment join: largest sample step at the join 9.04e-07 vs 1.46e-01 inside a segment; end samples 4.1e-11

### V10 — Peaks and clipping: PASS

- B5, M = 0: 900 segments (60.0 min): max peak -14.24 dBFS (limit −6), clipped 0, NaN/Inf 0, crest factor mean 12.90 dB / max 15.76 dB
- B7, M = 45 dB: 900 segments (60.0 min): max peak -13.56 dBFS (limit −6), clipped 0, NaN/Inf 0, crest factor mean 13.23 dB / max 16.44 dB
- for comparison only: zero starting phases (not [P]): 20 segments: max peak -6.64 dBFS, crest factor max 23.36 dB at the same RMS
- limiter: Layer B gain ≤ −6 dB and the limiter ceiling is −1 dBFS, so with peaks ≤ −6 dBFS the limiter cannot engage
- JVM render speed (not V14): 2.2% of real time on this build machine; the phone measurement is V14 (G3)

### V11 — Level stability: PASS

- segment RMS: worst deviation from −30 dBFS: 3.6e-09 dB over 1800 segments (limit 0.5)

### V12 — Randomness and determinism: PASS

- f0: KS D = 0.0095, p = 0.322 (10000 draws)
- q: KS D = 0.0090, p = 0.392 (10000 draws)
- p: KS D = 0.0080, p = 0.543 (10000 draws)
- φ (first harmonic): KS D = 0.0082, p = 0.514 (10000 draws)
- determinism: same seed → identical samples: true; different seed → different: true

### NC — Negative controls (faults must be detected): PASS

- NC1 0.5% AM on an out-of-band harmonic: V7 index 5.0e-03 → detected
- NC2 −50 dB tone at 500 Hz: V1 out-of-range energy -48.9 dB → detected
- NC3 μ off by 0.1 cyc/oct: V6a worst RMS error 0.2625 → detected
- NC4 modulation depth 0.9: V6a RMS error 0.0713 → detected

## Sample rate 44100 Hz (213 s)

### V1 — Harmonic content: PASS

- f0 96 Hz: harmonics 11–166 (156, expected 156); worst peak offset 0 bin; energy outside 1–16 kHz -155.1 dB; energy not at harmonics -71.2 dB
- f0 176 Hz: harmonics 6–90 (85, expected 85); worst peak offset 0 bin; energy outside 1–16 kHz -155.2 dB; energy not at harmonics -71.2 dB
- f0 256 Hz: harmonics 4–62 (59, expected 59); worst peak offset 0 bin; energy outside 1–16 kHz -149.6 dB; energy not at harmonics -71.2 dB

### V2 — Flat spectrum (M = 0): PASS

- f0 96 Hz: harmonic levels within ±0.0000 dB
- f0 176 Hz: harmonic levels within ±0.0000 dB
- f0 256 Hz: harmonic levels within ±0.0000 dB

### V3 — Hearing correction: PASS

- M = 15 dB: measured/expected dB re 1 kHz: 1.02 kHz 0.00/0.00; 2.05 kHz 0.11/0.11; 2.82 kHz 1.65/1.65; 3.97 kHz 5.47/5.47; 8.06 kHz 15.00/15.00; 11.01 kHz 15.00/15.00; 16.00 kHz 15.00/15.00; worst error 0.000 dB; total RMS error 0.0000 dB
- M = 30 dB: measured/expected dB re 1 kHz: 1.02 kHz 0.00/0.00; 2.05 kHz 0.23/0.23; 2.82 kHz 3.29/3.29; 3.97 kHz 10.93/10.93; 8.06 kHz 30.00/30.00; 11.01 kHz 30.00/30.00; 16.00 kHz 30.00/30.00; worst error 0.000 dB; total RMS error 0.0000 dB
- M = 45 dB: measured/expected dB re 1 kHz: 1.02 kHz 0.00/0.00; 2.05 kHz 0.34/0.34; 2.82 kHz 4.94/4.94; 3.97 kHz 16.40/16.40; 8.06 kHz 45.00/45.00; 11.01 kHz 45.00/45.00; 16.00 kHz 45.00/45.00; worst error 0.000 dB; total RMS error 0.0000 dB
- curve anchor points: 0 at ≤2 kHz, 1/9 at 2.83 kHz, 8/9 at 8 kHz, 1 above 8 kHz: exact

### V4 — Band table: PASS

- rows: 17 rows, 0 mismatches against [P] Table 1
- band edges: 1000·2^(k/2) Hz, octave wide: exact
- nearest-tone lookup: 4.1 kHz → B4, 15 kHz → B7

### V5 — AM depth (in band): PASS

- segments: 40 (bands B5 and B7), 1430 in-band harmonic tracks
- envelope range: min 0.0000, max 2.0000 (expected 0 and 2, d = 1)
- pooled mean: 0.9974 (expected 1 ± 0.02)
- tracks spanning the full 0–2 range in the 1.9 s window: 1310 of 1430 (not required: near band edges F·S(t) can cancel ωt for a while)
- 1 Hz temporal rate: verified in V6b from the ripple phase

### V6a — Envelopes follow [P] eq. 2 and 5: PASS

- worst RMS error: 0.00141 (limit 0.01) over 1430 in-band harmonic tracks

### V6b — Blind ripple density and temporal rate: PASS

- segments: 200 (band B7, 8–16 kHz, ≥ 31 harmonics per octave), fits every 50 ms in 1.05–2.95 s
- worst explained variance: 1.0000 (limit 0.95)
- temporal rate ω: worst |ω̂ − 1 Hz| = 0.0000 Hz (limit 0.05)
- measured density range: 1.500 – 7.500 cyc/oct (published 1.5–7.5)
- max rate of change: 2.357 cyc/oct/s (limit 2.59)

### V6c — 8 s spectral-rate cycle (folded across segments): PASS

- pooled points: 7800 from 200 half-cycle segments, placed at cycle phase p + 2πνt (recorded p)
- fitted mean μ: 4.5000 (published 4.5)
- fitted extent r: 3.0000 (published 3)
- r²: 1.00000

### V7 — No modulation outside the band; band edges: PASS

- f0 96 Hz, band B3: 135 out-of-band harmonics: worst modulation index 2.4e-05 (limit 2e-3), worst modulation-spectrum line -113.0 dB re mean (limit −60); edge harmonics 21/41 modulated, 20/42 not: yes
- f0 176 Hz, band B5: 62 out-of-band harmonics: worst modulation index 7.0e-06 (limit 2e-3), worst modulation-spectrum line -128.4 dB re mean (limit −60); edge harmonics 23/45 modulated, 22/46 not: yes
- f0 256 Hz, band B1: 55 out-of-band harmonics: worst modulation index 3.3e-06 (limit 2e-3), worst modulation-spectrum line -130.9 dB re mean (limit −60); edge harmonics 4/7 modulated, 3/8 not: yes
- f0 143 Hz, band B7: 49 out-of-band harmonics: worst modulation index 9.9e-06 (limit 2e-3), worst modulation-spectrum line -129.7 dB re mean (limit −60); edge harmonics 56/111 modulated, 55/112 not: yes

### V8 — Active vs sham equivalence: PASS

- configuration: pitch 4 kHz → active B4, sham B6; 100 segments, identical random draws
- total RMS difference: 0.0000 dB (limit 0.05)
- ⅓-octave difference, measured vs predicted: worst deviation 0.010 dB (limit 0.25)
- largest |active − sham| outside both bands: 0.37 dB (renormalisation to equal total RMS; predicted)
- largest |active − sham| in a modulated band: 2.13 dB

### V9 — Ramps and joins: PASS

- ramp shape: worst deviation from 1 s raised cosine 2.74e-05 of full scale (limit 1%)
- segment join: largest sample step at the join 1.26e-06 vs 1.55e-01 inside a segment; end samples 5.5e-11

### V10 — Peaks and clipping: PASS

- B5, M = 0: 900 segments (60.0 min): max peak -14.19 dBFS (limit −6), clipped 0, NaN/Inf 0, crest factor mean 12.90 dB / max 15.81 dB
- B7, M = 45 dB: 900 segments (60.0 min): max peak -13.61 dBFS (limit −6), clipped 0, NaN/Inf 0, crest factor mean 13.22 dB / max 16.39 dB
- for comparison only: zero starting phases (not [P]): 20 segments: max peak -6.64 dBFS, crest factor max 23.36 dB at the same RMS
- limiter: Layer B gain ≤ −6 dB and the limiter ceiling is −1 dBFS, so with peaks ≤ −6 dBFS the limiter cannot engage
- JVM render speed (not V14): 2.1% of real time on this build machine; the phone measurement is V14 (G3)

### V11 — Level stability: PASS

- segment RMS: worst deviation from −30 dBFS: 4.1e-09 dB over 1800 segments (limit 0.5)

### V12 — Randomness and determinism: PASS

- f0: KS D = 0.0095, p = 0.322 (10000 draws)
- q: KS D = 0.0090, p = 0.392 (10000 draws)
- p: KS D = 0.0080, p = 0.543 (10000 draws)
- φ (first harmonic): KS D = 0.0082, p = 0.514 (10000 draws)
- determinism: same seed → identical samples: true; different seed → different: true

### NC — Negative controls (faults must be detected): PASS

- NC1 0.5% AM on an out-of-band harmonic: V7 index 5.0e-03 → detected
- NC2 −50 dB tone at 500 Hz: V1 out-of-range energy -48.9 dB → detected
- NC3 μ off by 0.1 cyc/oct: V6a worst RMS error 0.2625 → detected
- NC4 modulation depth 0.9: V6a RMS error 0.0714 → detected

