#!/usr/bin/env python3
"""Independent check of a master: ffmpeg's EBU R128 meter, plus how far it moved from the source."""
import subprocess, re, sys, wave, numpy as np
def read(p):
    w=wave.open(p); n=w.getnframes(); b=w.getsampwidth(); r=w.readframes(n)
    a=np.frombuffer(r,dtype=np.uint8).reshape(-1,w.getnchannels()*b)
    if b==3:
        x=(a[:,0::3].astype(np.int32)|a[:,1::3].astype(np.int32)<<8|(a[:,2::3].astype(np.int8).astype(np.int32)<<16))/8388608
    else: x=np.frombuffer(r,dtype='<i2').reshape(-1,2)/32768
    return x,w.getframerate()
def meter(p):
    o=subprocess.run(['ffmpeg','-hide_banner','-nostats','-i',p,'-af','ebur128=peak=true:framelog=quiet','-f','null','-'],capture_output=True,text=True).stderr
    return float(re.search(r'I:\s+(-?[\d.]+) LUFS',o).group(1)),float(re.search(r'LRA:\s+([\d.]+) LU',o).group(1)),float(re.search(r'Peak:\s+(-?[\d.]+) dBFS',o).group(1))
src,sr=read(sys.argv[1])
print(f"{'file':44s} {'LUFS':>6s} {'LRA':>5s} {'dBTP':>6s} {'crest':>6s} {'corr':>6s} {'diff':>6s}")
for p in sys.argv[2:]:
    x,r=read(p); i,l,t=meter(p)
    crest=20*np.log10(np.abs(x).max()/np.sqrt((x**2).mean()))
    c=np.corrcoef(x[:,0],x[:,1])[0,1]
    d=''
    if r==sr and len(x)==len(src):
        g=(x*src).sum()/(x*x).sum(); d=f"{10*np.log10(((src-g*x)**2).sum()/(src**2).sum()):6.1f}"
    print(f"{p.split('scratchpad/')[-1][-44:]:44s} {i:6.1f} {l:5.1f} {t:6.1f} {crest:6.1f} {c:6.2f} {d:>6s}")
