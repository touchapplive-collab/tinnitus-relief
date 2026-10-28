"""Checks every bundled loop for a click at the wrap point and reports its level.

Decodes each res/raw/*.ogg with ffmpeg and compares the jump from the last sample to the first sample
with the 99.9th percentile of ordinary sample-to-sample steps inside the file.
Usage: python3 check_loops.py app/src/main/res/raw
"""
import subprocess, sys, glob, os
import numpy as np

def decode(path):
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-f', 's16le', '-ac', '2', '-ar', '44100', '-'],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.int16).reshape(-1, 2).astype(np.float64) / 32768

ok = True
for f in sorted(glob.glob(os.path.join(sys.argv[1], '*.ogg'))):
    x = decode(f)
    steps = np.abs(np.diff(x, axis=0)).max(axis=1)
    p999 = np.percentile(steps, 99.9)
    seam = np.abs(x[0] - x[-1]).max()
    rms = 20 * np.log10(np.sqrt((x ** 2).mean()))
    peak = 20 * np.log10(np.abs(x).max())
    good = seam <= p999
    ok &= good
    print(f"{os.path.basename(f):18s} {len(x)/44100:5.1f}s  rms {rms:6.1f} dBFS  peak {peak:5.1f} dBFS  "
          f"seam {seam:.4f} vs p99.9 step {p999:.4f}  {'OK' if good else 'CLICK'}")
sys.exit(0 if ok else 1)
