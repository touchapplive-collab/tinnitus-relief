import numpy as np, scipy.signal as ss, scipy.io.wavfile as wf, sys
sr=44100; dur=42; xf=2.0; N=int(sr*dur); rng=np.random.default_rng(7)
def bp(x,lo,hi,o=2): return ss.sosfilt(ss.butter(o,[lo,hi],'bandpass',fs=sr,output='sos'),x)
def pink(n):
    w=rng.standard_normal(n); f=np.fft.rfft(w); k=np.arange(len(f)); k[0]=1
    return np.fft.irfft(f/np.sqrt(k),n)
def smooth_env(n,rate,depth):
    pts=int(n/sr*rate)+3; v=rng.standard_normal(pts)
    e=np.interp(np.linspace(0,pts-1,n),np.arange(pts),v)
    e=ss.sosfiltfilt(ss.butter(1,rate,fs=sr,output='sos'),e); e/=np.abs(e).max()+1e-9
    return 1+depth*e
out=np.zeros((N,2))
common_h=bp(rng.standard_normal(N),1200,11000)
common_b=bp(pink(N),180,2600)
for c in range(2):
    h=0.65*common_h+0.35*bp(rng.standard_normal(N),1200,11000)
    b=0.6*common_b+0.4*bp(pink(N),180,2600)
    h/=h.std(); b/=b.std()
    out[:,c]=0.55*h*smooth_env(N,0.6,0.07)+0.35*b*smooth_env(N,0.3,0.1)
# droplets / splashes
n_ev=int(dur*420); t0=rng.integers(0,N-2000,n_ev)
for t in t0:
    L=int(sr*rng.uniform(0.003,0.014)); tt=np.arange(L)/sr
    f=rng.uniform(1400,5200); chirp=f*(1+rng.uniform(0,0.6)*tt/tt[-1])
    ph=2*np.pi*np.cumsum(chirp)/sr
    s=np.sin(ph)*np.exp(-tt/(L/sr/4))*rng.lognormal(-0.3,0.6)*0.9
    p=rng.uniform(0.15,0.85)
    out[t:t+L,0]+=s*np.sqrt(1-p); out[t:t+L,1]+=s*np.sqrt(p)
# gentle tilt: soften top end
out=ss.sosfilt(ss.butter(1,9000,'lowpass',fs=sr,output='sos'),out,axis=0)
out=ss.sosfilt(ss.butter(2,90,'highpass',fs=sr,output='sos'),out,axis=0)
# seamless loop: equal-power crossfade tail into head, drop tail
X=int(sr*xf); a=np.sin(np.linspace(0,np.pi/2,X))[:,None]; b=np.cos(np.linspace(0,np.pi/2,X))[:,None]
head=out[:X]*a+out[-X:]*b
loop=np.concatenate([head,out[X:-X]])
loop/=np.sqrt((loop**2).mean()); loop*=10**(-17/20)
loop=np.tanh(loop*1.2)/1.2
pk=np.abs(loop).max(); loop*=min(1,10**(-2/20)/pk)
print('len s',len(loop)/sr,'rms dB',20*np.log10(np.sqrt((loop**2).mean())),'peak dB',20*np.log10(np.abs(loop).max()))
wf.write(sys.argv[1],sr,(loop*32767).astype(np.int16))
