import json, sys, numpy as np
sys.path.insert(0, sys.argv[1])
import ripple_ref as R
exp = sys.argv[2]
cases = json.load(open(exp + '/cases.json'))
def ramp_generator(N, sr):
    # generator convention: offset ramp computed from (4 s - t), so the last sample is ~1e-9 rather than 0
    t = np.arange(N) / sr; w = np.ones(N); d = N / sr
    a = t < 1; w[a] = 0.5 * (1 - np.cos(np.pi * t[a]))
    b = t > d - 1; w[b] = 0.5 * (1 - np.cos(np.pi * (d - t[b])))
    return w

variants = {
 'anchor=2828,ramp generator': dict(anchor28=2000*np.sqrt(2), ramp_fn=ramp_generator),
 'anchor=2828,ramp t/sr': dict(anchor28=2000*np.sqrt(2)),
 'anchor=2800,ramp t/sr': dict(anchor28=2800.0),
}
for c in cases:
    x = np.fromfile(f"{exp}/{c['name']}.f32", dtype='<f4').astype(float)
    assert len(x) == c['samples']
    for vn, kw in variants.items():
        y, n = R.render(c['sample_rate'], c['samples'], c['modulated_band_hz'], c['amplitude_modulation'],
                        c['hearing_max_db'], c['target_rms_dbfs'], c['f0'], c['q'], c['p'], c['start_phases'], **kw)
        err = x - y
        rx = np.sqrt(np.mean(x**2))
        print(f"{c['name']:16s} {vn:22s} n={n[0]}..{n[-1]} ({len(n)}) first_ok={n[0]==c['first_harmonic']} "
              f"maxabs={np.max(np.abs(err)):.3e} relRMS={20*np.log10(np.sqrt(np.mean(err**2))/rx):.1f} dB "
              f"rms={20*np.log10(rx):.3f} dBFS peak={20*np.log10(np.max(np.abs(x))):.1f}")
