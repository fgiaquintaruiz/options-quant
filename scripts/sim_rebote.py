import gzip, csv, collections, numpy as np

import os
def buscar(nombre):
    """Encuentra el CSV este el script donde este (scripts\\, data\\ o el contenedor de Claude)."""
    aqui = os.path.dirname(os.path.abspath(__file__))
    candidatos = [
        os.path.join(aqui, "..", "data", "e9-export", nombre),
        os.path.join(aqui, "e9-export", nombre),
        os.path.join(aqui, nombre),
    ]
    for c in candidatos:
        if os.path.exists(c):
            return c
    raise SystemExit("No encuentro %s. Corre antes scripts\\export_dia.py" % nombre)


P=buscar('candles_day_1.csv.gz')
datos=collections.defaultdict(list)
with gzip.open(P,'rt') as fh:
    r=csv.reader(fh); next(r)
    for tk,ts,o,h,l,c,v in r:
        try: ts=int(ts)
        except: continue
        if 0<ts<4*10**9: datos[tk].append((ts,float(o),float(h),float(l),float(c)))
def sma(x,n):
    cs=np.cumsum(np.insert(x,0,0.0)); out=np.full(len(x),np.nan); out[n-1:]=(cs[n:]-cs[:-n])/n; return out
VENT=10; TOQUE=0.25
def simular(entrada, lado, hi, lo, i, atr, obj, stop):
    """primero que toque gana; si los dos en la misma vela, cuenta stop (conservador)"""
    for k in range(i+1, min(i+1+VENT, len(hi))):
        if lado>0:
            toca_stop = lo[k] <= entrada-stop*atr
            toca_obj  = hi[k] >= entrada+obj*atr
        else:
            toca_stop = hi[k] >= entrada+stop*atr
            toca_obj  = lo[k] <= entrada-obj*atr
        if toca_stop: return -stop
        if toca_obj: return obj
    # al cierre de la ventana
    fin=(cl_g[k]-entrada)/atr if lado>0 else (entrada-cl_g[k])/atr
    return fin
PER=[20,40,100,200]
vis=collections.defaultdict(list); ctrl=[]
for tk,rows in datos.items():
    rows.sort()
    if len(rows)<260: continue
    a=np.array(rows,dtype=float); hi,lo,cl=a[:,2],a[:,3],a[:,4]; cl_g=cl
    tr=np.maximum(hi[1:]-lo[1:], np.maximum(abs(hi[1:]-cl[:-1]), abs(lo[1:]-cl[:-1])))
    atr=np.full(len(cl),np.nan); atr[14:]=sma(tr,14)[13:]
    medias={n:sma(cl,n) for n in PER}
    marcadas=set()
    for n in PER:
        m=medias[n]; lado_prev=None; visita=0
        for i in range(n+15,len(cl)-VENT):
            if np.isnan(m[i]) or np.isnan(atr[i]) or atr[i]<=0: continue
            lado=1 if cl[i]>m[i] else -1
            if lado_prev is None: lado_prev=lado; continue
            if lado!=lado_prev: visita=0; lado_prev=lado; continue
            dist=(lo[i]-m[i]) if lado>0 else (m[i]-hi[i])
            if dist<0 or dist>TOQUE*atr[i]: continue
            visita+=1
            if visita==1:
                vis[n].append(simular(cl[i],lado,hi,lo,i,atr[i],1.0,1.0))
                if n==200: marcadas.add(i)
    for i in range(215,len(cl)-VENT,7):
        if np.isnan(atr[i]) or atr[i]<=0: continue
        lado = 1 if np.random.rand()<0.5 else -1
        ctrl.append(simular(cl[i],lado,hi,lo,i,atr[i],1.0,1.0))
def r(nom,x):
    x=np.array(x); 
    print(f"{nom:34} n={len(x):6d}  gana {100*np.mean(x>0):5.1f}%   media {np.mean(x):+.3f} ATR   mediana {np.median(x):+.2f}")
print("Simulacion: entrar en la PRIMERA VISITA a la media, objetivo +1 ATR / stop -1 ATR, 10 velas")
for n in PER: r(f"  primera visita a la MA{n}", vis[n])
r("CONTROL (vela al azar, lado al azar)", ctrl)
