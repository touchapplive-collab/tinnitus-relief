"""Reverse-engineers the DFCRS processing from the published example audio.

The MOST trial (eClinicalMedicine 2025;90:103671, PMC12766481) published example files, among them
  mmc3.mp3  "UM"                    – unmodified music
  mmc4.mp3  "DFCRS-4000hz-10.00dB"  – the same music processed for a 4000 Hz tinnitus pitch
If both contain the same music, the per-frequency level ratio DFCRS/UM *is* the filter that was applied.

This script measures, without assuming the answer:
  1. the average gain curve (1/48-octave resolution, 100 Hz – 16 kHz)
  2. which regions are boosted, their -3 dB edges and peak gain
  3. a comparison with the band placement published in Sound & Vibration 2025 (doi:10.59400/sv1928)
  4. whether the gain varies over time ("dynamic") – per-second gain in the boosted regions
and writes a Markdown report.

Usage:  python3 analyze_dfcrs_audio.py UM.mp3 DFCRS.mp3 report.md [pitch_hz=4000]
Needs ffmpeg and numpy.
"""
import subprocess, sys
import numpy as np

SR = 44100


def decode(path):
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-f', 'f32le', '-ac', '1', '-ar', str(SR), '-'],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)


def align(a, b, max_lag_s=3.0):
    """Lag (samples) that best aligns b to a, from the cross-correlation of the first 30 s."""
    n = min(len(a), len(b), SR * 30)
    A = np.fft.rfft(a[:n], 2 * n); B = np.fft.rfft(b[:n], 2 * n)
    xc = np.fft.irfft(A * np.conj(B))
    lim = int(max_lag_s * SR)
    cand = np.concatenate([xc[:lim], xc[-lim:]])
    lags = np.concatenate([np.arange(lim), np.arange(-lim, 0)])
    lag = lags[np.argmax(np.abs(cand))]
    corr = np.max(np.abs(cand)) / (np.linalg.norm(a[:n]) * np.linalg.norm(b[:n]) + 1e-12)
    return int(lag), float(corr)


def stft_power(x, n=8192, hop=2048):
    w = np.hanning(n)
    frames = (len(x) - n) // hop
    out = np.empty((frames, n // 2 + 1))
    for i in range(frames):
        seg = x[i * hop:i * hop + n] * w
        out[i] = np.abs(np.fft.rfft(seg)) ** 2
    return out


def main(um_path, df_path, report, pitch=4000.0):
    um, df = decode(um_path), decode(df_path)
    lag, corr = align(um, df)
    if lag > 0: df = df[lag:]
    elif lag < 0: um = um[-lag:]
    n = min(len(um), len(df))
    um, df = um[:n], df[:n]
    Pu, Pd = stft_power(um), stft_power(df)
    freqs = np.fft.rfftfreq(8192, 1 / SR)

    # 1/48-octave bands
    edges = 100 * 2 ** (np.arange(0, np.log2(16000 / 100) * 48 + 1) / 48)
    centers = np.sqrt(edges[:-1] * edges[1:])
    gain = np.full(len(centers), np.nan)
    for i in range(len(centers)):
        m = (freqs >= edges[i]) & (freqs < edges[i + 1])
        if m.sum() == 0: continue
        pu, pd = Pu[:, m].sum(), Pd[:, m].sum()
        if pu > 0: gain[i] = 10 * np.log10(pd / pu)
    # remove any broadband level offset (e.g. overall normalisation): median of all bands = 0 dB
    offset = np.nanmedian(gain)
    g = gain - offset

    boosted = g > 3.0
    regions = []
    i = 0
    while i < len(g):
        if boosted[i]:
            j = i
            while j + 1 < len(g) and boosted[j + 1]: j += 1
            pk = np.nanmax(g[i:j + 1])
            regions.append((centers[i], centers[j], pk))
            i = j + 1
        else: i += 1

    r = 2 ** (1 / 6)
    fu, fl = np.sqrt(2) * pitch, pitch / np.sqrt(2)
    predicted = [(fl / r, fl * r), (fu / r, fu * r)]

    # time variation of the boost: per ~1 s gain in each measured region
    hop_s = 2048 / SR
    per_sec = int(round(1 / hop_s))
    dyn = []
    for lo, hi, _ in regions:
        m = (freqs >= lo) & (freqs <= hi)
        mu, md = Pu[:, m].sum(axis=1), Pd[:, m].sum(axis=1)
        k = len(mu) // per_sec
        gu = mu[:k * per_sec].reshape(k, per_sec).sum(axis=1)
        gd = md[:k * per_sec].reshape(k, per_sec).sum(axis=1)
        valid = gu > np.percentile(gu, 20)
        gs = 10 * np.log10(gd[valid] / gu[valid]) - offset
        dyn.append((lo, hi, float(np.mean(gs)), float(np.std(gs)), float(np.min(gs)), float(np.max(gs))))

    with open(report, 'w') as f:
        f.write('# DFCRS example audio analysis\n\n')
        f.write(f'- files: `{um_path}` (UM) vs `{df_path}` (DFCRS), assumed pitch {pitch:.0f} Hz\n')
        f.write(f'- alignment lag {lag} samples, normalised correlation {corr:.3f} '
                f'({"same recording" if corr > 0.5 else "LOW – the files may not contain the same music; results unreliable"})\n')
        f.write(f'- broadband level offset removed: {offset:+.2f} dB\n\n')
        f.write('## Boosted regions (gain > 3 dB)\n\n| from Hz | to Hz | peak gain dB |\n|---|---|---|\n')
        for lo, hi, pk in regions: f.write(f'| {lo:.0f} | {hi:.0f} | {pk:.1f} |\n')
        f.write('\n## Published band placement (Sound & Vibration 2025, eq. 1–6, N = 3)\n\n| from Hz | to Hz |\n|---|---|\n')
        for lo, hi in predicted: f.write(f'| {lo:.0f} | {hi:.0f} |\n')
        f.write('\n## Time variation of the boost (1-second windows)\n\n| region | mean dB | std dB | min dB | max dB |\n|---|---|---|---|---|\n')
        for lo, hi, mu, sd, mn, mx in dyn: f.write(f'| {lo:.0f}–{hi:.0f} | {mu:.1f} | {sd:.2f} | {mn:.1f} | {mx:.1f} |\n')
        f.write('\nA std of a few tenths of a dB means a static boost; several dB means the gain itself changes over time.\n')
        f.write('\n## Gain curve (1/48 octave)\n\n| Hz | dB |\n|---|---|\n')
        for c, v in zip(centers, g):
            if not np.isnan(v): f.write(f'| {c:.0f} | {v:+.2f} |\n')
    print('report written to', report)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4]) if len(sys.argv) > 4 else 4000.0)
