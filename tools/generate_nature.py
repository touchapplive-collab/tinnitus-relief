"""Synthesises the bundled nature loops (rain, heavy rain, ocean, river, waterfall, fan).

All sounds are generated procedurally (no third-party recordings, no licensing issues), made seamless
with an equal-power crossfade of the tail into the head, loudness-normalised to about -18 dBFS RMS,
and encoded as Ogg Vorbis for res/raw.

Usage: python3 generate_nature.py OUT_DIR
"""
import sys, subprocess, os
import numpy as np
import scipy.signal as ss
import scipy.io.wavfile as wf

SR = 44100
rng = np.random.default_rng(11)


def sos(kind, f, order=2):
    return ss.butter(order, f, kind, fs=SR, output='sos')


def filt(x, kind, f, order=2):
    return ss.sosfilt(sos(kind, f, order), x, axis=0)


def pink(n):
    w = rng.standard_normal(n)
    F = np.fft.rfft(w); k = np.arange(len(F)); k[0] = 1
    return np.fft.irfft(F / np.sqrt(k), n)


def brown(n):
    w = rng.standard_normal(n)
    F = np.fft.rfft(w); k = np.arange(len(F)).astype(float); k[0] = 1
    return np.fft.irfft(F / k, n)


def env(n, rate, depth, smooth=1.0):
    pts = int(n / SR * rate) + 4
    v = rng.standard_normal(pts)
    e = np.interp(np.linspace(0, pts - 1, n), np.arange(pts), v)
    e = ss.sosfiltfilt(sos('lowpass', max(rate * smooth, 0.05), 1), e)
    e /= np.abs(e).max() + 1e-9
    return 1 + depth * e


def stereo(fn, n, corr=0.6):
    c = fn(n)
    return np.stack([corr * c + (1 - corr) * fn(n), corr * c + (1 - corr) * fn(n)], axis=1)


def norm(x):
    return x / (np.sqrt((x ** 2).mean()) + 1e-12)


def drops(n, rate, fmin, fmax, dmin, dmax, amp_sigma=0.6, chirp=0.5):
    out = np.zeros((n, 2))
    count = int(n / SR * rate)
    for t in rng.integers(0, n - 4000, count):
        L = int(SR * rng.uniform(dmin, dmax)); tt = np.arange(L) / SR
        f = rng.uniform(fmin, fmax) * (1 + rng.uniform(0, chirp) * tt / tt[-1])
        s = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt / (L / SR / 4)) * rng.lognormal(-0.3, amp_sigma)
        p = rng.uniform(0.1, 0.9)
        out[t:t + L, 0] += s * np.sqrt(1 - p); out[t:t + L, 1] += s * np.sqrt(p)
    return out


def finish(x, name, out_dir, xf=2.0, rms_db=-18.0):
    x = filt(x, 'highpass', 30, 2)
    X = int(SR * xf)
    a = np.sin(np.linspace(0, np.pi / 2, X))[:, None]; b = np.cos(np.linspace(0, np.pi / 2, X))[:, None]
    loop = np.concatenate([x[:X] * a + x[-X:] * b, x[X:-X]])
    loop = norm(loop) * 10 ** (rms_db / 20)
    loop = np.tanh(loop * 1.3) / 1.3
    pk = np.abs(loop).max(); loop *= min(1, 10 ** (-1.5 / 20) / pk)
    wav = os.path.join(out_dir, name + '.wav'); ogg = os.path.join(out_dir, name + '.ogg')
    wf.write(wav, SR, (loop * 32767).astype(np.int16))
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i', wav, '-c:a', 'libvorbis', '-q:a', '4', ogg], check=True)
    os.remove(wav)
    print(name, 'len', round(len(loop) / SR, 1), 's  rms dB', round(20 * np.log10(np.sqrt((loop ** 2).mean())), 1))


def main(out_dir):
    D = 36; N = SR * D
    # gentle rain: soft broadband hiss + sparse light drops
    hiss = filt(stereo(pink, N, 0.4), 'bandpass', [500, 9000])
    x = 0.6 * norm(hiss) * env(N, 0.4, 0.06)[:, None] + 0.9 * drops(N, 90, 2500, 7000, 0.002, 0.008)
    finish(filt(x, 'lowpass', 10000, 1), 'rain_gentle', out_dir)
    # heavy rain: dense drops, louder hiss, low rumble of water on surfaces
    hiss = filt(stereo(pink, N, 0.5), 'bandpass', [250, 11000])
    rumble = filt(stereo(brown, N, 0.7), 'lowpass', 400)
    x = 0.8 * norm(hiss) * env(N, 0.25, 0.12)[:, None] + 0.35 * norm(rumble) + 0.6 * drops(N, 900, 1500, 6000, 0.002, 0.01)
    finish(x, 'rain_heavy', out_dir)
    # ocean: brown/pink surf shaped by slow wave swells (~9–13 s)
    surf = 0.6 * norm(filt(stereo(brown, N, 0.6), 'lowpass', 1200)) + 0.4 * norm(filt(stereo(pink, N, 0.5), 'bandpass', [300, 6000]))
    t = np.arange(N) / SR
    period = D / 3.0  # three swells per loop so the loop stays periodic
    phase = 2 * np.pi * t / period
    swell = 0.35 + 0.65 * (0.5 - 0.5 * np.cos(phase)) ** 2.2
    crash = (0.5 - 0.5 * np.cos(phase)) ** 6
    hiss = norm(filt(stereo(pink, N, 0.3), 'highpass', 2500)) * crash[:, None] * 0.5
    finish(surf * swell[:, None] + hiss, 'ocean', out_dir)
    # river: mid-band flow with fast gurgling modulation and bubbles
    flow = norm(filt(stereo(pink, N, 0.5), 'bandpass', [180, 3500]))
    gurgle = env(N, 6.0, 0.35)[:, None]
    bubbles = drops(N, 60, 350, 1100, 0.008, 0.03, 0.5, 0.8)
    finish(0.8 * flow * gurgle + 0.6 * bubbles + 0.25 * norm(filt(stereo(pink, N, 0.4), 'highpass', 3000)), 'river', out_dir)
    # waterfall: dense, steady broadband roar
    roar = 0.7 * norm(filt(stereo(brown, N, 0.6), 'lowpass', 2500)) + 0.5 * norm(filt(stereo(pink, N, 0.6), 'bandpass', [400, 7000]))
    finish(roar * env(N, 0.5, 0.05)[:, None], 'waterfall', out_dir)
    # fan: smooth airflow (brown/pink, low-passed) with a faint motor hum and blade swish
    air = 0.8 * norm(filt(stereo(brown, N, 0.8), 'lowpass', 900)) + 0.3 * norm(filt(stereo(pink, N, 0.8), 'bandpass', [600, 3000]))
    hum_f = 100.0  # 100 Hz: whole number of cycles per loop
    hum = 0.015 * (np.sin(2 * np.pi * hum_f * t) + 0.4 * np.sin(2 * np.pi * 2 * hum_f * t))
    swish = 1 + 0.04 * np.sin(2 * np.pi * 15.0 * t)  # 15 Hz blade rate, whole cycles per loop
    finish(air * swish[:, None] + hum[:, None], 'fan', out_dir)


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else '.')
