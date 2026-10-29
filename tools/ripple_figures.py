import numpy as np, csv, sys
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt
from scipy.io import wavfile
from scipy.signal import stft
R=sys.argv[1]; O=sys.argv[2]
plt.rcParams.update({'font.size':9,'figure.dpi':130})
ink='#2b2a28'; acc='#c0603a'; acc2='#4f7a8a'
def band(tag):
    for l in open(f'{R}/{tag}_params.txt'):
        if l.startswith('band='): p=l.split(); lo,hi=map(float,p[1].split('-')); return lo,hi
# 1 spectrograms
fig,ax=plt.subplots(1,3,figsize=(11,3.4),sharey=True)
for a,tag,title in zip(ax,['active','sham','active_M45'],['Active (B4, 2.8–5.7 kHz), M = 0','Sham (B6, 5.7–11.3 kHz), M = 0','Active, hearing correction M = 45 dB']):
    sr,x=wavfile.read(f'{R}/{tag}.wav'); x=x[:4*sr]
    f,t,Z=stft(x,sr,nperseg=4096,noverlap=4096-512)
    P=20*np.log10(np.abs(Z)+1e-12); P-=P.max()
    m=(f>=800)&(f<=17000)
    a.pcolormesh(t,f[m]/1000,P[m],vmin=-70,vmax=0,cmap='magma',shading='auto')
    a.set_yscale('log'); a.set_yticks([1,2,4,8,16]); a.set_yticklabels(['1','2','4','8','16'])
    lo,hi=band(tag); a.axhline(lo/1000,color='w',lw=.6,ls='--'); a.axhline(hi/1000,color='w',lw=.6,ls='--')
    a.set_title(title,fontsize=9); a.set_xlabel('time (s)')
ax[0].set_ylabel('frequency (kHz)')
fig.suptitle('Spectrograms of one 4 s segment (same random draws; dashed lines = modulated band)',fontsize=10)
fig.tight_layout(); fig.savefig(f'{O}/fig1_spectrograms.png'); plt.close()
# 2 envelope matrices
fig,ax=plt.subplots(1,2,figsize=(11,3.6),sharey=True)
for a,tag,title in zip(ax,['active','sham'],['Active: harmonic envelopes','Sham: harmonic envelopes']):
    rows=list(csv.reader(open(f'{R}/{tag}_envelopes.csv'))); t=np.array(rows[0][2:],float)
    fr=np.array([float(r[0]) for r in rows[1:]]); E=np.array([[float(v) for v in r[2:]] for r in rows[1:]])
    im=a.pcolormesh(t,fr/1000,E,cmap='viridis',vmin=0,vmax=2,shading='nearest')
    a.set_yscale('log'); a.set_yticks([1,2,4,8,16]); a.set_yticklabels(['1','2','4','8','16']); a.set_xlabel('time (s)'); a.set_title(title,fontsize=9)
ax[0].set_ylabel('harmonic frequency (kHz)')
fig.colorbar(im,ax=ax,label='envelope (1 = unmodulated)',shrink=.9)
fig.suptitle('Measured per-harmonic envelopes: the moving spectral ripple appears only inside the chosen band',fontsize=10)
fig.savefig(f'{O}/fig2_envelopes.png',bbox_inches='tight'); plt.close()
# 3 folded cycle
d=np.genfromtxt(f'{R}/48000_v6_fold.csv',delimiter=',',names=True)
fig,a=plt.subplots(figsize=(6,3.3))
ph=np.mod(d['cycle_phase'],2*np.pi)
a.scatter(ph/(2*np.pi)*8,d['S_hat'],s=3,color=acc,alpha=.5,label='measured Ŝ (40 segments shown)')
xx=np.linspace(0,2*np.pi,300); a.plot(xx/(2*np.pi)*8,4.5+3*np.sin(xx),color=ink,lw=1,label='[P] eq. 5: 4.5 + 3·sin')
a.set_xlabel('position in the 8 s cycle (s)'); a.set_ylabel('ripple density (cyc/oct)'); a.legend(frameon=False,fontsize=8)
a.set_title('V6c: 8 s cycle reconstructed from 4 s segments',fontsize=10)
fig.tight_layout(); fig.savefig(f'{O}/fig3_cycle.png'); plt.close()
# 4 third octave
d=np.genfromtxt(f'{R}/48000_v8_third_octave.csv',delimiter=',',names=True)
fig,a=plt.subplots(figsize=(6,3.3))
a.plot(d['centre_hz']/1000,d['active_minus_sham_predicted_db'],'-',color=ink,lw=1,label='predicted')
a.plot(d['centre_hz']/1000,d['active_minus_sham_measured_db'],'o',color=acc,ms=4,label='measured (100 segments)')
a.axvspan(2.828,5.657,color=acc,alpha=.12,label='active band'); a.axvspan(5.657,11.31,color=acc2,alpha=.12,label='sham band')
a.set_xscale('log'); a.set_xticks([1,2,4,8,16]); a.set_xticklabels(['1','2','4','8','16'])
a.set_xlabel('⅓-octave centre (kHz)'); a.set_ylabel('active − sham (dB)'); a.legend(frameon=False,fontsize=8)
a.set_title('V8: long-term spectrum, active vs sham (equal total RMS)',fontsize=10)
fig.tight_layout(); fig.savefig(f'{O}/fig4_third_octave.png'); plt.close()
# 5 crest
d=list(csv.reader(open(f'{R}/48000_v10_crest.csv')))[1:]
fig,a=plt.subplots(figsize=(6,3.3))
for name,col in [('B5, M = 0',acc2),('B7, M = 45 dB',acc)]:
    pk=[float(r[3]) for r in d if (r[0]+','+r[1])==name]; a.hist(pk,bins=40,alpha=.6,color=col,label=f'{name} (max {max(pk):.1f} dBFS)')
a.axvline(-6,color=ink,ls='--',lw=1); a.text(-6.3,a.get_ylim()[1]*.9,'limit −6 dBFS',ha='right',fontsize=8)
a.set_xlabel('segment peak (dBFS) at −30 dBFS RMS'); a.set_ylabel('segments'); a.legend(frameon=False,fontsize=8)
a.set_title('V10: peaks over two 60 min renders (900 segments each)',fontsize=10)
fig.tight_layout(); fig.savefig(f'{O}/fig5_peaks.png'); plt.close()
# 6 hearing curve
def w(f):
    pts=[(2000,0),(2828.427,1/9),(8000,8/9),(9513.66,1)]
    if f<=2000: return 0
    if f>=9513.66: return 1
    for (a1,w1),(b1,w2) in zip(pts,pts[1:]):
        if f<=b1: return w1+(w2-w1)*np.log2(f/a1)/np.log2(b1/a1)
fs=np.geomspace(1000,16000,300)
fig,a=plt.subplots(figsize=(6,3.3))
for M,col in zip([15,30,45],['#9bb5bd',acc2,acc]):
    a.plot(fs/1000,[M*w(f) for f in fs],color=col,label=f'M = {M} dB')
a.plot([2,2.828,8],[0,45/9,45*8/9],'k.',ms=6,label='[P] anchor points (M = 45)')
a.set_xscale('log'); a.set_xticks([1,2,4,8,16]); a.set_xticklabels(['1','2','4','8','16'])
a.set_xlabel('frequency (kHz)'); a.set_ylabel('added gain (dB)'); a.legend(frameon=False,fontsize=8)
a.set_title('Hearing-slope correction (measured within 0.001 dB, V3)',fontsize=10)
fig.tight_layout(); fig.savefig(f'{O}/fig6_hearing.png'); plt.close()
print('ok')
