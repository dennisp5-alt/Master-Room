#!/usr/bin/env python3
"""Synthetic honky-tonk test song, built to contain the faults mastering has to deal with:
muddy low mids, a 3-6 kHz vocal edge, off-centre bass, a chorus that jumps. Test signal only:
good for measuring a chain, useless for judging how it sounds."""
import numpy as np, sys, wave
from scipy.signal import butter, sosfilt, fftconvolve
sr=48000; seconds=float(sys.argv[2]) if len(sys.argv)>2 else 100; seed=int(sys.argv[3]) if len(sys.argv)>3 else 1
rng=np.random.default_rng(seed); n=int(sr*seconds); bpm=150; beat=60/bpm
L=np.zeros(n); R=np.zeros(n)
sections=[(0,8,'intro',.55),(8,40,'verse',.6),(40,56,'chorus',1.0),(56,88,'verse',.62),(88,112,'chorus',1.0),(112,128,'break',.8),(128,160,'chorus',1.05),(160,168,'outro',.5)]
def energy(b):
    for s,e,_,g in sections:
        if s<=b<e: return g
    return .5
def add(buf,i,s,g=1.0):
    if i>=n: return
    m=min(len(s),n-i); buf[i:i+m]+=s[:m]*g
def bp(x,lo,hi,o=2): return sosfilt(butter(o,[lo/(sr/2),hi/(sr/2)],'band',output='sos'),x)
def kick():
    t=np.arange(int(.3*sr))/sr
    return (np.sin(2*np.pi*(52*t+ (120/30)*(1-np.exp(-t*30))))*np.exp(-t*11)+.25*np.exp(-t*300)*rng.standard_normal(t.size))
def snare():
    t=np.arange(int(.22*sr))/sr
    return (.75*bp(rng.standard_normal(t.size),300,8000)*np.exp(-t*20)*2+.5*np.sin(2*np.pi*185*t)*np.exp(-t*28))
def hat():
    t=np.arange(int(.05*sr))/sr
    return bp(rng.standard_normal(t.size),6000,15000)*np.exp(-t*90)
def pluck(f,dur,bright=.5):
    N=max(2,int(sr/f)); m=int(dur*sr); buf=rng.uniform(-1,1,N); out=np.zeros(m)
    for i in range(0,m,N):
        k=min(N,m-i); out[i:i+k]=buf[:k]
        buf=bright*buf+(1-bright)*.5*(buf+np.roll(buf,1)); buf*=.996
    return out
roots=[110.0,110.0,146.83,110.0,164.81,146.83,110.0,110.0]
beats=int(seconds/beat)
for b in range(beats):
    g=energy(b); i=int(b*beat*sr)
    if g>.52:
        hum=(rng.normal(1,.06))*g
        if b%2==0: k=kick(); add(L,i,k,.8*hum); add(R,i,k,.8*hum)
        else:
            s=snare(); add(L,i,s,.62*hum); add(R,i,s,.56*hum)
        for off in (0,.5):
            h=hat(); ii=int((b+off)*beat*sr); add(L,ii,h,.10*g); add(R,ii,h,.16*g)
    root=roots[(b//4)%8]
    f=root/2 if b%2==0 else root*.75
    t=np.arange(int(beat*sr*.95))/sr
    bass=np.tanh(1.8*np.sin(2*np.pi*f*t))*np.exp(-t*2.5)*(1-np.exp(-t*300))
    add(L,i,bass,.46*(.75+g*.3)); add(R,i,bass,.34*(.75+g*.3))
    for off,dir_ in ((0,1),(.5,-1)):
        ii=int((b+off)*beat*sr)
        for j,mul in enumerate((1,1.26,1.5,2,2.52)):
            p=pluck(root*2*mul,beat*.9,.55)
            add(L,ii+j*200*(1 if dir_>0 else 0),p,.07*g); add(R,ii+j*140,p,.12*g)
    if g>=.8 and b%2==1:
        for j,mul in enumerate((2,2.52,3)):
            p=pluck(root*2*mul,beat*.7,.7); add(L,i,p,.10*g); add(R,i,p,.05*g)
t=np.arange(n)/sr
mel=np.array([1,1.125,1.26,1.5,1.26,1.125,1,.89])
f0=np.zeros(n)
for b in range(beats):
    i=int(b*beat*sr); f0[i:i+int(beat*sr)+1]=220*mel[(b//2)%8]
from scipy.ndimage import uniform_filter1d
f0=uniform_filter1d(f0,int(.03*sr))*(1+.012*np.sin(2*np.pi*5.3*t))
ph=2*np.pi*np.cumsum(f0)/sr
src=sum(np.sin(k*ph)/k**1.1 for k in range(1,48))
vow=(.5+.5*np.sin(2*np.pi*t/(2*beat)))
voc=bp(src,500,1000)*1.0+bp(src,1050,1700)*.7*vow+bp(src,2400,3100)*.45+bp(src,3300,5600)*.60
gate=np.zeros(n)
for b in range(beats):
    if energy(b) in (.6,.62,1.0,1.05) and b%4!=3:
        i=int(b*beat*sr); gate[i:i+int(beat*sr*.85)]=energy(b)
gate=uniform_filter1d(gate,int(.012*sr))
voc=voc*gate
# esses
ess=np.zeros(n)
for b in range(0,beats,6):
    if energy(b)>=.6: add(ess,int((b+.5)*beat*sr),bp(rng.standard_normal(int(.09*sr)),5500,10000)*np.hanning(int(.09*sr)),.34)
voc=voc+ess
ir=rng.standard_normal(int(1.1*sr))*np.exp(-np.arange(int(1.1*sr))/sr*5.5)
wl=fftconvolve(voc,ir)[:n]*.012; wr=fftconvolve(voc,np.roll(ir,311))[:n]*.012
L+=voc*.42+wl; R+=voc*.42+wr
# mud and generation hiss
mud=bp(L+R,180,380); L+=.32*mud; R+=.32*mud
shim=bp(rng.standard_normal(n),9000,16000)*.0035; L+=shim; R-=shim*.5
mix=np.stack([L,R])
mix/=np.max(np.abs(mix))*1.12
if len(sys.argv)>4:
    # Pre-limited version, shaped like a generated song that arrives already fairly loud.
    rmsdb=float(sys.argv[4]); mix*=10**(rmsdb/20)/np.sqrt(np.mean(mix**2))
    mix=np.tanh(mix*1.05)/1.05*.94
x=np.round(np.clip(mix.T,-1,1)*8388607).astype(np.int32)
raw=np.empty((n,6),dtype=np.uint8)
raw[:,0]=x[:,0]&255; raw[:,1]=(x[:,0]>>8)&255; raw[:,2]=(x[:,0]>>16)&255
raw[:,3]=x[:,1]&255; raw[:,4]=(x[:,1]>>8)&255; raw[:,5]=(x[:,1]>>16)&255
with wave.open(sys.argv[1],'wb') as w:
    w.setnchannels(2); w.setsampwidth(3); w.setframerate(sr); w.writeframes(raw.tobytes())
print('wrote',sys.argv[1])
