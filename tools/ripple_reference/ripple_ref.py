"""Independent reference implementation of Yukhnovich et al. 2025 (Hear Res 109335), eqs. 1-5,
amplitude-modulation variant, written from the article + PHASE1-RIPPLE-SPEC open choices only."""
import numpy as np

D, OMEGA, MU, R, NU = 1.0, 1.0, 4.5, 3.0, 0.125   # [P] eq 2,5
FMIN, FMAX = 1000.0, 16000.0                         # [P] eq 1
SEG_S, RAMP_S = 4.0, 1.0                             # [P] Methods

def harmonics(f0):
    n = np.arange(1, int(FMAX // f0) + 2)
    f = n * f0
    return n[(f >= FMIN) & (f <= FMAX)]

def hearing_weight(f, anchor28=2000*np.sqrt(2)):
    # 0 up to 2 kHz, 1/9 at 2.8 kHz, linear in log2 f to 8/9 at 8 kHz, maximum above 8 kHz
    # (updated after G2, decision T1: literal article wording, also the trial-code behaviour)
    f = np.asarray(f, dtype=float)
    w = np.interp(np.log2(f), np.log2([2000.0, anchor28, 8000.0]), [0.0, 1/9, 8/9])
    return np.where(f > 8000.0, 1.0, w)

def ramp(N, sr):
    t = np.arange(N) / sr
    w = np.ones(N)
    r = t < RAMP_S
    w[r] = 0.5 * (1 - np.cos(np.pi * t[r] / RAMP_S))
    te = (N - 1 - np.arange(N)) / sr   # time to last sample
    e = te < RAMP_S
    w[e] = np.minimum(w[e], 0.5 * (1 - np.cos(np.pi * te[e] / RAMP_S)))
    return w

def render(sr, N, band, am, M_db, target_dbfs, f0, q, p, phases, anchor28=2000*np.sqrt(2), ramp_fn=ramp):
    n_all = harmonics(f0)
    assert len(phases) == len(n_all), (len(phases), len(n_all))
    t = np.arange(N) / sr
    lo, hi = band
    c = np.sqrt(lo * hi)
    S = MU + R * np.sin(p + 2*np.pi*NU*t)                      # eq 5
    out = np.zeros(N)
    for n, phi in zip(n_all, phases):
        f = n * f0
        g = 10 ** (M_db * hearing_weight(f, anchor28) / 20)
        if am and lo <= f < hi:
            Fn = np.log2(f / c)                                   # eq 4
            A = 1 + D * np.sin(2*np.pi*(OMEGA*t + Fn*S) + q)      # eq 2
        else:
            A = 1.0
        out += g * A * np.sin(2*np.pi*n*f0*t + phi)              # eq 1 (psi_n = 0 in AM variant)
    out *= ramp_fn(N, sr)
    rms = np.sqrt(np.mean(out**2))
    return out * (10**(target_dbfs/20) / rms), n_all
