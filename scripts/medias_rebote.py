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
        if not (0<ts<4*10**9): continue
        datos[tk].append((ts,float(o),float(h),float(l),float(c)))

def sma(x,n):
    cs=np.cumsum(np.insert(x,0,0.0))
    out=np.full(len(x),np.nan); out[n-1:]=(cs[n:]-cs[:-n])/n
    return out

PERIODOS=[20,40,100,200]
VENTANA=10          # velas para resolver
TOQUE=0.25          # ATR: cuenta como visita
UMBRAL=0.5          # ATR: cierre mas alla = rompio / respeto
res=collections.defaultdict(lambda: collections.Counter())
recorridos=collections.defaultdict(list)
tickers=0
for tk,rows in datos.items():
    rows.sort()
    if len(rows)<260: continue
    tickers+=1
    a=np.array(rows,dtype=float)
    hi,lo,cl=a[:,2],a[:,3],a[:,4]
    tr=np.maximum(hi[1:]-lo[1:], np.maximum(abs(hi[1:]-cl[:-1]), abs(lo[1:]-cl[:-1])))
    atr=np.full(len(cl),np.nan); atr[14:]=sma(tr,14)[13:]
    medias={n:sma(cl,n) for n in PERIODOS}
    for n in PERIODOS:
        m=medias[n]
        lado_prev=None; visita=0
        for i in range(n+15, len(cl)-VENTANA):
            if np.isnan(m[i]) or np.isnan(atr[i]) or atr[i]<=0: continue
            lado = 1 if cl[i]>m[i] else -1        # 1 = precio por encima
            if lado_prev is None: lado_prev=lado; continue
            if lado!=lado_prev:                    # cruzo la media: se reinicia el contador
                visita=0; lado_prev=lado; continue
            # visita: el extremo se acerca a menos de 0,25 ATR
            dist = (lo[i]-m[i]) if lado>0 else (m[i]-hi[i])
            if dist > TOQUE*atr[i] or dist < -TOQUE*atr[i]*4: continue
            if dist < 0: continue                  # ya la atraveso de largo: no es visita limpia
            visita+=1
            fut=cl[i+1:i+1+VENTANA]
            extremo = fut[np.argmax(np.abs(fut-m[i]))]
            if lado>0:
                if extremo < m[i]-UMBRAL*atr[i]: r='rompio'
                elif extremo > m[i]+UMBRAL*atr[i]: r='respeto'
                else: r='ni'
                mfe=(np.max(fut)-cl[i])/atr[i]
            else:
                if extremo > m[i]+UMBRAL*atr[i]: r='rompio'
                elif extremo < m[i]-UMBRAL*atr[i]: r='respeto'
                else: r='ni'
                mfe=(cl[i]-np.min(fut))/atr[i]
            v = visita if visita<=3 else 4
            res[(n,v,lado)][r]+=1
            if r=='respeto': recorridos[(n,v,lado)].append(mfe)

print("tickers usados:", tickers)
print()
print(f"{'media':>6} {'visita':>7} {'desde':>8} {'n':>7} {'respeta':>8} {'rompe':>7}   recorrido (ATR)")
for lado,nom in ((1,'arriba'),(-1,'abajo')):
    for n in PERIODOS:
        for v in (1,2,3,4):
            c=res[(n,v,lado)]; tot=sum(c.values())
            if tot<50: continue
            med=np.median(recorridos[(n,v,lado)]) if recorridos[(n,v,lado)] else float('nan')
            print(f"{n:6d} {('4+' if v==4 else v):>7} {nom:>8} {tot:7d} {100*c['respeto']/tot:7.1f}% {100*c['rompio']/tot:6.1f}%   {med:5.2f}")
    print()
